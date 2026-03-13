package com.example.a11yframework.rule.extractor

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.a11yframework.rule.ExtractRule
import com.example.a11yframework.rule.ExtractLocation
import com.example.a11yframework.rule.ExtractType
import com.example.a11yframework.utils.NodeUtils
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

/**
 * 数据提取器
 * 
 * 功能:
 * 1. 关键词提取器
 * 2. 正则提取器
 * 3. 列表提取器
 * 4. 提取器链
 * 
 * @param service 无障碍服务实例
 */
class DataExtractor(private val service: AccessibilityService) {
    
    companion object {
        private const val TAG = "DataExtractor"
        private const val MIN_CARD_WIDTH_PX = 600
        private const val MIN_CARD_HEIGHT_PX = 180
        private const val MAX_CARD_HEIGHT_PX = 900
        private const val ROOT_REGEX_TEXT_MAX_DEPTH = 12
        private const val ROOT_REGEX_TEXT_MAX_NODES = 260
        private const val ROOT_REGEX_TEXT_MAX_LENGTH = 5000
        private const val ITEM_TEXT_MAX_DEPTH = 10
        private const val ITEM_TEXT_MAX_NODES = 120
        private const val ITEM_TEXT_MAX_LENGTH = 2400
        private const val CONTAINER_SEARCH_MAX_DEPTH = 18
        private const val CONTAINER_SEARCH_MAX_NODES = 320
        private const val LIST_SCAN_MAX_DEPTH = 10
        private const val LIST_SCAN_MAX_NODES = 220
        private val LIST_ITEM_SIGNAL_PATTERN = Pattern.compile(
            "(现价\\s*\\d|原价\\s*\\d|随时退|过期退|预约|意向金|领券抢购|团购|套餐|体验|次卡|疗程)"
        )
        private val PRICE_SIGNAL_PATTERN = Pattern.compile(
            "(?:现价|原价)?\\s*\\d{2,5}(?:\\.\\d+)?\\s*元?"
        )
        private val TITLE_SIGNAL_PATTERN = Pattern.compile("[\\u4E00-\\u9FA5A-Za-z]{2,}")
    }
    
    /**
     * 提取数据
     * 
     * @param extractRules 提取规则
     * @param rootNode 根节点
     * @return 提取结果
     */
    fun extract(extractRules: Map<String, ExtractRule>, rootNode: AccessibilityNodeInfo?): ExtractResult {
        if (rootNode == null) {
            Log.w(TAG, "根节点为空，无法提取数据")
            return ExtractResult(emptyMap(), "根节点为空")
        }
        
        try {
            val extractedData = mutableMapOf<String, Any>()
            
            for ((fieldName, rule) in extractRules.entries) {
                try {
                    val value = extractField(rule, rootNode)
                    if (value != null) {
                        extractedData[fieldName] = value
                        Log.d(TAG, "提取成功：$fieldName = $value")
                    } else {
                        Log.d(TAG, "提取结果为空：$fieldName")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "提取字段失败：$fieldName", e)
                    extractedData["${fieldName}_error"] = e.message ?: "未知错误"
                }
            }
            
            return ExtractResult(extractedData, null)
            
        } catch (e: Exception) {
            Log.e(TAG, "数据提取异常：${e.message}", e)
            return ExtractResult(emptyMap(), "提取异常：${e.message}")
        }
    }
    
    /**
     * 提取单个字段
     */
    private fun extractField(rule: ExtractRule, rootNode: AccessibilityNodeInfo?): Any? {
        return when (rule.type) {
            ExtractType.FIND_BY_KEYWORDS -> extractByKeywords(rule, rootNode)
            ExtractType.REGEX -> extractByRegex(rule, rootNode)
            ExtractType.FIND_LIST -> extractList(rule, rootNode)
            ExtractType.XPATH -> {
                Log.w(TAG, "XPath 提取类型暂未实现")
                null
            }
        }
    }
    
    /**
     * 关键词提取
     */
    private fun extractByKeywords(rule: ExtractRule, rootNode: AccessibilityNodeInfo?): String? {
        val keywords = rule.keywords ?: return null
        
        // 根据位置策略查找
        return when (rule.location) {
            ExtractLocation.TOP -> findInTopArea(rootNode, keywords, rule.maxDepth)
            ExtractLocation.MIDDLE -> findInMiddleArea(rootNode, keywords, rule.maxDepth)
            ExtractLocation.BOTTOM -> findInBottomArea(rootNode, keywords, rule.maxDepth)
        }
    }
    
    /**
     * 在顶部区域查找
     */
    private fun findInTopArea(rootNode: AccessibilityNodeInfo?, keywords: List<String>, maxDepth: Int): String? {
        return collectKeywordMatches(rootNode, keywords, maxDepth).firstOrNull()
    }
    
    /**
     * 在中部区域查找
     */
    private fun findInMiddleArea(rootNode: AccessibilityNodeInfo?, keywords: List<String>, maxDepth: Int): String? {
        val matches = collectKeywordMatches(rootNode, keywords, maxDepth)
        if (matches.isEmpty()) {
            return null
        }

        return matches[matches.size / 2]
    }
    
    /**
     * 在底部区域查找
     */
    private fun findInBottomArea(rootNode: AccessibilityNodeInfo?, keywords: List<String>, maxDepth: Int): String? {
        return collectKeywordMatches(rootNode, keywords, maxDepth).lastOrNull()
    }
    
    /**
     * 查找所有匹配
     */
    private fun findAllMatches(rootNode: AccessibilityNodeInfo?, keywords: List<String>, maxDepth: Int): List<String> {
        return collectKeywordMatches(rootNode, keywords, maxDepth)
    }
    
    /**
     * 正则提取
     */
    private fun extractByRegex(rule: ExtractRule, rootNode: AccessibilityNodeInfo?): String? {
        val pattern = rule.pattern ?: return null
        val pageText = NodeUtils.getAllNodeText(
            rootNode,
            maxDepth = ROOT_REGEX_TEXT_MAX_DEPTH,
            maxNodes = ROOT_REGEX_TEXT_MAX_NODES,
            maxTextLength = ROOT_REGEX_TEXT_MAX_LENGTH
        )
        
        return try {
            val regex = Pattern.compile(pattern)
            val matcher = regex.matcher(pageText)
            
            if (matcher.find()) {
                if (rule.group > 0 && rule.group <= matcher.groupCount()) {
                    matcher.group(rule.group)
                } else {
                    matcher.group(0)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "正则表达式编译失败：$pattern", e)
            null
        }
    }
    
    /**
     * 列表提取
     */
    private fun extractList(rule: ExtractRule, rootNode: AccessibilityNodeInfo?): List<Map<String, Any>> {
        val container = rule.container ?: return emptyList()
        val itemRules = rule.itemRules ?: return emptyList()
        
        // 查找容器节点
        val containerNode = findContainerNode(rootNode, container)
        if (containerNode == null) {
            Log.w(TAG, "未找到列表容器")
            return emptyList()
        }
        
        val items = mutableListOf<Map<String, Any>>()
        val itemNodes = collectListItemNodes(containerNode)

        if (itemNodes.isEmpty()) {
            Log.d(TAG, "未识别到递归列表候选项，回退到容器直接子节点")
            for (i in 0 until containerNode.childCount) {
                containerNode.getChild(i)?.let { itemNode ->
                    try {
                        val itemData = extractItem(itemRules, itemNode)
                        if (itemData.isNotEmpty()) {
                            items.add(itemData)
                        }
                    } finally {
                        itemNode.recycle()
                    }
                }
            }
        } else {
            itemNodes.forEach { itemNode ->
                try {
                    val itemData = extractItem(itemRules, itemNode)
                    if (itemData.isNotEmpty()) {
                        items.add(itemData)
                    }
                } finally {
                    itemNode.recycle()
                }
            }
        }
        
        containerNode.recycle()
        
        Log.d(TAG, "提取列表项 ${items.size} 个")
        return items
    }
    
    /**
     * 查找容器节点
     */
    private fun findContainerNode(rootNode: AccessibilityNodeInfo?, container: com.example.a11yframework.rule.ContainerConfig): AccessibilityNodeInfo? {
        if (rootNode == null) return null

        val candidates = mutableListOf<AccessibilityNodeInfo>()

        fun matches(node: AccessibilityNodeInfo): Boolean {
            val targetClassName = container.className
            if (targetClassName != null) {
                val className = node.className?.toString() ?: ""
                if (!className.contains(targetClassName, ignoreCase = true)) {
                    return false
                }
            }

            val targetViewId = container.viewId
            if (targetViewId != null) {
                val viewId = node.viewIdResourceName ?: ""
                if (!viewId.contains(targetViewId, ignoreCase = true)) {
                    return false
                }
            }

            return container.className != null || container.viewId != null
        }

        findContainerByViewId(rootNode, container, ::matches)?.let { return it }

        var visitedNodes = 0

        fun traverse(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (depth > CONTAINER_SEARCH_MAX_DEPTH || visitedNodes >= CONTAINER_SEARCH_MAX_NODES) {
                return null
            }

            visitedNodes++
            if (matches(node)) {
                candidates.add(AccessibilityNodeInfo.obtain(node))
            }

            for (i in 0 until node.childCount) {
                if (visitedNodes >= CONTAINER_SEARCH_MAX_NODES) {
                    break
                }

                node.getChild(i)?.let { child ->
                    traverse(child, depth + 1)
                    child.recycle()
                }
            }

            return null
        }
        
        traverse(rootNode, 0)

        val selected = candidates.maxByOrNull { candidate ->
            scoreContainerCandidate(candidate, container)
        }

        candidates.forEach { candidate ->
            if (candidate !== selected) {
                candidate.recycle()
            }
        }

        return selected
    }

    private fun collectListItemNodes(containerNode: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val containerBounds = Rect()
        containerNode.getBoundsInScreen(containerBounds)

        val candidates = mutableListOf<ListItemCandidate>()
        val seenKeys = mutableSetOf<String>()
        var visitedNodes = 0

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > LIST_SCAN_MAX_DEPTH || visitedNodes >= LIST_SCAN_MAX_NODES) {
                return
            }

            visitedNodes++
            if (isLikelyListItemNode(node, containerBounds)) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val normalizedText = getAllNodeText(node)
                val key = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}:${normalizedText.hashCode()}"

                if (seenKeys.add(key)) {
                    candidates.add(
                        ListItemCandidate(
                            node = AccessibilityNodeInfo.obtain(node),
                            bounds = bounds,
                            depth = depth
                        )
                    )
                }
            }

            for (i in 0 until node.childCount) {
                if (visitedNodes >= LIST_SCAN_MAX_NODES) {
                    break
                }

                node.getChild(i)?.let { child ->
                    traverse(child, depth + 1)
                    child.recycle()
                }
            }
        }

        for (i in 0 until containerNode.childCount) {
            containerNode.getChild(i)?.let { child ->
                traverse(child, 1)
                child.recycle()
            }
        }

        val deduplicated = deduplicateCandidates(candidates)
        Log.d(TAG, "识别列表候选项 ${deduplicated.size} 个")
        return deduplicated.map { it.node }
    }

    private fun isLikelyListItemNode(
        node: AccessibilityNodeInfo,
        containerBounds: Rect
    ): Boolean {
        if (node.isScrollable) {
            return false
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val minWidth = max(containerBounds.width() / 2, MIN_CARD_WIDTH_PX)
        val maxHeight = min(containerBounds.height(), MAX_CARD_HEIGHT_PX)
        if (bounds.width() < minWidth) {
            return false
        }
        if (bounds.height() < MIN_CARD_HEIGHT_PX || bounds.height() > maxHeight) {
            return false
        }

        val text = getAllNodeText(node)
        if (text.length < 12) {
            return false
        }

        return LIST_ITEM_SIGNAL_PATTERN.matcher(text).find() &&
            PRICE_SIGNAL_PATTERN.matcher(text).find() &&
            TITLE_SIGNAL_PATTERN.matcher(text).find()
    }

    private fun deduplicateCandidates(
        candidates: List<ListItemCandidate>
    ): List<ListItemCandidate> {
        val accepted = mutableListOf<ListItemCandidate>()

        val sorted = candidates.sortedWith(
            compareBy<ListItemCandidate>(
                { it.bounds.width() * it.bounds.height() },
                { -it.depth },
                { it.bounds.top },
                { it.bounds.left }
            )
        )

        sorted.forEach { candidate ->
            val duplicate = accepted.any { existing ->
                isSubstantiallyOverlapping(existing.bounds, candidate.bounds)
            }

            if (duplicate) {
                candidate.node.recycle()
            } else {
                accepted.add(candidate)
            }
        }

        return accepted.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    private fun isSubstantiallyOverlapping(first: Rect, second: Rect): Boolean {
        val left = max(first.left, second.left)
        val top = max(first.top, second.top)
        val right = min(first.right, second.right)
        val bottom = min(first.bottom, second.bottom)

        if (left >= right || top >= bottom) {
            return false
        }

        val intersectionArea = (right - left) * (bottom - top)
        val smallerArea = min(first.width() * first.height(), second.width() * second.height())
        return intersectionArea >= smallerArea * 0.85
    }
    
    /**
     * 提取单个列表项
     */
    private fun extractItem(itemRules: Map<String, ExtractRule>, itemNode: AccessibilityNodeInfo): Map<String, Any> {
        val itemData = mutableMapOf<String, Any>()
        
        for ((fieldName, rule) in itemRules.entries) {
            try {
                val value = extractField(rule, itemNode)
                if (value != null) {
                    itemData[fieldName] = value
                }
            } catch (e: Exception) {
                Log.e(TAG, "提取列表项字段失败：$fieldName", e)
            }
        }
        
        return itemData
    }
    
    /**
     * 获取所有节点文本
     */
    private fun getAllNodeText(rootNode: AccessibilityNodeInfo?): String {
        return normalizeExtractText(
            NodeUtils.getAllNodeText(
                rootNode,
                maxDepth = ITEM_TEXT_MAX_DEPTH,
                maxNodes = ITEM_TEXT_MAX_NODES,
                maxTextLength = ITEM_TEXT_MAX_LENGTH
            )
        )
    }

    private fun collectKeywordMatches(
        rootNode: AccessibilityNodeInfo?,
        keywords: List<String>,
        maxDepth: Int
    ): List<String> {
        if (rootNode == null) {
            return emptyList()
        }

        val matches = mutableListOf<KeywordMatch>()
        val seenMatches = mutableSetOf<String>()

        keywords.distinct().forEach keywordLoop@{ keyword ->
            val nodes = try {
                rootNode.findAccessibilityNodeInfosByText(keyword)
            } catch (e: Exception) {
                Log.w(TAG, "关键词搜索失败：$keyword", e)
                emptyList()
            }

            try {
                nodes.forEach nodeLoop@{ node ->
                    val combinedText = normalizeExtractText(
                        "${node.text?.toString().orEmpty()} ${node.contentDescription?.toString().orEmpty()}"
                    )
                    if (combinedText.isBlank() || !combinedText.contains(keyword, ignoreCase = true)) {
                        return@nodeLoop
                    }

                    val depth = estimateDepth(node, maxDepth + 4)
                    if (depth > maxDepth) {
                        return@nodeLoop
                    }

                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    val matchKey = "${bounds.top}:${bounds.left}:$combinedText"

                    if (seenMatches.add(matchKey)) {
                        matches.add(
                            KeywordMatch(
                                text = combinedText,
                                top = bounds.top,
                                left = bounds.left,
                                depth = depth
                            )
                        )
                    }
                }
            } finally {
                nodes.forEach { recycleSafely(it) }
            }
        }

        return matches
            .sortedWith(compareBy<KeywordMatch>({ it.top }, { it.left }, { it.depth }))
            .map { it.text }
    }

    private fun estimateDepth(node: AccessibilityNodeInfo, limit: Int): Int {
        var depth = 0
        var parent = node.parent

        while (parent != null && depth <= limit) {
            depth++
            val grandParent = parent.parent
            parent.recycle()
            parent = grandParent
        }

        return depth
    }

    private fun findContainerByViewId(
        rootNode: AccessibilityNodeInfo,
        container: com.example.a11yframework.rule.ContainerConfig,
        matcher: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val viewId = container.viewId ?: return null
        if (viewId.contains("*") || viewId.contains(".*")) {
            return null
        }

        val nodes = try {
            rootNode.findAccessibilityNodeInfosByViewId(viewId)
        } catch (e: Exception) {
            Log.w(TAG, "通过 viewId 查找容器失败：$viewId", e)
            emptyList()
        }

        var selected: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE

        nodes.forEach candidateLoop@{ candidate ->
            try {
                if (!matcher(candidate)) {
                    return@candidateLoop
                }

                val score = scoreContainerCandidate(candidate, container)
                if (score > bestScore) {
                    selected?.recycle()
                    selected = AccessibilityNodeInfo.obtain(candidate)
                    bestScore = score
                }
            } finally {
                recycleSafely(candidate)
            }
        }

        return selected
    }

    private fun scoreContainerCandidate(
        candidate: AccessibilityNodeInfo,
        container: com.example.a11yframework.rule.ContainerConfig
    ): Int {
        val viewId = candidate.viewIdResourceName ?: ""
        val targetViewId = container.viewId
        return candidate.childCount +
            if (candidate.isScrollable) 5 else 0 +
            if (targetViewId != null && viewId.contains(targetViewId, ignoreCase = true)) 20 else 0
    }

    private fun recycleSafely(node: AccessibilityNodeInfo?) {
        try {
            node?.recycle()
        } catch (_: Exception) {
        }
    }

    private fun normalizeExtractText(rawText: String): String {
        return rawText
            .replace("\u00A0", " ")
            .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

private data class ListItemCandidate(
    val node: AccessibilityNodeInfo,
    val bounds: Rect,
    val depth: Int
)

private data class KeywordMatch(
    val text: String,
    val top: Int,
    val left: Int,
    val depth: Int
)

/**
 * 提取结果
 * 
 * @property data 提取的数据
 * @property errorMessage 错误消息
 */
data class ExtractResult(
    val data: Map<String, Any>,
    val errorMessage: String?
)
