package com.example.a11yframework.rule.matcher

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.example.a11yframework.rule.MatchRule
import com.example.a11yframework.rule.MatchLogic
import com.example.a11yframework.rule.MatchType
import com.example.a11yframework.rule.PageConfig
import java.util.regex.Pattern

/**
 * 页面匹配器
 * 
 * 功能:
 * 1. 文本匹配器
 * 2. 类名匹配器
 * 3. viewId 匹配器
 * 4. 组合匹配器 (AND/OR)
 * 
 * @param service 无障碍服务实例
 */
class PageMatcher(private val service: AccessibilityService) {
    
    companion object {
        private const val TAG = "PageMatcher"
    }
    
    /**
     * 文本匹配器（缓存）
     */
    private val textCache = mutableMapOf<String, String>()
    
    /**
     * 匹配页面
     * 
     * @param pageConfig 页面配置
     * @param rootNode 根节点
     * @return 匹配结果
     */
    fun match(pageConfig: PageConfig, rootNode: AccessibilityNodeInfo?): MatchResult {
        if (rootNode == null) {
            Log.w(TAG, "根节点为空，无法匹配")
            return MatchResult(false, "根节点为空")
        }
        
        try {
            // 获取页面文本（缓存）
            val pageText = getPageText(rootNode)
            
            // 遍历所有匹配规则
            val matchResults = pageConfig.matchRules.map { rule ->
                matchRule(rule, pageText, rootNode)
            }
            
            // 根据匹配逻辑组合结果
            val matched = when (pageConfig.matchLogic) {
                MatchLogic.AND -> matchResults.all { it }
                MatchLogic.OR -> matchResults.any { it }
            }
            
            val result = MatchResult(
                matched = matched,
                pageId = pageConfig.pageId,
                pageName = pageConfig.pageName,
                matchedRules = pageConfig.matchRules.filterIndexed { index, _ -> 
                    matchResults[index] 
                }
            )
            
            if (matched) {
                Log.i(TAG, "页面匹配成功：${pageConfig.pageName}")
            } else {
                Log.d(TAG, "页面匹配失败：${pageConfig.pageName}")
            }
            
            return result
            
        } catch (e: Exception) {
            Log.e(TAG, "页面匹配异常：${e.message}", e)
            return MatchResult(false, "匹配异常：${e.message}")
        }
    }
    
    /**
     * 匹配单个规则
     */
    private fun matchRule(rule: MatchRule, pageText: String, rootNode: AccessibilityNodeInfo?): Boolean {
        return when (rule.type) {
            MatchType.TEXT_CONTAINS -> matchTextContains(rule, pageText)
            MatchType.TEXT_EQUALS -> matchTextEquals(rule, pageText)
            MatchType.CLASS_NAME -> matchClassName(rule, rootNode)
            MatchType.VIEW_ID -> matchViewId(rule, rootNode)
            MatchType.REGEX -> matchRegex(rule, pageText)
        }
    }
    
    /**
     * 文本包含匹配
     */
    private fun matchTextContains(rule: MatchRule, pageText: String): Boolean {
        val values = rule.values ?: return false
        
        val results = values.map { value ->
            pageText.contains(value, ignoreCase = true)
        }
        
        return when (rule.logic) {
            MatchLogic.AND -> results.all { it }
            MatchLogic.OR -> results.any { it }
        }
    }
    
    /**
     * 文本完全匹配
     */
    private fun matchTextEquals(rule: MatchRule, pageText: String): Boolean {
        val values = rule.values ?: return false
        
        val results = values.map { value ->
            pageText.equals(value, ignoreCase = true)
        }
        
        return when (rule.logic) {
            MatchLogic.AND -> results.all { it }
            MatchLogic.OR -> results.any { it }
        }
    }
    
    /**
     * 类名匹配（支持通配符）
     */
    private fun matchClassName(rule: MatchRule, rootNode: AccessibilityNodeInfo?): Boolean {
        val pattern = rule.pattern ?: return false
        val regexPattern = pattern.replace("*", ".*")
        
        return findNodeByClassName(rootNode, regexPattern) != null
    }
    
    /**
     * viewId 匹配（支持通配符）
     */
    private fun matchViewId(rule: MatchRule, rootNode: AccessibilityNodeInfo?): Boolean {
        val pattern = rule.pattern ?: return false
        val regexPattern = pattern.replace("*", ".*")
        
        return findNodeByViewId(rootNode, regexPattern) != null
    }
    
    /**
     * 正则表达式匹配
     */
    private fun matchRegex(rule: MatchRule, pageText: String): Boolean {
        val pattern = rule.pattern ?: return false
        
        return try {
            val regex = Pattern.compile(pattern)
            regex.matcher(pageText).find()
        } catch (e: Exception) {
            Log.e(TAG, "正则表达式编译失败：$pattern", e)
            false
        }
    }
    
    /**
     * 获取页面文本（带缓存）
     */
    private fun getPageText(rootNode: AccessibilityNodeInfo?): String {
        val cacheKey = "page_text_${System.currentTimeMillis() / 60000}" // 每分钟缓存
        
        return textCache.getOrPut(cacheKey) {
            getAllNodeText(rootNode)
        }
    }
    
    /**
     * 获取所有节点文本
     */
    private fun getAllNodeText(rootNode: AccessibilityNodeInfo?): String {
        if (rootNode == null) return ""
        
        val text = StringBuilder()
        
        // 深度优先遍历
        fun traverse(node: AccessibilityNodeInfo) {
            // 添加节点文本
            node.text?.let {
                text.append(it.toString()).append(" ")
            }
            
            // 添加 contentDescription
            node.contentDescription?.let {
                text.append(it.toString()).append(" ")
            }
            
            // 遍历子节点
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    traverse(child)
                    child.recycle()
                }
            }
        }
        
        traverse(rootNode)
        
        return text.toString().trim()
    }
    
    /**
     * 按类名查找节点
     */
    private fun findNodeByClassName(rootNode: AccessibilityNodeInfo?, regexPattern: String): AccessibilityNodeInfo? {
        if (rootNode == null) return null
        
        val regex = Pattern.compile(regexPattern, Pattern.CASE_INSENSITIVE)
        
        fun traverse(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            // 检查当前节点
            val className = node.className?.toString() ?: ""
            if (regex.matcher(className).matches()) {
                return node
            }
            
            // 遍历子节点
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    val result = traverse(child)
                    child.recycle()
                    if (result != null) return result
                }
            }
            
            return null
        }
        
        return traverse(rootNode)
    }
    
    /**
     * 按 viewId 查找节点
     */
    private fun findNodeByViewId(rootNode: AccessibilityNodeInfo?, regexPattern: String): AccessibilityNodeInfo? {
        if (rootNode == null) return null
        
        val regex = Pattern.compile(regexPattern, Pattern.CASE_INSENSITIVE)
        
        fun traverse(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            // 检查当前节点
            val viewId = node.viewIdResourceName ?: ""
            if (regex.matcher(viewId).matches()) {
                return node
            }
            
            // 遍历子节点
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    val result = traverse(child)
                    child.recycle()
                    if (result != null) return result
                }
            }
            
            return null
        }
        
        return traverse(rootNode)
    }
    
    /**
     * 清除缓存
     */
    fun clearCache() {
        textCache.clear()
        Log.d(TAG, "文本缓存已清除")
    }
}

/**
 * 匹配结果
 * 
 * @property matched 是否匹配成功
 * @property pageId 页面 ID
 * @property pageName 页面名称
 * @property matchedRules 匹配成功的规则
 * @property errorMessage 错误消息
 */
data class MatchResult(
    val matched: Boolean,
    val pageId: String? = null,
    val pageName: String? = null,
    val matchedRules: List<MatchRule> = emptyList(),
    val errorMessage: String? = null
)
