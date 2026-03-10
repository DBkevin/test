package com.example.a11yframework.plugins

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.a11yframework.core.IAccessibilityPlugin
import com.example.a11yframework.core.ScrapedData

/**
 * 美团插件
 * 
 * 抓取美团 APP 上的团购数据
 * 
 * 配置项（通过 ConfigManager 设置）:
 * - keywords: List<String> - 要搜索的关键词
 * - targetCities: List<String> - 目标城市
 * - scrapeMode: String - "list" 或 "detail"
 */
class MeituanPlugin : IAccessibilityPlugin {
    
    companion object {
        private const val TAG = "MeituanPlugin"
        
        // 美团相关包名
        val MEITUAN_PACKAGES = listOf(
            "com.sankuai.meituan",           // 美团主 APP
            "com.sankuai.meituan.takeoutnew"  // 美团外卖
        )
        
        // 目标页面特征（用于识别）
        private const val PAGE_SHOP_LIST = "shop_list"      // 商家列表页
        private const val PAGE_SHOP_DETAIL = "shop_detail"  // 商家详情页
        private const val PAGE_SEARCH_RESULT = "search_result" // 搜索结果页
    }
    
    private var service: AccessibilityService? = null
    private var currentMode: String = "list"
    private var keywords: List<String> = emptyList()
    
    override val pluginId: String = "meituan"
    override val pluginName: String = "美团"
    override val targetPackages: List<String> = MEITUAN_PACKAGES
    
    override fun initialize(service: AccessibilityService) {
        this.service = service
        Log.i(TAG, "Plugin initialized")
        
        // 加载配置
        val configManager = service.getConfigManager()
        keywords = configManager.getPluginConfigList(pluginId, "keywords")
        currentMode = configManager.getPluginConfigString(pluginId, "scrapeMode", "list")
        
        Log.d(TAG, "Config loaded: mode=$currentMode, keywords=$keywords")
    }
    
    override fun cleanup() {
        service = null
        Log.i(TAG, "Plugin cleaned up")
    }
    
    override fun isTargetPage(nodeInfo: AccessibilityNodeInfo?): Boolean {
        if (nodeInfo == null) return false
        
        // 通过页面特征判断是否是目标页面
        // 美团商家列表通常包含"团购"、"优惠"等关键词
        
        val searchText = getNodeText(nodeInfo).lowercase()
        
        return searchText.contains("团购") || 
               searchText.contains("优惠") ||
               searchText.contains("商家") ||
               searchText.contains("到店")
    }
    
    override fun scrapeData(nodeInfo: AccessibilityNodeInfo?): List<ScrapedData> {
        if (nodeInfo == null) return emptyList()
        
        val results = mutableListOf<ScrapedData>()
        
        try {
            // 遍历节点树，查找团购信息
            findShopNodes(nodeInfo).forEach { shopNode ->
                val shopData = extractShopData(shopNode)
                if (shopData != null) {
                    results.add(shopData)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scraping data", e)
        }
        
        return results
    }
    
    override fun processData(data: List<ScrapedData>): List<ScrapedData> {
        // 数据过滤和清洗
        return data.filter { item ->
            // 过滤掉没有团购信息的
            item.content["groupBuyTitle"].isNullOrEmpty().not()
        }.map { item ->
            // 数据标准化
            item.copy(
                content = item.content.mapValues { (_, value) ->
                    value?.trim()?.replace("\\s+".toRegex(), " ") ?: ""
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
    
    // ==================== 内部方法 ====================
    
    /**
     * 查找商家节点
     */
    private fun findShopNodes(rootNode: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val shopNodes = mutableListOf<AccessibilityNodeInfo>()
        
        // 方法 1: 通过 text 查找
        val keywordNodes = rootNode.findAccessibilityNodeInfosByText("团购")
        keywordNodes.forEach { node ->
            // 向上查找父节点，直到找到商家卡片
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 5) {
                if (isShopCardNode(parent)) {
                    shopNodes.add(parent)
                    break
                }
                val grandParent = parent.parent
                parent.recycle()
                parent = grandParent
                depth++
            }
        }
        
        // 方法 2: 通过 className 查找（RecyclerView/ListView 项）
        if (shopNodes.isEmpty()) {
            findNodesByClassName(rootNode, "androidx.recyclerview.widget.RecyclerView").forEach { recyclerView ->
                for (i in 0 until recyclerView.childCount) {
                    val child = recyclerView.getChild(i)
                    if (child != null && isShopCardNode(child)) {
                        shopNodes.add(child)
                    }
                }
            }
        }
        
        return shopNodes.distinct()
    }
    
    /**
     * 判断节点是否是商家卡片
     */
    private fun isShopCardNode(node: AccessibilityNodeInfo): Boolean {
        // 商家卡片通常包含：店名、地址、评分、团购信息
        val text = getNodeText(node)
        return text.length > 20 &&  // 卡片内容较多
               (text.contains("¥") || text.contains("元"))  // 包含价格
    }
    
    /**
     * 从商家节点提取数据
     */
    private fun extractShopData(shopNode: AccessibilityNodeInfo): ScrapedData? {
        val text = getNodeText(shopNode)
        
        // 提取关键信息（简单示例，实际需要更复杂的解析）
        val shopName = extractPattern(text, "(.*?)\\s+(?:¥|元)") ?: "未知商家"
        val price = extractPattern(text, "(?:¥|元)(\\d+(?:\\.\\d+)?)") ?: ""
        val groupBuyTitle = extractPattern(text, "(.*?)\\s+¥") ?: ""
        
        return ScrapedData(
            pluginId = pluginId,
            pageType = PAGE_SHOP_LIST,
            dataType = "group_buy",
            content = mapOf(
                "shopName" to shopName,
                "price" to price,
                "groupBuyTitle" to groupBuyTitle,
                "rawText" to text
            ),
            rawText = text
        )
    }
    
    /**
     * 获取节点及其子节点的所有文本
     */
    private fun getNodeText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        
        // 当前节点文本
        node.text?.let { sb.append(it.toString()).append(" ") }
        
        // 子节点文本
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                child.text?.let { sb.append(it.toString()).append(" ") }
                child.recycle()
            }
        }
        
        return sb.toString().trim()
    }
    
    /**
     * 通过 className 查找节点
     */
    private fun findNodesByClassName(root: AccessibilityNodeInfo, className: String): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        
        if (root.className == className) {
            results.add(root)
        }
        
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null) {
                results.addAll(findNodesByClassName(child, className))
                child.recycle()
            }
        }
        
        return results
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
     * 模拟点击（可选功能）
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        
        val path = Path()
        path.moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        
        return service?.dispatchGesture(gesture, null, null) ?: false
    }
    
    /**
     * 设置抓取模式
     */
    fun setScrapeMode(mode: String) {
        currentMode = mode
        val configManager = (service as? com.example.a11yframework.core.FrameworkAccessibilityService)?.getConfigManager()
        configManager?.let { config ->
            val currentConfig = config.getPluginConfigMap(pluginId).toMutableMap()
            currentConfig["scrapeMode"] = mode
            config.setPluginConfigMap(pluginId, currentConfig)
        }
    }
    
    /**
     * 设置关键词
     */
    fun setKeywords(keywords: List<String>) {
        this.keywords = keywords
        val configManager = (service as? com.example.a11yframework.core.FrameworkAccessibilityService)?.getConfigManager()
        configManager?.let { config ->
            val currentConfig = config.getPluginConfigMap(pluginId).toMutableMap()
            currentConfig["keywords"] = keywords
            config.setPluginConfigMap(pluginId, currentConfig)
        }
    }
}
