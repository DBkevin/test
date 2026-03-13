package com.example.a11yframework.search

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.a11yframework.utils.NodeUtils
import kotlin.math.abs

/**
 * 搜索控制器
 * 
 * 功能:
 * 1. 定位搜索框
 * 2. 清空搜索框
 * 3. 输入搜索内容
 * 4. 点击搜索按钮
 */
class SearchController(
    private val service: AccessibilityService
) {
    
    companion object {
        private const val TAG = "SearchController"
        private const val SEARCH_NODE_MAX_DEPTH = 18
        private const val SEARCH_PREPARE_MAX_ATTEMPTS = 6
        private const val SEARCH_RETRY_DELAY_MS = 700L
        private const val MERCHANT_RESULT_OPEN_TIMEOUT_MS = 1800L
        
        // 搜索框特征
        private val SEARCH_KEYWORDS = listOf("搜索", "搜索框", "search", "放大镜")
        private val SEARCH_BUTTON_KEYWORDS = listOf("搜索", "查找", "search", "🔍")
        private val DOUYIN_GROUPBUY_PAGE_KEYWORDS = listOf("附近好店", "美食", "休闲娱乐", "景点/周边游", "酒店民宿", "丽人")
        private val SCROLLABLE_CLASS_KEYWORDS = listOf("RecyclerView", "ListView", "ScrollView", "NestedScrollView", "WebView")
        private val MERCHANT_RESULT_HINTS = listOf("医院", "门诊", "医疗美容", "美容医院", "诊所", "机构")
        private val MERCHANT_RESULT_CONTEXT_HINTS = listOf("评价", "回头客", "km", "m", "/人", "人均", "价格优惠")
        private val MERCHANT_DETAIL_PAGE_HINTS = listOf("收藏", "关注", "在线咨询", "预约有礼", "领券抢购")
        
        // 抖音搜索页面特征
        private const val DOUYIN_SEARCH_ACTIVITY = "com.ss.android.ugc.aweme.search.activity.SearchResultActivity"
        
        // 美团搜索页面特征
        private const val MEITUAN_SEARCH_ACTIVITY = "com.sankuai.meituan.search.activity.SearchActivity"
    }
    
    /**
     * 执行搜索
     * 
     * @param keyword 搜索关键词（医院名称）
     * @return 是否成功
     */
    fun search(
        keyword: String,
        entryKeywords: List<String> = SEARCH_KEYWORDS,
        buttonKeywords: List<String> = SEARCH_BUTTON_KEYWORDS
    ): Boolean {
        val resolvedEntryKeywords = entryKeywords.ifEmpty { SEARCH_KEYWORDS }
        val resolvedButtonKeywords = buttonKeywords.ifEmpty { SEARCH_BUTTON_KEYWORDS }
        Log.i(TAG, "Searching for: $keyword")
        var searchBox: AccessibilityNodeInfo? = null

        return try {
            // 1. 定位搜索框
            searchBox = prepareSearchBox(resolvedEntryKeywords)
            if (searchBox == null) {
                Log.e(TAG, "Search box not found")
                return false
            }
            
            // 2. 清空搜索框
            clearSearchBox(searchBox)
            
            // 3. 输入搜索内容
            inputText(searchBox, keyword)

            // 4. 点击搜索按钮
            val submitted = clickSearchButton(searchBox, resolvedButtonKeywords)
            
            Log.i(TAG, "Search executed: $keyword, submitted=$submitted")
            submitted
            
        } catch (e: Exception) {
            Log.e(TAG, "Search error", e)
            false
        } finally {
            searchBox?.recycle()
        }
    }

    fun searchDouyinGroupBuy(keyword: String): Boolean {
        val tabSelected = selectDouyinGroupBuyTab()
        if (tabSelected) {
            Thread.sleep(1200)
        } else {
            Log.w(TAG, "Douyin group buy tab not explicitly selected, fallback to direct search")
        }

        return search(keyword)
    }

    fun openMerchantResult(merchantName: String, maxScrollRounds: Int = 3): Boolean {
        repeat(maxScrollRounds + 1) { round ->
            val merchantNode = findMerchantResultNode(merchantName)
            if (merchantNode != null) {
                try {
                    val opened = openMerchantCandidate(merchantNode, merchantName, round)
                    if (opened) {
                        return true
                    }
                } finally {
                    merchantNode.recycle()
                }
            }

            if (round == maxScrollRounds) {
                return false
            }

            if (!scrollCurrentPage()) {
                return false
            }
            Thread.sleep(1500)
        }

        return false
    }

    fun scrollCurrentPage(forward: Boolean = true): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false

        try {
            val scrollableNode = NodeUtils.findNodeByCondition(
                rootNode,
                condition = { node: AccessibilityNodeInfo ->
                    val className = node.className?.toString().orEmpty()
                    node.isScrollable || SCROLLABLE_CLASS_KEYWORDS.any { keyword ->
                        className.contains(keyword, ignoreCase = true)
                    }
                },
                maxDepth = 12
            )

            if (scrollableNode != null) {
                val scrollCopy = AccessibilityNodeInfo.obtain(scrollableNode)
                try {
                    val scrolled = NodeUtils.scrollNode(scrollCopy, forward)
                    if (scrolled) {
                        Log.d(TAG, "Scrolled page with accessibility action")
                        return true
                    }
                } finally {
                    scrollCopy.recycle()
                }
            }
        } finally {
            rootNode.recycle()
        }

        val swiped = swipePage(forward)
        Log.d(TAG, "Scrolled page with gesture: $swiped")
        return swiped
    }

    fun clickText(
        targetText: String,
        exactMatch: Boolean = false,
        maxScrollRounds: Int = 0,
        fallbackTapX: Int? = null,
        fallbackTapY: Int? = null
    ): Boolean {
        repeat(maxScrollRounds + 1) { round ->
            val matchedNode = findTextNode(targetText, exactMatch)
            if (matchedNode != null) {
                try {
                    val clicked = NodeUtils.clickNode(matchedNode)
                    Log.i(
                        TAG,
                        "Click text: target=$targetText, round=$round, exact=$exactMatch, clicked=$clicked"
                    )
                    if (clicked) {
                        return true
                    }
                } finally {
                    matchedNode.recycle()
                }
            }

            if (round == maxScrollRounds || !scrollCurrentPage()) {
                val fallbackTapped = fallbackTapX != null &&
                    fallbackTapY != null &&
                    tapScreen(fallbackTapX, fallbackTapY)
                if (fallbackTapped) {
                    Log.i(
                        TAG,
                        "Click text fallback tap: target=$targetText, x=$fallbackTapX, y=$fallbackTapY"
                    )
                    return true
                }
                return false
            }
            Thread.sleep(1200)
        }

        return false
    }

    fun clickTextFuzzy(targetText: String, maxScrollRounds: Int = 0): Boolean {
        return openMerchantResult(targetText, maxScrollRounds)
    }

    fun clickAnyText(
        targetTexts: List<String>,
        exactMatch: Boolean = false,
        maxClicks: Int = targetTexts.size
    ): Int {
        val normalizedTargets = targetTexts
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        if (normalizedTargets.isEmpty() || maxClicks <= 0) {
            return 0
        }

        val clickedKeys = mutableSetOf<String>()
        var clickCount = 0

        while (clickCount < maxClicks) {
            var clickedInThisPass = false

            for (targetText in normalizedTargets) {
                val matchedNode = findTextNode(targetText, exactMatch) ?: continue
                try {
                    val bounds = Rect()
                    matchedNode.getBoundsInScreen(bounds)
                    val clickKey = "$targetText:${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
                    if (!clickedKeys.add(clickKey)) {
                        continue
                    }

                    val clicked = NodeUtils.clickNode(matchedNode)
                    Log.i(
                        TAG,
                        "Click any text: target=$targetText, exact=$exactMatch, clicked=$clicked"
                    )
                    if (clicked) {
                        clickCount++
                        clickedInThisPass = true
                        Thread.sleep(600)
                        break
                    }
                } finally {
                    matchedNode.recycle()
                }
            }

            if (!clickedInThisPass) {
                break
            }
        }

        return clickCount
    }

    fun clickViewId(viewId: String, maxScrollRounds: Int = 0): Boolean {
        repeat(maxScrollRounds + 1) { round ->
            val matchedNode = findNodeByViewId(viewId)
            if (matchedNode != null) {
                try {
                    val clicked = NodeUtils.clickNode(matchedNode)
                    Log.i(TAG, "Click viewId: id=$viewId, round=$round, clicked=$clicked")
                    if (clicked) {
                        return true
                    }
                } finally {
                    matchedNode.recycle()
                }
            }

            if (round == maxScrollRounds || !scrollCurrentPage()) {
                return false
            }
            Thread.sleep(1200)
        }

        return false
    }

    fun waitForAnyText(targetTexts: List<String>, timeoutMs: Long): Boolean {
        if (targetTexts.isEmpty()) {
            return false
        }

        val startAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startAt < timeoutMs) {
            val rootNode = service.rootInActiveWindow
            if (rootNode != null) {
                try {
                    val pageText = NodeUtils.getAllNodeText(rootNode)
                    val matched = targetTexts.any { target ->
                        pageText.contains(target, ignoreCase = true)
                    }
                    if (matched) {
                        return true
                    }
                } finally {
                    rootNode.recycle()
                }
            }

            Thread.sleep(400)
        }

        return false
    }

    private fun prepareSearchBox(entryKeywords: List<String> = SEARCH_KEYWORDS): AccessibilityNodeInfo? {
        repeat(SEARCH_PREPARE_MAX_ATTEMPTS) { attempt ->
            findSearchBox(entryKeywords)?.let { return it }

            val opened = openSearchEntry(entryKeywords)
            if (opened) {
                Log.d(TAG, "Opened search entry on attempt=${attempt + 1}")
            }

            Thread.sleep(SEARCH_RETRY_DELAY_MS)
        }

        return findSearchBox(entryKeywords)
    }
    
    /**
     * 定位搜索框
     */
    private fun findSearchBox(searchKeywords: List<String> = SEARCH_KEYWORDS): AccessibilityNodeInfo? {
        val rootNode = service.rootInActiveWindow ?: return null
        
        try {
            // 方法 1: 通过更稳定的 viewId 查找真实输入框
            val byId = NodeUtils.findNodeByCondition(
                rootNode,
                condition = { node: AccessibilityNodeInfo ->
                    val viewId = node.viewIdResourceName?.lowercase() ?: return@findNodeByCondition false
                    (viewId.contains("et_search") ||
                        viewId.contains("search_kw") ||
                        viewId.contains("search_input") ||
                        viewId.contains("search_edit")) &&
                        isLikelySearchInput(node)
                },
                maxDepth = SEARCH_NODE_MAX_DEPTH
            )

            if (byId != null) {
                Log.d(TAG, "Found search box by id")
                return AccessibilityNodeInfo.obtain(byId)
            }

            // 方法 2: 通过 className / editability 查找真实输入框
            val byClass = NodeUtils.findNodeByCondition(rootNode, condition = { node: AccessibilityNodeInfo ->
                isLikelySearchInput(node)
            }, maxDepth = SEARCH_NODE_MAX_DEPTH)
            
            if (byClass != null) {
                Log.d(TAG, "Found search box by class")
                return AccessibilityNodeInfo.obtain(byClass)
            }
            
            // 方法 3: 通过关键词查找可输入的搜索框，避免把顶部搜索按钮误当成输入框
            val byKeyword = NodeUtils.findNodeByCondition(
                rootNode,
                condition = { node: AccessibilityNodeInfo ->
                    if (!isLikelySearchInput(node)) {
                        return@findNodeByCondition false
                    }

                    val text = getComparableNodeText(node).lowercase()
                    searchKeywords.any { keyword -> text.contains(keyword.lowercase()) }
                },
                maxDepth = SEARCH_NODE_MAX_DEPTH
            )

            if (byKeyword != null) {
                Log.d(TAG, "Found search box by keyword")
                return AccessibilityNodeInfo.obtain(byKeyword)
            }
            
            return null
            
        } finally {
            rootNode.recycle()
        }
    }
    
    /**
     * 清空搜索框
     */
    private fun clearSearchBox(searchBox: AccessibilityNodeInfo) {
        try {
            // 方法 1: 使用 setText（如果支持）
            if (supportsSetText(searchBox)) {
                val arguments = android.os.Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                searchBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                Log.d(TAG, "Cleared search box with setText")
                return
            }
            
            // 方法 2: 长按全选后删除
            val rect = Rect()
            searchBox.getBoundsInScreen(rect)
            
            // 长按搜索框
            longClick(rect.centerX(), rect.centerY())
            Thread.sleep(500)
            
            // 点击"全选"
            clickSelectAll()
            Thread.sleep(300)
            
            // 点击"删除"
            clickDelete()
            
            Log.d(TAG, "Cleared search box with gestures")
            
        } catch (e: Exception) {
            Log.e(TAG, "Clear search box error", e)
        }
    }
    
    /**
     * 输入文本
     */
    private fun inputText(searchBox: AccessibilityNodeInfo, text: String) {
        try {
            // 方法 1: 使用 setText（如果支持）
            if (supportsSetText(searchBox)) {
                val arguments = android.os.Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                searchBox.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                searchBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                Log.d(TAG, "Input text with setText: $text")
                return
            }
            
            // 方法 2: 点击搜索框后使用输入法
            val rect = Rect()
            searchBox.getBoundsInScreen(rect)
            
            // 点击搜索框
            click(rect.centerX(), rect.centerY())
            Thread.sleep(500)
            
            // 使用系统输入法输入（需要 ADB Keyboard 或类似工具）
            // 这里暂时使用模拟按键，实际使用可能需要配合输入法
            simulateTextInput(text)
            
            Log.d(TAG, "Input text with gestures: $text")
            
        } catch (e: Exception) {
            Log.e(TAG, "Input text error", e)
        }
    }
    
    /**
     * 点击搜索按钮
     */
    private fun clickSearchButton(
        searchBox: AccessibilityNodeInfo? = null,
        buttonKeywords: List<String> = SEARCH_BUTTON_KEYWORDS
    ): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false
        
        try {
            // 方法 1: 通过关键词查找搜索按钮，允许点击父节点
            val button = NodeUtils.findNodeByCondition(
                rootNode,
                condition = { node: AccessibilityNodeInfo ->
                    val text = getComparableNodeText(node).lowercase()
                    text.isNotBlank() && buttonKeywords.any { keyword ->
                        text.contains(keyword.lowercase())
                    }
                },
                maxDepth = SEARCH_NODE_MAX_DEPTH
            )
            
            if (button != null) {
                val clicked = NodeUtils.clickNode(button)
                Log.d(TAG, "Clicked search button by keyword: $clicked")
                if (clicked) {
                    return true
                }

                val tapped = tapNodeCenter(button)
                if (tapped) {
                    Log.d(TAG, "Tapped search button by keyword bounds")
                    return true
                }
            }
            
            // 方法 2: 通过 viewId 查找按钮型搜索控件
            val buttonById = NodeUtils.findNodeByCondition(
                rootNode,
                condition = { node: AccessibilityNodeInfo ->
                    if (isLikelySearchInput(node)) {
                        return@findNodeByCondition false
                    }

                    val viewId = node.viewIdResourceName?.lowercase() ?: return@findNodeByCondition false
                    (viewId.contains("btn") ||
                        viewId.contains("button") ||
                        viewId.contains("submit")) &&
                        viewId.contains("search")
                },
                maxDepth = SEARCH_NODE_MAX_DEPTH
            )
            
            if (buttonById != null) {
                val clicked = NodeUtils.clickNode(buttonById)
                Log.d(TAG, "Clicked search button by id: $clicked")
                if (clicked) {
                    return true
                }

                val tapped = tapNodeCenter(buttonById)
                if (tapped) {
                    Log.d(TAG, "Tapped search button by id bounds")
                    return true
                }
            }
            
            // 方法 3: 使用输入法搜索动作
            if (searchBox != null) {
                val imeResult = searchBox.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                )
                if (imeResult) {
                    Log.d(TAG, "Submitted search with IME action")
                    return true
                }
            }

            return false
            
        } finally {
            rootNode.recycle()
        }
    }

    private fun openSearchEntry(entryKeywords: List<String> = SEARCH_KEYWORDS): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false

        try {
            val entryNode = NodeUtils.findNodeByCondition(
                rootNode,
                condition = { node: AccessibilityNodeInfo ->
                val nodeText = getComparableNodeText(node).lowercase()
                val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
                val viewId = node.viewIdResourceName?.lowercase() ?: ""

                entryKeywords.any { keyword ->
                    val lowerKeyword = keyword.lowercase()
                    nodeText.contains(lowerKeyword) ||
                        contentDesc.contains(lowerKeyword) ||
                        viewId.contains(lowerKeyword)
                }
            },
                maxDepth = SEARCH_NODE_MAX_DEPTH
            )

            if (entryNode != null) {
                val clicked = NodeUtils.clickNode(entryNode)
                Log.d(TAG, "Opened search entry: $clicked")
                return clicked
            }

            return false
        } finally {
            rootNode.recycle()
        }
    }

    private fun selectDouyinGroupBuyTab(): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false

        try {
            if (isLikelyDouyinGroupBuyPage(rootNode)) {
                return true
            }

            val tabNode = NodeUtils.findNodeByCondition(
                rootNode,
                condition = { node: AccessibilityNodeInfo ->
                    val nodeText = getComparableNodeText(node)
                    nodeText.contains("团购", ignoreCase = true)
                },
                maxDepth = 10
            )

            if (tabNode != null) {
                val clicked = NodeUtils.clickNode(tabNode)
                Log.d(TAG, "Selected Douyin group buy tab: $clicked")
                return clicked
            }

            return false
        } finally {
            rootNode.recycle()
        }
    }

    private fun isLikelyDouyinGroupBuyPage(rootNode: AccessibilityNodeInfo): Boolean {
        val pageText = NodeUtils.getAllNodeText(rootNode)
        val hitCount = DOUYIN_GROUPBUY_PAGE_KEYWORDS.count { keyword ->
            pageText.contains(keyword, ignoreCase = true)
        }
        return hitCount >= 2
    }

    private fun findMerchantResultNode(merchantName: String): AccessibilityNodeInfo? {
        val rootNode = service.rootInActiveWindow ?: return null

        try {
            val normalizedTarget = normalizeText(merchantName)
            if (normalizedTarget.isBlank()) {
                return null
            }

            val candidates = NodeUtils.findNodesByCondition(
                rootNode,
                condition = { node: AccessibilityNodeInfo ->
                    if (node.isFocused || isLikelySearchInput(node)) {
                        return@findNodesByCondition false
                    }

                    val viewId = node.viewIdResourceName?.lowercase().orEmpty()
                    if (viewId.contains("et_search") || viewId.contains("search_kw")) {
                        return@findNodesByCondition false
                    }

                    val contentDesc = node.contentDescription?.toString().orEmpty()
                    if (contentDesc.contains("填入搜索框")) {
                        return@findNodesByCondition false
                    }

                    val text = normalizeText(getComparableNodeText(node))
                    text.isNotBlank() && (
                        text.contains(normalizedTarget) || normalizedTarget.contains(text)
                    )
                },
                maxDepth = 20
            )

            val scoredCandidates = candidates
                .map { candidate ->
                    val contextText = buildMerchantCandidateContext(candidate)
                    val score = scoreMerchantCandidate(candidate, normalizedTarget, contextText)
                    MerchantCandidateScore(
                        node = candidate,
                        score = score,
                        comparableText = getComparableNodeText(candidate),
                        context = contextText
                    )
                }
            logTopMerchantCandidates(scoredCandidates)

            val bestCandidate = scoredCandidates
                .filter { it.score > 0 }
                .maxByOrNull { it.score }

            val bestNode = bestCandidate?.node
            val result = bestNode?.let { AccessibilityNodeInfo.obtain(it) }

            candidates.forEach { candidate ->
                if (candidate !== bestNode) {
                    candidate.recycle()
                }
            }
            bestNode?.recycle()

            return result
        } finally {
            rootNode.recycle()
        }
    }

    private fun scoreMerchantCandidate(
        node: AccessibilityNodeInfo,
        normalizedTarget: String,
        contextText: String
    ): Int {
        val rawText = getComparableNodeText(node)
        val comparableText = normalizeText(rawText)
        if (comparableText.isBlank()) {
            return 0
        }
        if (!comparableText.contains(normalizedTarget) && !normalizedTarget.contains(comparableText)) {
            return 0
        }

        val hasMerchantHint = MERCHANT_RESULT_HINTS.any { hint ->
            rawText.contains(hint, ignoreCase = true)
        }
        val hasStructuredContext = MERCHANT_RESULT_CONTEXT_HINTS.any { hint ->
            contextText.contains(hint, ignoreCase = true)
        }
        val hasReviewContext = contextText.contains("评价")
        val hasRepeatCustomerContext = contextText.contains("回头客")
        val hasDistanceContext = Regex("\\d+(\\.\\d+)?\\s*(km|m)", RegexOption.IGNORE_CASE)
            .containsMatchIn(contextText)
        val hasPricePerPersonContext = contextText.contains("/人") || contextText.contains("人均")
        val hasReviewCountContext = Regex("\\d+条评价").containsMatchIn(contextText)
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val isInResultListArea = bounds.top >= 500

        var score = 0
        if (comparableText == normalizedTarget) {
            score += 30
        }
        if (comparableText.contains(normalizedTarget)) {
            score += 80
        }
        if (comparableText.length > normalizedTarget.length) {
            score += minOf(40, comparableText.length - normalizedTarget.length)
        }
        if (hasMerchantHint) {
            score += 120
        } else if (comparableText == normalizedTarget) {
            score -= 80
        }
        if (hasStructuredContext) {
            score += 70
        }
        if (hasReviewContext) {
            score += 45
        }
        if (hasReviewCountContext) {
            score += 35
        }
        if (hasRepeatCustomerContext) {
            score += 60
        }
        if (hasDistanceContext) {
            score += 25
        }
        if (hasPricePerPersonContext) {
            score += 35
        }
        if (isInResultListArea) {
            score += 80
        } else {
            score -= 140
        }
        if (node.isClickable) {
            score += 10
        }
        if (node.className?.toString()?.contains("TextView", ignoreCase = true) == true) {
            score += 10
        }

        score -= abs(comparableText.length - normalizedTarget.length)
        return score
    }

    private fun buildMerchantCandidateContext(node: AccessibilityNodeInfo): String {
        val snippets = mutableListOf<String>()
        val parent = node.parent

        if (parent != null) {
            try {
                for (index in 0 until parent.childCount) {
                    val child = parent.getChild(index) ?: continue
                    try {
                        val text = NodeUtils.getAllNodeText(
                            child,
                            maxDepth = 2,
                            maxNodes = 20,
                            maxTextLength = 240
                        )
                        if (text.isNotBlank()) {
                            snippets.add(text)
                        }
                    } finally {
                        child.recycle()
                    }
                }
            } finally {
                parent.recycle()
            }
        }

        if (snippets.isEmpty()) {
            snippets.add(getComparableNodeText(node))
        }

        return snippets.joinToString(" ")
    }

    private fun openMerchantCandidate(
        merchantNode: AccessibilityNodeInfo,
        merchantName: String,
        round: Int
    ): Boolean {
        val clicked = NodeUtils.clickNode(merchantNode)
        if (clicked && waitForMerchantDetailPage(merchantName, MERCHANT_RESULT_OPEN_TIMEOUT_MS)) {
            Log.i(TAG, "Merchant result opened by accessibility click: name=$merchantName, round=$round")
            return true
        }

        val tappedCenter = tapNodeCenter(merchantNode)
        if (tappedCenter && waitForMerchantDetailPage(merchantName, MERCHANT_RESULT_OPEN_TIMEOUT_MS)) {
            Log.i(TAG, "Merchant result opened by center tap: name=$merchantName, round=$round")
            return true
        }

        val tappedBelow = tapNodeCenterWithOffset(merchantNode, offsetY = 110)
        if (tappedBelow && waitForMerchantDetailPage(merchantName, MERCHANT_RESULT_OPEN_TIMEOUT_MS)) {
            Log.i(TAG, "Merchant result opened by offset tap: name=$merchantName, round=$round")
            return true
        }

        Log.w(
            TAG,
            "Merchant result did not open detail page: name=$merchantName, round=$round, clicked=$clicked, tappedCenter=$tappedCenter, tappedBelow=$tappedBelow"
        )
        return false
    }

    private fun waitForMerchantDetailPage(merchantName: String, timeoutMs: Long): Boolean {
        val normalizedTarget = normalizeText(merchantName)
        val startAt = System.currentTimeMillis()

        while (System.currentTimeMillis() - startAt < timeoutMs) {
            val rootNode = service.rootInActiveWindow
            if (rootNode != null) {
                try {
                    if (isLikelyMerchantDetailPage(rootNode, normalizedTarget)) {
                        return true
                    }
                } finally {
                    rootNode.recycle()
                }
            }
            Thread.sleep(250)
        }

        return false
    }

    private fun isLikelyMerchantDetailPage(
        rootNode: AccessibilityNodeInfo,
        normalizedTarget: String
    ): Boolean {
        val pageText = NodeUtils.getAllNodeText(
            rootNode,
            maxDepth = 14,
            maxNodes = 220,
            maxTextLength = 3000
        )
        val normalizedPageText = normalizeText(pageText)
        val hasMerchantName = normalizedTarget.isBlank() || normalizedPageText.contains(normalizedTarget)
        val hasDetailSignal = MERCHANT_DETAIL_PAGE_HINTS.any { hint ->
            pageText.contains(hint, ignoreCase = true)
        }
        return hasMerchantName && hasDetailSignal
    }

    private fun tapNodeCenterWithOffset(node: AccessibilityNodeInfo, offsetY: Int): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) {
            return false
        }

        val metrics = service.resources.displayMetrics
        val x = bounds.centerX().coerceIn(0, metrics.widthPixels - 1)
        val y = (bounds.centerY() + offsetY).coerceIn(0, metrics.heightPixels - 1)
        return tapScreen(x, y)
    }

    private fun logTopMerchantCandidates(candidates: List<MerchantCandidateScore>) {
        val topCandidates = candidates
            .sortedByDescending { it.score }
            .take(3)

        if (topCandidates.isEmpty()) {
            return
        }

        val summary = topCandidates.joinToString(" | ") { candidate ->
            val normalizedText = normalizeText(candidate.comparableText)
            "score=${candidate.score}, text=${normalizedText.take(24)}, context=${candidate.context.take(48)}"
        }
        Log.i(TAG, "Merchant candidates: $summary")
    }

    private fun getComparableNodeText(node: AccessibilityNodeInfo): String {
        val text = NodeUtils.getNodeText(node)
        if (text.isNotBlank()) {
            return text
        }
        return node.contentDescription?.toString().orEmpty()
    }

    private fun normalizeText(text: String): String {
        return text.lowercase()
            .replace("[\\s·•|｜/\\\\-]+".toRegex(), "")
            .replace("[^\\p{L}\\p{N}]".toRegex(), "")
    }

    private fun supportsSetText(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) {
            return true
        }

        return node.actionList.any { action ->
            action.id == AccessibilityNodeInfo.ACTION_SET_TEXT
        }
    }

    private fun isLikelySearchInput(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        return node.isEditable ||
            className.contains("EditText", ignoreCase = true) ||
            className.contains("AutoCompleteTextView", ignoreCase = true) ||
            supportsSetText(node) ||
            viewId.contains("search_input") ||
            viewId.contains("search_edit") ||
            viewId.contains("search_kw")
    }
    
    /**
     * 模拟文本输入（使用输入法）
     */
    private fun simulateTextInput(text: String) {
        // 这个方法需要配合 ADB Keyboard 使用
        // 或者使用系统输入法的辅助功能
        
        // 方案 1: 使用 ADB 命令（需要 root 或 ADB 权限）
        // Runtime.getRuntime().exec("input text \"$text\"")
        
        // 方案 2: 使用剪贴板粘贴
        // 暂时不实现，需要额外权限
        
        Log.d(TAG, "Simulating text input: $text")
    }
    
    /**
     * 点击坐标
     */
    private fun click(x: Int, y: Int) {
        tapScreen(x, y)
    }

    fun tapScreen(x: Int, y: Int): Boolean {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        
        return service.dispatchGesture(gesture, null, null)
    }

    private fun tapNodeCenter(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) {
            return false
        }
        return tapScreen(bounds.centerX(), bounds.centerY())
    }

    private data class MerchantCandidateScore(
        val node: AccessibilityNodeInfo,
        val score: Int,
        val comparableText: String,
        val context: String
    )
    
    /**
     * 长按坐标
     */
    private fun longClick(x: Int, y: Int) {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        
        service.dispatchGesture(gesture, null, null)
    }

    private fun swipePage(forward: Boolean): Boolean {
        val metrics = service.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val startY = if (forward) metrics.heightPixels * 0.78f else metrics.heightPixels * 0.32f
        val endY = if (forward) metrics.heightPixels * 0.32f else metrics.heightPixels * 0.78f

        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 450))
            .build()

        return service.dispatchGesture(gesture, null, null)
    }
    
    /**
     * 点击全选按钮
     */
    private fun clickSelectAll() {
        // 在弹出菜单中查找"全选"按钮
        val rootNode = service.rootInActiveWindow ?: return
        
        try {
            val selectAllButton = NodeUtils.findNodeByCondition(rootNode, condition = { node: AccessibilityNodeInfo ->
                if (!node.isClickable) return@findNodeByCondition false
                NodeUtils.getNodeText(node).contains("全选")
            })
            
            selectAllButton?.let { NodeUtils.clickNode(it) }
            
        } finally {
            rootNode.recycle()
        }
    }
    
    /**
     * 点击删除按钮
     */
    private fun clickDelete() {
        // 在弹出菜单中查找"删除"按钮
        val rootNode = service.rootInActiveWindow ?: return
        
        try {
            val deleteButton = NodeUtils.findNodeByCondition(rootNode, condition = { node: AccessibilityNodeInfo ->
                if (!node.isClickable) return@findNodeByCondition false
                val text = NodeUtils.getNodeText(node)
                text.contains("删除") || text.contains("剪切")
            })
            
            deleteButton?.let { NodeUtils.clickNode(it) }
            
        } finally {
            rootNode.recycle()
        }
    }
    
    /**
     * 判断当前是否是搜索页面
     */
    fun isSearchPage(): Boolean {
        return findSearchBox() != null
    }

    private fun findTextNode(targetText: String, exactMatch: Boolean): AccessibilityNodeInfo? {
        val rootNode = service.rootInActiveWindow ?: return null

        try {
            val normalizedTarget = normalizeText(targetText)
            if (normalizedTarget.isBlank()) {
                return null
            }

            val candidates = NodeUtils.findNodesByCondition(
                rootNode,
                condition = { node: AccessibilityNodeInfo ->
                    val comparableText = normalizeText(getComparableNodeText(node))
                    if (comparableText.isBlank()) {
                        return@findNodesByCondition false
                    }

                    if (exactMatch) {
                        comparableText == normalizedTarget
                    } else {
                        comparableText.contains(normalizedTarget)
                    }
                },
                maxDepth = 20
            )

            val scoredCandidate = candidates
                .map { it to scoreTextCandidate(it, normalizedTarget, exactMatch) }
                .filter { (_, score) -> score > 0 }
                .maxByOrNull { (_, score) -> score }

            val bestNode = scoredCandidate?.first
            val result = bestNode?.let { AccessibilityNodeInfo.obtain(it) }

            candidates.forEach { candidate ->
                if (candidate !== bestNode) {
                    candidate.recycle()
                }
            }
            bestNode?.recycle()

            return result
        } finally {
            rootNode.recycle()
        }
    }

    private fun findNodeByViewId(targetViewId: String): AccessibilityNodeInfo? {
        val rootNode = service.rootInActiveWindow ?: return null

        try {
            val matchedNode = NodeUtils.findNodeByCondition(
                rootNode,
                condition = { node: AccessibilityNodeInfo ->
                    val viewId = node.viewIdResourceName ?: return@findNodeByCondition false
                    viewId.contains(targetViewId, ignoreCase = true)
                },
                maxDepth = 20
            )

            return matchedNode?.let { AccessibilityNodeInfo.obtain(it) }
        } finally {
            rootNode.recycle()
        }
    }

    private fun scoreTextCandidate(
        node: AccessibilityNodeInfo,
        normalizedTarget: String,
        exactMatch: Boolean
    ): Int {
        val comparableText = normalizeText(getComparableNodeText(node))
        if (comparableText.isBlank()) {
            return 0
        }

        if (exactMatch && comparableText != normalizedTarget) {
            return 0
        }
        if (!exactMatch && !comparableText.contains(normalizedTarget)) {
            return 0
        }

        var score = 0
        if (comparableText == normalizedTarget) {
            score += 120
        }
        if (!exactMatch && comparableText.contains(normalizedTarget)) {
            score += 80
        }
        if (node.isClickable) {
            score += 15
        }
        if (node.className?.toString()?.contains("TextView", ignoreCase = true) == true) {
            score += 10
        }

        score -= abs(comparableText.length - normalizedTarget.length)
        return score
    }
}
