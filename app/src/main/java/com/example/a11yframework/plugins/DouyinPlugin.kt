package com.example.a11yframework.plugins

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.a11yframework.core.IAccessibilityPlugin
import com.example.a11yframework.core.ScrapedData
import com.example.a11yframework.core.FrameworkAccessibilityService

/**
 * 抖音插件
 */
class DouyinPlugin : IAccessibilityPlugin {
    
    companion object {
        private const val TAG = "DouyinPlugin"
        val DOUYIN_PACKAGES = listOf(
            "com.ss.android.ugc.aweme",
            "com.ss.android.ugc.aweme.lite"
        )
        private const val PAGE_FEED = "feed"
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
        
        val frameworkService = service as? FrameworkAccessibilityService
        val configManager = frameworkService?.configManager
        
        // 加载配置的关键词
        val loadedKeywords = configManager?.getPluginConfigList(pluginId, "keywords")
        keywords = loadedKeywords?.filter { it.isNotEmpty() } ?: listOf("团购", "优惠", "套餐", "券")
        
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
        
        // 从配置读取关键词
        val keywords = keywords.ifEmpty {
            listOf("团购", "优惠", "套餐", "券", "到店")
        }
        
        val isTarget = keywords.any { keyword ->
            searchText.contains(keyword.lowercase())
        }
        
        if (isTarget) {
            Log.i(TAG, "Target page detected! Keywords: $keywords")
        }
        return isTarget
    }
    
    override fun scrapeData(nodeInfo: AccessibilityNodeInfo?): List<ScrapedData> {
        if (nodeInfo == null) return emptyList()
        
        val results = mutableListOf<ScrapedData>()
        
        try {
            scrapeFeedData(nodeInfo, results)
        } catch (e: Exception) {
            Log.e(TAG, "Error scraping data", e)
        }
        
        return results
    }
    
    override fun processData(data: List<ScrapedData>): List<ScrapedData> {
        val seen = mutableSetOf<String>()
        return data.filter { item ->
            val key = item.content["groupBuyTitle"] ?: ""
            if (key in seen) false else { seen.add(key); true }
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
    
    private fun scrapeFeedData(rootNode: AccessibilityNodeInfo, results: MutableList<ScrapedData>) {
        Log.d(TAG, "Scraping feed data...")
        
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
    
    private fun extractGroupBuyData(node: AccessibilityNodeInfo): ScrapedData? {
        val text = getNodeText(node)
        
        val groupBuyTitle = extractFirstLine(text)
        val price = extractPrice(text)
        
        if (groupBuyTitle.isNullOrEmpty()) return null
        
        return ScrapedData(
            pluginId = pluginId,
            pageType = PAGE_FEED,
            dataType = "group_buy",
            content = mapOf(
                "groupBuyTitle" to groupBuyTitle,
                "price" to price,
                "rawText" to text
            ),
            rawText = text
        )
    }
    
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
    
    private fun findNodesByText(root: AccessibilityNodeInfo, text: String): List<AccessibilityNodeInfo> {
        return root.findAccessibilityNodeInfosByText(text)
    }
    
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
    
    private fun isCardNode(node: AccessibilityNodeInfo): Boolean {
        val text = getNodeText(node)
        return text.length > 30 && text.contains("¥")
    }
    
    private fun extractFirstLine(text: String): String {
        return text.split("\n").firstOrNull()?.trim() ?: ""
    }
    
    private fun extractPrice(text: String): String {
        return Regex("¥(\\d+(?:\\.\\d+)?)").find(text)?.groupValues?.get(1) ?: ""
    }
}
