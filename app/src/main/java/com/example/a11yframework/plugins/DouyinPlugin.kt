package com.example.a11yframework.plugins

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.a11yframework.core.IAccessibilityPlugin
import com.example.a11yframework.core.ScrapedData

/**
 * 抖音插件
 * 
 * 抓取抖音 APP 上的团购数据
 * 
 * 配置项:
 * - keywords: List<String> - 要搜索的关键词
 * - targetCities: List<String> - 目标城市
 * - scrapeMode: String - "feed" (推荐页), "search" (搜索页), "shop" (商家页)
 */
class DouyinPlugin : IAccessibilityPlugin {
    
    companion object {
        private const val TAG = "DouyinPlugin"
        
        // 抖音包名
        val DOUYIN_PACKAGES = listOf(
            "com.ss.android.ugc.aweme",      // 抖音主 APP
            "com.ss.android.ugc.aweme.lite"  // 抖音极速版
        )
        
        // 页面类型
        private const val PAGE_FEED = "feed"           // 推荐页
        private const val PAGE_SEARCH = "search"       // 搜索页
        private const val PAGE_SHOP = "shop"           // 商家/团购页
        private const val PAGE_LIVE = "live"           // 直播页
    }
    
    private var service: AccessibilityService? = null
    private var currentMode: String = "feed"
    private var keywords: List<String> = emptyList()
    
    override val pluginId: String = "douyin"
    override val pluginName: String = "抖音"
    override val targetPackages: List<String> = DOUYIN_PACKAGES
    
    override fun initialize(service: AccessibilityService) {
        this.service = service
        Log.i(TAG, "Plugin initialized")
        
        // 加载配置
        val frameworkService = service as? com.example.a11yframework.core.FrameworkAccessibilityService
        val configManager = frameworkService?.configManager
        keywords = configManager?.getPluginConfigList(pluginId, "keywords") ?: emptyList()
        currentMode = configManager?.getPluginConfigString(pluginId, "scrapeMode", "feed") ?: "feed"
        
        Log.d(TAG, "Config loaded: mode=$currentMode, keywords=$keywords")
    }
    
    override fun cleanup() {
        service = null
        Log.i(TAG, "Plugin cleaned up")
    }
    
    override fun isTargetPage(nodeInfo: AccessibilityNodeInfo?): Boolean {
        if (nodeInfo == null) return false
        
        // 抖音团购页面特征
        val searchText = getNodeText(nodeInfo).lowercase()
        
        return searchText.contains("团购") || 
               searchText.contains("优惠") ||
               searchText.contains("到店") ||
               searchText.contains("券") ||
               searchText.contains("套餐")
    }
    
    override fun scrapeData(nodeInfo: AccessibilityNodeInfo?): List<ScrapedData> {
        if (nodeInfo == null) return emptyList()
        
        val results = mutableListOf<ScrapedData>()
        
        try {
            when (currentMode) {
                "feed" -> scrapeFeedData(nodeInfo, results)
                "search" -> scrapeSearchData(nodeInfo, results)
                "shop" -> scrapeShopData(nodeInfo, results)
                else -> scrapeFeedData(nodeInfo, results)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scraping data", e)
        }
        
        return results
    }
    
    override fun processData(data: List<ScrapedData>): List<ScrapedData> {
        // 去重：相同团购标题只保留一条
        val seen = mutableSetOf<String>()
        return data.filter { item ->
            val key = item.content["groupBuyTitle"] ?: ""
            if (key in seen) false else { seen.add(key); true }
        }.map { item ->
            // 数据清洗
            item.copy(
                content = item.content.mapValues { (_, value) ->
                    value?.trim()
                        ?.replace("\\s+".toRegex(), " ")
                        ?.replace("[\\u200B-\\u200D\\uFEFF]".toRegex(), "")  // 移除零宽字符
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
    
    // ==================== 各模式抓取逻辑 ====================
    
    /**
     * 抓取推荐页数据
     */
    private fun scrapeFeedData(rootNode: AccessibilityNodeInfo, results: MutableList<ScrapedData>) {
        Log.d(TAG, "Scraping feed data...")
        
        // 查找包含团购信息的节点
        findNodesByText(rootNode, "团购").forEach { node ->
            val cardNode = findParentCard(node)
            if (cardNode != null) {
                val data = extractGroupBuyData(cardNode)
                if (data != null) {
                    results.add(data)
                }
            }
        }
    }
    
    /**
     * 抓取搜索页数据
     */
    private fun scrapeSearchData(rootNode: AccessibilityNodeInfo, results: MutableList<ScrapedData>) {
        Log.d(TAG, "Scraping search data...")
        
        // 搜索结果通常是列表形式
        findNodesByClassName(rootNode, "androidx.recyclerview.widget.RecyclerView").forEach { recyclerView ->
            for (i in 0 until recyclerView.childCount) {
                val item = recyclerView.getChild(i)
                if (item != null) {
                    val data = extractGroupBuyData(item)
                    if (data != null) {
                        results.add(data)
                    }
                    item.recycle()
                }
            }
        }
    }
    
    /**
     * 抓取商家页数据
     */
    private fun scrapeShopData(rootNode: AccessibilityNodeInfo, results: MutableList<ScrapedData>) {
        Log.d(TAG, "Scraping shop data...")
        
        // 商家详情页，提取完整信息
        val data = extractShopDetailData(rootNode)
        if (data != null) {
            results.add(data)
        }
    }
    
    // ==================== 数据提取 ====================
    
    /**
     * 提取团购数据
     */
    private fun extractGroupBuyData(node: AccessibilityNodeInfo): ScrapedData? {
        val text = getNodeText(node)
        
        // 简单解析（实际需要根据具体 UI 结构调整）
        val groupBuyTitle = extractFirstLine(text)
        val price = extractPrice(text)
        val originalPrice = extractOriginalPrice(text)
        val salesCount = extractSales(text)
        
        if (groupBuyTitle.isNullOrEmpty()) return null
        
        return ScrapedData(
            pluginId = pluginId,
            pageType = PAGE_FEED,
            dataType = "group_buy",
            content = mapOf(
                "groupBuyTitle" to groupBuyTitle,
                "price" to price,
                "originalPrice" to originalPrice,
                "salesCount" to salesCount,
                "rawText" to text
            ),
            rawText = text
        )
    }
    
    /**
     * 提取商家详情数据
     */
    private fun extractShopDetailData(node: AccessibilityNodeInfo): ScrapedData? {
        val text = getNodeText(node)
        
        return ScrapedData(
            pluginId = pluginId,
            pageType = PAGE_SHOP,
            dataType = "shop_detail",
            content = mapOf(
                "shopName" to extractShopName(text),
                "address" to extractAddress(text),
                "rating" to extractRating(text),
                "groupBuyList" to text,  // 团购列表
                "rawText" to text
            ),
            rawText = text
        )
    }
    
    // ==================== 工具方法 ====================
    
    /**
     * 获取节点文本
     */
    private fun getNodeText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        collectNodeText(node, sb, 0)
        return sb.toString().trim()
    }
    
    /**
     * 递归收集节点文本
     */
    private fun collectNodeText(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 10) return  // 防止过深
        
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
     * 查找包含指定文本的节点
     */
    private fun findNodesByText(root: AccessibilityNodeInfo, text: String): List<AccessibilityNodeInfo> {
        return root.findAccessibilityNodeInfosByText(text)
    }
    
    /**
     * 通过 className 查找节点
     */
    private fun findNodesByClassName(root: AccessibilityNodeInfo, className: String): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        findNodesByClassNameRecursive(root, className, results)
        return results
    }
    
    private fun findNodesByClassNameRecursive(
        node: AccessibilityNodeInfo, 
        className: String, 
        results: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.className == className) {
            results.add(node)
            return
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
        return text.length > 30 && text.contains("¥")
    }
    
    // ==================== 文本解析 ====================
    
    private fun extractFirstLine(text: String): String {
        return text.split("\n").firstOrNull()?.trim() ?: ""
    }
    
    private fun extractPrice(text: String): String {
        return Regex("¥(\\d+(?:\\.\\d+)?)").find(text)?.groupValues?.get(1) ?: ""
    }
    
    private fun extractOriginalPrice(text: String): String {
        return Regex("¥(\\d+(?:\\.\\d+)?)").findAll(text)
            .drop(1)
            .firstOrNull()
            ?.groupValues
            ?.get(1) ?: ""
    }
    
    private fun extractSales(text: String): String {
        return Regex("(\\d+(?:\\.\\d+)?[kKwW]?) 已售").find(text)?.groupValues?.get(1) ?: ""
    }
    
    private fun extractShopName(text: String): String {
        return text.split("\n").firstOrNull()?.trim() ?: ""
    }
    
    private fun extractAddress(text: String): String {
        return Regex("(地址 | 位置)[:：]?\\s*(.+?)(?:\\s+¥|\\s+评分|\\s+电话|$)").find(text)?.groupValues?.get(2) ?: ""
    }
    
    private fun extractRating(text: String): String {
        return Regex("(\\d+\\.\\d+) 分").find(text)?.groupValues?.get(1) ?: ""
    }
    
    // ==================== 配置方法 ====================
    
    fun setScrapeMode(mode: String) {
        currentMode = mode
        (service as? com.example.a11yframework.core.FrameworkAccessibilityService)?.configManager?.let { config ->
            val currentConfig = config.getPluginConfigMap(pluginId).toMutableMap()
            currentConfig["scrapeMode"] = mode
            config.setPluginConfigMap(pluginId, currentConfig)
        }
    }
    
    fun setKeywords(keywords: List<String>) {
        this.keywords = keywords
        (service as? com.example.a11yframework.core.FrameworkAccessibilityService)?.configManager?.let { config ->
            val currentConfig = config.getPluginConfigMap(pluginId).toMutableMap()
            currentConfig["keywords"] = keywords
            config.setPluginConfigMap(pluginId, currentConfig)
        }
    }
}
