package com.example.a11yframework.plugins

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.a11yframework.core.IAccessibilityPlugin
import com.example.a11yframework.core.ScrapedData
import com.example.a11yframework.core.FrameworkAccessibilityService

/**
 * 美团插件
 */
class MeituanPlugin : IAccessibilityPlugin {
    
    companion object {
        private const val TAG = "MeituanPlugin"
        val MEITUAN_PACKAGES = listOf(
            "com.sankuai.meituan",
            "com.sankuai.meituan.takeoutnew"
        )
        private const val PAGE_SHOP_LIST = "shop_list"
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
        
        val frameworkService = service as? FrameworkAccessibilityService
        val configManager = frameworkService?.configManager
        
        // 加载配置的关键词
        val loadedKeywords = configManager?.getPluginConfigList(pluginId, "keywords")
        keywords = loadedKeywords?.filter { it.isNotEmpty() } ?: listOf("团购", "优惠", "套餐", "商家")
        
        currentMode = configManager?.getPluginConfigString(pluginId, "scrapeMode", "list") ?: "list"
        
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
            listOf("团购", "优惠", "套餐", "商家", "到店")
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
        return data.filter { item ->
            item.content["groupBuyTitle"].isNullOrEmpty().not()
        }.map { item ->
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
    
    private fun findShopNodes(rootNode: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val shopNodes = mutableListOf<AccessibilityNodeInfo>()
        
        val keywordNodes = rootNode.findAccessibilityNodeInfosByText("团购")
        keywordNodes.forEach { node ->
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
        
        return shopNodes.distinct()
    }
    
    private fun isShopCardNode(node: AccessibilityNodeInfo): Boolean {
        val text = getNodeText(node)
        return text.length > 20 && text.contains("¥")
    }
    
    private fun extractShopData(shopNode: AccessibilityNodeInfo): ScrapedData? {
        val text = getNodeText(shopNode)
        
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
    
    private fun getNodeText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        node.text?.let { sb.append(it.toString()).append(" ") }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                child.text?.let { sb.append(it.toString()).append(" ") }
                child.recycle()
            }
        }
        
        return sb.toString().trim()
    }
    
    private fun extractPattern(text: String, pattern: String): String? {
        return try {
            val regex = Regex(pattern)
            regex.find(text)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }
}
