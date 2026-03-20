package com.example.a11yframework.rule.matcher

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.a11yframework.rule.MatchRule
import com.example.a11yframework.rule.MatchLogic
import com.example.a11yframework.rule.MatchType
import com.example.a11yframework.rule.PageConfig
import com.example.a11yframework.utils.NodeUtils
import java.util.regex.Pattern
import kotlin.LazyThreadSafetyMode

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
        private const val PAGE_TEXT_MAX_DEPTH = 12
        private const val PAGE_TEXT_MAX_NODES = 220
        private const val PAGE_TEXT_MAX_LENGTH = 4000
        private const val NODE_SEARCH_MAX_DEPTH = 18
        private const val NODE_SEARCH_MAX_NODES = 260
    }
    
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
            val pageText by lazy(LazyThreadSafetyMode.NONE) {
                getPageText(rootNode)
            }
            
            // 遍历所有匹配规则
            val matchResults = pageConfig.matchRules.map { rule ->
                matchRule(rule, rootNode) { pageText }
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
    private fun matchRule(
        rule: MatchRule,
        rootNode: AccessibilityNodeInfo?,
        pageTextProvider: () -> String
    ): Boolean {
        return when (rule.type) {
            MatchType.TEXT_CONTAINS -> matchTextContains(rule, rootNode, pageTextProvider)
            MatchType.TEXT_EQUALS -> matchTextEquals(rule, rootNode, pageTextProvider)
            MatchType.CLASS_NAME -> matchClassName(rule, rootNode)
            MatchType.VIEW_ID -> matchViewId(rule, rootNode)
            MatchType.REGEX -> matchRegex(rule, pageTextProvider())
        }
    }
    
    /**
     * 文本包含匹配
     */
    private fun matchTextContains(
        rule: MatchRule,
        rootNode: AccessibilityNodeInfo?,
        pageTextProvider: () -> String
    ): Boolean {
        val values = rule.values ?: return false

        val results = values.map { value ->
            hasTextMatch(rule, rootNode, value, exact = false, pageTextProvider)
        }

        return when (rule.logic) {
            MatchLogic.AND -> results.all { it }
            MatchLogic.OR -> results.any { it }
        }
    }
    
    /**
     * 文本完全匹配
     */
    private fun matchTextEquals(
        rule: MatchRule,
        rootNode: AccessibilityNodeInfo?,
        pageTextProvider: () -> String
    ): Boolean {
        val values = rule.values ?: return false

        val results = values.map { value ->
            hasTextMatch(rule, rootNode, value, exact = true, pageTextProvider)
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

        val node = findNodeByClassName(rootNode, regexPattern)
        val matched = node != null
        node?.recycle()
        return matched
    }
    
    /**
     * viewId 匹配（支持通配符）
     */
    private fun matchViewId(rule: MatchRule, rootNode: AccessibilityNodeInfo?): Boolean {
        val pattern = rule.pattern ?: return false
        val regexPattern = pattern.replace("*", ".*")

        val node = findNodeByViewId(rootNode, regexPattern)
        val matched = node != null
        node?.recycle()
        return matched
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
     * 获取页面文本
     */
    private fun getPageText(rootNode: AccessibilityNodeInfo?): String {
        return NodeUtils.getAllNodeText(
            rootNode,
            maxDepth = PAGE_TEXT_MAX_DEPTH,
            maxNodes = PAGE_TEXT_MAX_NODES,
            maxTextLength = PAGE_TEXT_MAX_LENGTH
        )
    }
    
    /**
     * 按类名查找节点
     */
    private fun findNodeByClassName(rootNode: AccessibilityNodeInfo?, regexPattern: String): AccessibilityNodeInfo? {
        if (rootNode == null) return null
        
        val regex = Pattern.compile(regexPattern, Pattern.CASE_INSENSITIVE)

        return findFirstMatchingNode(rootNode) { node ->
            regex.matcher(node.className?.toString().orEmpty()).matches()
        }
    }
    
    /**
     * 按 viewId 查找节点
     */
    private fun findNodeByViewId(rootNode: AccessibilityNodeInfo?, regexPattern: String): AccessibilityNodeInfo? {
        if (rootNode == null) return null
        
        val regex = Pattern.compile(regexPattern, Pattern.CASE_INSENSITIVE)

        return findFirstMatchingNode(rootNode) { node ->
            regex.matcher(node.viewIdResourceName.orEmpty()).matches()
        }
    }

    private fun hasTextMatch(
        rule: MatchRule,
        rootNode: AccessibilityNodeInfo?,
        value: String,
        exact: Boolean,
        pageTextProvider: () -> String
    ): Boolean {
        if (value.isBlank()) {
            return false
        }

        if (rule.field.equals("page_text", ignoreCase = true)) {
            val pageText = normalizeText(pageTextProvider())
            return if (exact) {
                pageText.equals(value, ignoreCase = true)
            } else {
                pageText.contains(value, ignoreCase = true)
            }
        }

        if (rootNode == null) {
            return false
        }

        val matchedNodes = try {
            rootNode.findAccessibilityNodeInfosByText(value)
        } catch (e: Exception) {
            Log.w(TAG, "文本搜索失败：$value", e)
            emptyList()
        }

        return try {
            matchedNodes.any { node ->
                val combinedText = normalizeText(
                    "${node.text?.toString().orEmpty()} ${node.contentDescription?.toString().orEmpty()}"
                )

                if (exact) {
                    combinedText.equals(value, ignoreCase = true)
                } else {
                    combinedText.contains(value, ignoreCase = true)
                }
            }
        } finally {
            matchedNodes.forEach { recycleSafely(it) }
        }
    }

    private fun findFirstMatchingNode(
        rootNode: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        var visitedNodes = 0

        fun traverse(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (depth > NODE_SEARCH_MAX_DEPTH || visitedNodes >= NODE_SEARCH_MAX_NODES) {
                return null
            }

            visitedNodes++
            if (predicate(node)) {
                return AccessibilityNodeInfo.obtain(node)
            }

            for (i in 0 until node.childCount) {
                if (visitedNodes >= NODE_SEARCH_MAX_NODES) {
                    break
                }

                val child = node.getChild(i)
                if (child != null) {
                    try {
                        val result = traverse(child, depth + 1)
                        if (result != null) {
                            return result
                        }
                    } finally {
                        child.recycle()
                    }
                }
            }

            return null
        }

        return traverse(rootNode, 0)
    }

    private fun normalizeText(rawText: String): String {
        return rawText
            .replace("\u00A0", " ")
            .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun recycleSafely(node: AccessibilityNodeInfo?) {
        try {
            node?.recycle()
        } catch (_: Exception) {
        }
    }
    
    /**
     * 清除缓存
     */
    fun clearCache() {
        Log.d(TAG, "PageMatcher is stateless, no cache to clear")
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
