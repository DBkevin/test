package com.example.a11yframework.plugins

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.a11yframework.core.IAccessibilityPlugin
import com.example.a11yframework.core.ScrapedData
import com.example.a11yframework.core.FrameworkAccessibilityService

/**
 * 抖音插件 - 医美团购数据抓取
 * 
 * 抓取目标:
 * 1. 医院名称
 * 2. 荣誉项
 * 3. 团单信息（头图、名称、价格、销量）
 */
class DouyinPlugin : IAccessibilityPlugin {
    
    companion object {
        private const val TAG = "DouyinPlugin"
        
        // 抖音包名
        val DOUYIN_PACKAGES = listOf(
            "com.ss.android.ugc.aweme",
            "com.ss.android.ugc.aweme.lite"
        )
        
        // 关键词配置
        private val HOSPITAL_KEYWORDS = listOf("医院", "门诊", "整形", "美容", "医美", " clinic")
        private val HONOR_KEYWORDS = listOf("认证", "奖", "基地", "定点", "指定", "授权", "合作")
        private val GROUP_BUY_KEYWORDS = listOf("团购", "套餐", "体验", "次卡", "疗程")
        private val PRICE_KEYWORDS = listOf("¥", "元")
        private val SALES_KEYWORDS = listOf("已售", "销量", "购买")
        
        // 页面类型
        private const val PAGE_HOSPITAL_DETAIL = "hospital_detail"
        private const val PAGE_SEARCH_RESULT = "search_result"
    }
    
    private var service: AccessibilityService? = null
    private var currentMode: String = "feed"
    private var keywords: List<String> = emptyList()
    
    override val pluginId: String = "douyin"
    override val pluginName: String = "功能 A"
    override val targetPackages: List<String> = DOUYIN_PACKAGES
    
    override fun initialize(service: AccessibilityService) {
        this.service = service
        Log.i(TAG, "Plugin initialized")
        
        val frameworkService = service as? FrameworkAccessibilityService
        val configManager = frameworkService?.configManager
        
        val loadedKeywords = configManager?.getPluginConfigList(pluginId, "keywords")
        keywords = loadedKeywords?.filter { it.isNotEmpty() } ?: listOf("黄金微针", "水光针", "热玛吉")
        
        currentMode = configManager?.getPluginConfigString(pluginId, "scrapeMode", "feed") ?: "feed"
        
        Log.d(TAG, "Config loaded: mode=$currentMode, keywords=$keywords")
    }
    
    override fun cleanup() {
        service = null
        Log.i(TAG, "Plugin cleaned up")
    }
    
    override fun isTargetPage(nodeInfo: AccessibilityNodeInfo?): Boolean {
        if (nodeInfo == null) return false
        
        val searchText = getNodeText(nodeInfo).lowercase()
        
        // 检查是否是医院/医美相关页面
        val isHospitalPage = HOSPITAL_KEYWORDS.any { keyword ->
            searchText.contains(keyword)
        }
        
        // 检查是否包含配置的关键词
        val hasKeyword = keywords.any { keyword ->
            searchText.contains(keyword.lowercase())
        }
        
        // 检查是否有团购相关信息
        val hasGroupBuy = GROUP_BUY_KEYWORDS.any { keyword ->
            searchText.contains(keyword)
        }
        
        val isTarget = (isHospitalPage || hasKeyword) && hasGroupBuy
        
        if (isTarget) {
            Log.i(TAG, "Target page detected! Keywords: $keywords")
        }
        
        return isTarget
    }
    
    override fun scrapeData(nodeInfo: AccessibilityNodeInfo?): List<ScrapedData> {
        if (nodeInfo == null) return emptyList()
        
        val results = mutableListOf<ScrapedData>()
        
        try {
            // 1. 提取医院信息
            val hospitalInfo = extractHospitalInfo(nodeInfo)
            
            // 2. 提取团单列表
            val groupBuyList = extractGroupBuyList(nodeInfo)
            
            // 3. 合并数据
            groupBuyList.forEach { groupBuy ->
                val data = ScrapedData(
                    pluginId = pluginId,
                    pageType = PAGE_HOSPITAL_DETAIL,
                    dataType = "hospital_group_buy",
                    content = mapOf(
                        "hospitalName" to hospitalInfo.hospitalName,
                        "honors" to hospitalInfo.honors,
                        "groupBuyTitle" to groupBuy.title,
                        "price" to groupBuy.price,
                        "sales" to groupBuy.sales,
                        "imageUrl" to groupBuy.imageUrl,
                        "rawText" to groupBuy.rawText
                    ),
                    rawText = "${hospitalInfo.hospitalName} ${groupBuy.title}"
                )
                results.add(data)
            }
            
            if (results.isNotEmpty()) {
                Log.i(TAG, "Scraped ${results.size} records from ${hospitalInfo.hospitalName}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error scraping data", e)
        }
        
        return results
    }
    
    override fun processData(data: List<ScrapedData>): List<ScrapedData> {
        // 数据清洗和标准化
        return data.filter { item ->
            // 过滤掉没有团购标题的
            item.content["groupBuyTitle"].isNullOrEmpty().not()
        }.map { item ->
            item.copy(
                content = item.content.mapValues { (_, value) ->
                    value?.trim()
                        ?.replace("\\s+".toRegex(), " ")
                        ?.replace("[\\u200B-\\u200D\\uFEFF]".toRegex(), "")
                        ?: ""
                }
            )
        }
    }
    
    override fun onActivate() {
        Log.i(TAG, "Plugin activated")
    }
    
    override fun onDeactivate() {
        Log.i(TAG, "Plugin deactivated")
    }
    
    // ==================== 数据提取方法 ====================
    
    /**
     * 医院信息数据类
     */
    data class HospitalInfo(
        val hospitalName: String = "",
        val honors: String = ""
    )
    
    /**
     * 团单信息数据类
     */
    data class GroupBuyInfo(
        val title: String = "",
        val price: String = "",
        val sales: String = "",
        val imageUrl: String = "",
        val rawText: String = ""
    )
    
    /**
     * 提取医院信息
     */
    private fun extractHospitalInfo(rootNode: AccessibilityNodeInfo): HospitalInfo {
        var hospitalName = ""
        var honors = ""
        
        // 查找医院名称（页面顶部，包含医院关键词）
        val hospitalNode = findNodeByKeywords(rootNode, HOSPITAL_KEYWORDS, maxDepth = 3)
        hospitalName = hospitalNode?.let { getNodeText(it) } ?: ""
        
        // 查找荣誉项（在医院名称附近，包含荣誉关键词）
        if (hospitalNode != null) {
            val honorNode = findNodeByKeywords(rootNode, HONOR_KEYWORDS, maxDepth = 5)
            honors = honorNode?.let { getNodeText(it) } ?: ""
        }
        
        Log.d(TAG, "Hospital: $hospitalName, Honors: $honors")
        
        return HospitalInfo(hospitalName, honors)
    }
    
    /**
     * 提取团单列表
     */
    private fun extractGroupBuyList(rootNode: AccessibilityNodeInfo): List<GroupBuyInfo> {
        val results = mutableListOf<GroupBuyInfo>()
        
        // 方法 1: 通过 RecyclerView 查找
        findNodesByClassName(rootNode, "androidx.recyclerview.widget.RecyclerView").forEach { recyclerView ->
            for (i in 0 until recyclerView.childCount) {
                val item = recyclerView.getChild(i)
                if (item != null) {
                    val groupBuy = extractGroupBuyItem(item)
                    if (groupBuy.title.isNotEmpty()) {
                        results.add(groupBuy)
                    }
                    item.recycle()
                }
            }
        }
        
        // 方法 2: 通过关键词查找团单卡片
        if (results.isEmpty()) {
            findNodesByKeywords(rootNode, GROUP_BUY_KEYWORDS).forEach { node ->
                val cardNode = findParentCard(node)
                if (cardNode != null) {
                    val groupBuy = extractGroupBuyItem(cardNode)
                    if (groupBuy.title.isNotEmpty()) {
                        results.add(groupBuy)
                    }
                }
            }
        }
        
        Log.d(TAG, "Found ${results.size} group buy items")
        
        return results
    }
    
    /**
     * 从单个卡片中提取团单信息
     */
    private fun extractGroupBuyItem(cardNode: AccessibilityNodeInfo): GroupBuyInfo {
        val cardText = getNodeText(cardNode)
        
        // 提取团单名称
        val title = findNodeByKeywords(cardNode, GROUP_BUY_KEYWORDS)?.let { getNodeText(it) }
            ?: extractPattern(cardText, "(.*?)\\s*¥") ?: ""
        
        // 提取价格
        val price = findNodeByText(cardNode, "¥")?.let { getNodeText(it) }
            ?: extractPattern(cardText, "¥(\\d+(?:\\.\\d+)?)")?.let { "¥$it" }
            ?: ""
        
        // 提取销量
        val sales = findNodeByKeywords(cardNode, SALES_KEYWORDS)?.let { getNodeText(it) }
            ?: extractPattern(cardText, "(已售\\d+[+kKwW]?)") ?: ""
        
        // 提取图片 URL（如果有）
        val imageUrl = findImageView(cardNode)
        
        return GroupBuyInfo(title, price, sales, imageUrl, cardText)
    }
    
    // ==================== 工具方法 ====================
    
    /**
     * 根据关键词查找节点
     */
    private fun findNodeByKeywords(
        rootNode: AccessibilityNodeInfo,
        keywords: List<String>,
        maxDepth: Int = 5
    ): AccessibilityNodeInfo? {
        return findNodeByCondition(rootNode, { node ->
            val text = getNodeText(node).lowercase()
            keywords.any { keyword -> text.contains(keyword.lowercase()) }
        }, maxDepth)
    }
    
    /**
     * 根据文本查找节点
     */
    private fun findNodeByText(
        rootNode: AccessibilityNodeInfo,
        text: String
    ): AccessibilityNodeInfo? {
        return findNodeByCondition(rootNode, { node ->
            getNodeText(node).contains(text)
        })
    }
    
    /**
     * 根据条件查找节点
     */
    private fun findNodeByCondition(
        node: AccessibilityNodeInfo,
        condition: (AccessibilityNodeInfo) -> Boolean,
        maxDepth: Int = 5
    ): AccessibilityNodeInfo? {
        if (condition(node)) {
            return node
        }
        
        if (maxDepth <= 0) return null
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val result = findNodeByCondition(child, condition, maxDepth - 1)
                if (result != null) {
                    return result
                }
                child.recycle()
            }
        }
        
        return null
    }
    
    /**
     * 查找所有匹配关键词的节点
     */
    private fun findNodesByKeywords(
        rootNode: AccessibilityNodeInfo,
        keywords: List<String>
    ): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        findNodesByCondition(rootNode, { node ->
            val text = getNodeText(node).lowercase()
            keywords.any { keyword -> text.contains(keyword.lowercase()) }
        }, results)
        return results
    }
    
    /**
     * 根据 className 查找节点
     */
    private fun findNodesByClassName(
        rootNode: AccessibilityNodeInfo,
        className: String
    ): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        findNodesByClassNameRecursive(rootNode, className, results)
        return results
    }
    
    private fun findNodesByClassNameRecursive(
        node: AccessibilityNodeInfo,
        className: String,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.className == className) {
            results.add(node)
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findNodesByClassNameRecursive(child, className, results)
                child.recycle()
            }
        }
    }
    
    /**
     * 查找父级卡片节点
     */
    private fun findParentCard(node: AccessibilityNodeInfo, maxDepth: Int = 5): AccessibilityNodeInfo? {
        var parent = node.parent
        var depth = 0
        
        while (parent != null && depth < maxDepth) {
            if (isCardNode(parent)) {
                return parent
            }
            val grandParent = parent.parent
            parent.recycle()
            parent = grandParent
            depth++
        }
        
        return parent
    }
    
    /**
     * 判断是否是卡片节点
     */
    private fun isCardNode(node: AccessibilityNodeInfo): Boolean {
        val text = getNodeText(node)
        return text.length > 30 && (text.contains("¥") || text.contains("团购"))
    }
    
    /**
     * 查找 ImageView 节点
     */
    private fun findImageView(node: AccessibilityNodeInfo): String {
        // 尝试获取图片 URL（如果有）
        val imageView = findNodeByClassName(node, "android.widget.ImageView")
        return imageView?.let {
            // 这里可以尝试获取图片 URL，但需要特殊权限
            // 暂时返回空字符串
            ""
        } ?: ""
    }
    
    /**
     * 获取节点文本
     */
    private fun getNodeText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        collectNodeText(node, sb, 0)
        return sb.toString().trim()
    }
    
    private fun collectNodeText(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 10) return
        node.text?.let { sb.append(it.toString()).append(" ") }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectNodeText(child, sb, depth + 1)
                child.recycle()
            }
        }
    }
    
    /**
     * 正则提取
     */
    private fun extractPattern(text: String, pattern: String): String? {
        return try {
            val regex = Regex(pattern)
            regex.find(text)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 根据 className 查找单个节点
     */
    private fun findNodeByClassName(node: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        return findNodeByCondition(node, { it.className == className })
    }
}
