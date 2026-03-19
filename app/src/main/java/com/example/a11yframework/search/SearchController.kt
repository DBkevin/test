package com.example.a11yframework.search

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.a11yframework.utils.NodeUtils
import java.io.File
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

    private val douyinPageClassifier by lazy { DouyinPageClassifier(service) }

    data class PageTextMatchResult(
        val matched: Boolean,
        val matchedAllTexts: List<String> = emptyList(),
        val matchedAnyTexts: List<String> = emptyList(),
        val presentNoneTexts: List<String> = emptyList()
    )
    
    companion object {
        private const val TAG = "SearchController"
        private const val SEARCH_NODE_MAX_DEPTH = 18
        private const val MERCHANT_RESULT_NODE_MAX_DEPTH = 32
        private const val SEARCH_PREPARE_MAX_ATTEMPTS = 6
        private const val SEARCH_RETRY_DELAY_MS = 700L
        private const val MERCHANT_RESULT_OPEN_TIMEOUT_MS = 4200L
        private const val MERCHANT_RESULT_OPEN_GRACE_MS = 1500L
        private const val DOUYIN_GROUPBUY_TAB_TAP_X = 1016
        private const val DOUYIN_GROUPBUY_TAB_TAP_Y = 216
        private const val DOUYIN_GROUPBUY_SEARCH_ENTRY_TAP_X = 383
        private const val DOUYIN_GROUPBUY_SEARCH_ENTRY_TAP_Y = 373
        private const val DOUYIN_GROUPBUY_TOP_SEARCH_TAP_X = 1340
        private const val DOUYIN_GROUPBUY_TOP_SEARCH_TAP_Y = 219
        private const val DOUYIN_SEARCH_SUBMIT_TAP_X = 1331
        private const val DOUYIN_SEARCH_SUBMIT_TAP_Y = 215
        private const val DOUYIN_HOME_BOTTOM_TAB_TAP_X = 113
        private const val DOUYIN_HOME_BOTTOM_TAB_TAP_Y = 3020
        private const val DOUYIN_MERCHANT_ENTRY_BAND_TAP_X = 508
        private const val DOUYIN_MERCHANT_ENTRY_BAND_TAP_Y = 516
        private const val DOUYIN_GROUPBUY_WAIT_TIMEOUT_MS = 4_000L
        private const val DOUYIN_SEARCH_INPUT_WAIT_TIMEOUT_MS = 4_000L
        private const val DOUYIN_SEARCH_RESULT_WAIT_TIMEOUT_MS = 7_000L
        private const val DOUYIN_HOME_PREPARE_MAX_ATTEMPTS = 4
        private const val TAP_TRACE_FILE_NAME = "tap-trace-latest.txt"
        private const val TAP_TRACE_MAX_BYTES = 64 * 1024L
        
        // 搜索框特征
        private val SEARCH_KEYWORDS = listOf("搜索", "搜索框", "search", "放大镜")
        private val SEARCH_BUTTON_KEYWORDS = listOf("搜索", "查找", "search", "🔍")
        private val DOUYIN_GROUPBUY_PAGE_KEYWORDS = listOf("附近好店", "美食", "休闲娱乐", "景点/周边游", "酒店民宿", "丽人")
        private val DOUYIN_GROUPBUY_SEARCH_ENTRY_HINTS = listOf("美莱团购", "郑州", "搜索")
        private val DOUYIN_HOME_PAGE_KEYWORDS = listOf("团购", "推荐", "搜索", "首页", "我")
        private val SCROLLABLE_CLASS_KEYWORDS = listOf("RecyclerView", "ListView", "ScrollView", "NestedScrollView", "WebView")
        private val MERCHANT_RESULT_HINTS = listOf("医院", "门诊", "医疗美容", "美容医院", "诊所", "机构")
        private val MERCHANT_RESULT_CONTEXT_HINTS = listOf("评价", "回头客", "km", "m", "/人", "人均", "价格优惠")
        private val MERCHANT_RESULT_PRODUCT_HINTS = listOf(
            "已售",
            "人逛过",
            "次卡",
            "券后",
            "去抢购",
            "领券抢购",
            "继续追问",
            "至少提前",
            "随时退",
            "好评率"
        )
        private val MERCHANT_DETAIL_PAGE_HINTS = listOf("收藏", "关注", "在线咨询", "预约有礼", "领券抢购")
        private val MERCHANT_HOME_TOP_HINTS = listOf("关注", "回头客", "无隐形消费", "详情", "在线咨询", "电话")
        private val MERCHANT_TAIL_SECTION_HINTS = listOf(
            "展开更多",
            "收起",
            "预约到店送好礼",
            "预约到店专属礼",
            "用户评价"
        )
        private val MERCHANT_DETAIL_CARD_HINTS = listOf(
            "去抢购",
            "领券抢购",
            "已售",
            "现价",
            "原价",
            "券后",
            "人逛过",
            "至少提前",
            "随时退",
            "次卡"
        )
        private val POPUP_DISMISS_KEYWORDS = listOf("允许", "同意", "我知道了", "稍后", "关闭")

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
        beginTapTraceRun("douyin_search:$keyword")
        dismissCommonPopupActions(maxActions = 2)

        when (getCurrentDouyinPageKind(keyword)) {
            DouyinPageKind.GROUPBUY_SEARCH_INPUT -> {
                Log.d(TAG, "Douyin search input already visible, skip home/group-buy preparation")
            }
            DouyinPageKind.GROUPBUY_HOME -> {
                Log.d(TAG, "Douyin group-buy home already visible, skip tab selection")
            }
            else -> {
                if (!prepareDouyinHomePage()) {
                    Log.w(TAG, "Douyin home page not confirmed before selecting group buy tab")
                    return false
                }

                val tabSelected = selectDouyinGroupBuyTab()
                if (!tabSelected) {
                    Log.w(TAG, "Douyin group buy tab not explicitly selected, stop search to avoid path deviation")
                    return false
                }
                Thread.sleep(1200)
            }
        }

        val currentKind = getCurrentDouyinPageKind(keyword)
        if (currentKind != DouyinPageKind.GROUPBUY_SEARCH_INPUT &&
            currentKind != DouyinPageKind.GROUPBUY_HOME
        ) {
            Log.w(TAG, "Current page is not group-buy path: kind=$currentKind")
            return false
        }

        if (currentKind != DouyinPageKind.GROUPBUY_SEARCH_INPUT &&
            !waitForDouyinGroupBuyPage(DOUYIN_GROUPBUY_WAIT_TIMEOUT_MS)
        ) {
            Log.w(TAG, "Douyin group buy page not confirmed after tab selection, stop search")
            return false
        }
        dismissCommonPopupActions(maxActions = 1)

        val searchEntryOpened = openDouyinGroupBuySearchEntry()
        if (!searchEntryOpened) {
            Log.e(TAG, "Douyin group buy search entry not found")
            return false
        }

        if (!waitForDouyinSearchInputPage(DOUYIN_SEARCH_INPUT_WAIT_TIMEOUT_MS)) {
            Log.e(TAG, "Douyin dedicated search input page not reached")
            return false
        }
        dismissCommonPopupActions(maxActions = 1)

        val submitted = search(
            keyword = keyword,
            entryKeywords = DOUYIN_GROUPBUY_SEARCH_ENTRY_HINTS,
            buttonKeywords = listOf("搜索")
        )

        if (!submitted) {
            return false
        }

        if (!waitForDouyinMerchantResultPage(keyword, DOUYIN_SEARCH_RESULT_WAIT_TIMEOUT_MS)) {
            Log.w(TAG, "Douyin merchant result page not confirmed after search submit")
        }

        return true
    }

    fun openMerchantResult(merchantName: String, maxScrollRounds: Int = 0): Boolean {
        appendTapTraceLine("=== open_merchant_result:$merchantName @${System.currentTimeMillis()} ===")
        dismissCommonPopupActions(maxActions = 1)

        when (getCurrentDouyinPageKind(merchantName)) {
            DouyinPageKind.MERCHANT_HOME,
            DouyinPageKind.MERCHANT_TAIL -> {
                Log.d(TAG, "Merchant detail page already visible, skip reopening merchant result")
                return true
            }
            else -> Unit
        }

        if (!waitForDouyinMerchantResultPage(merchantName, DOUYIN_SEARCH_RESULT_WAIT_TIMEOUT_MS)) {
            Log.w(TAG, "Merchant result page not ready before opening merchant: $merchantName")
        }

        repeat(3) { settleRound ->
            dismissCommonPopupActions(maxActions = 1)
            val merchantNode = findMerchantResultNode(merchantName)
            if (merchantNode != null) {
                try {
                    val opened = openMerchantCandidate(merchantNode, merchantName, settleRound)
                    if (opened) {
                        return true
                    }
                } finally {
                    merchantNode.recycle()
                }
            }

            if (openMerchantWithPresetEntryBand(merchantName, settleRound, fallbackOnly = true)) {
                return true
            }

            if (settleRound < 2) {
                Thread.sleep(1200)
            }
        }

        repeat(maxScrollRounds + 1) { round ->
            dismissCommonPopupActions(maxActions = 1)
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

            if (openMerchantWithPresetEntryBand(merchantName, round, fallbackOnly = true)) {
                return true
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
                    val clicked = clickNodeWithTrace("click_text:$targetText", matchedNode)
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
                    tapScreen(fallbackTapX, fallbackTapY, "click_text_fallback:$targetText")
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

                    val clicked = clickNodeWithTrace("click_any_text:$targetText", matchedNode)
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

    fun clickMerchantExpandTexts(
        targetTexts: List<String>,
        maxClicks: Int = targetTexts.size
    ): Int {
        val normalizedTargets = targetTexts
            .map { normalizeText(it) }
            .filter { it.isNotBlank() }
            .distinct()
        if (normalizedTargets.isEmpty() || maxClicks <= 0) {
            return 0
        }

        val rootNode = service.rootInActiveWindow ?: return 0
        val metrics = service.resources.displayMetrics
        val minTop = (metrics.heightPixels * 0.48f).toInt()
        val maxBottom = (metrics.heightPixels * 0.93f).toInt()

        try {
            val candidates = NodeUtils.findNodesByCondition(
                rootNode,
                condition = { node: AccessibilityNodeInfo ->
                    val rawText = getComparableNodeText(node)
                    if (rawText.isBlank()) {
                        return@findNodesByCondition false
                    }
                    if (rawText.contains("你可能感兴趣", ignoreCase = true) ||
                        rawText.contains("猜你喜欢", ignoreCase = true)
                    ) {
                        return@findNodesByCondition false
                    }

                    val normalizedText = normalizeText(rawText)
                    val keywordMatched = normalizedTargets.any { target ->
                        normalizedText.contains(target)
                    }
                    if (!keywordMatched) {
                        return@findNodesByCondition false
                    }

                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    !bounds.isEmpty &&
                        bounds.top >= minTop &&
                        bounds.bottom <= maxBottom &&
                        bounds.height() in 36..220
                },
                maxDepth = 28
            )

            try {
                val sortedCandidates = candidates.sortedWith(
                    compareBy<AccessibilityNodeInfo>({ node ->
                        Rect().also { node.getBoundsInScreen(it) }.top
                    }, { node ->
                        Rect().also { node.getBoundsInScreen(it) }.left
                    })
                )

                val clickedKeys = mutableSetOf<String>()
                var clickCount = 0

                for (candidate in sortedCandidates) {
                    if (clickCount >= maxClicks) {
                        break
                    }

                    val bounds = Rect()
                    candidate.getBoundsInScreen(bounds)
                    if (bounds.isEmpty) {
                        continue
                    }

                    val clickKey = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
                    if (!clickedKeys.add(clickKey)) {
                        continue
                    }

                    val clicked = clickNodeWithTrace("merchant_expand", candidate)
                    if (clicked) {
                        clickCount++
                        Thread.sleep(500)
                    }
                }

                if (clickCount > 0) {
                    Log.i(TAG, "Merchant expand taps: count=$clickCount")
                }
                return clickCount
            } finally {
                NodeUtils.recycleNodes(candidates)
            }
        } finally {
            rootNode.recycle()
        }
    }

    fun clickViewId(viewId: String, maxScrollRounds: Int = 0): Boolean {
        repeat(maxScrollRounds + 1) { round ->
            val matchedNode = findNodeByViewId(viewId)
            if (matchedNode != null) {
                try {
                    val clicked = clickNodeWithTrace("click_view_id:$viewId", matchedNode)
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

    private fun getCurrentDouyinPageKind(merchantName: String = ""): DouyinPageKind {
        val rootNode = service.rootInActiveWindow ?: return DouyinPageKind.UNKNOWN
        return try {
            douyinPageClassifier.classify(rootNode, merchantName).kind
        } finally {
            rootNode.recycle()
        }
    }

    private fun prepareDouyinHomePage(): Boolean {
        repeat(DOUYIN_HOME_PREPARE_MAX_ATTEMPTS) { attempt ->
            dismissCommonPopupActions(maxActions = 1)
            val rootNode = service.rootInActiveWindow
            if (rootNode != null) {
                try {
                    when (douyinPageClassifier.classify(rootNode).kind) {
                        DouyinPageKind.GROUPBUY_HOME,
                        DouyinPageKind.HOME_FEED -> return true
                        else -> Unit
                    }
                } finally {
                    rootNode.recycle()
                }
            }

            val currentRoot = service.rootInActiveWindow
            if (currentRoot != null) {
                try {
                    when (douyinPageClassifier.classify(currentRoot).kind) {
                        DouyinPageKind.MERCHANT_HOME,
                        DouyinPageKind.MERCHANT_TAIL,
                        DouyinPageKind.MERCHANT_RESULT_LIST,
                        DouyinPageKind.RECOMMENDATION -> {
                        val backed = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        Log.d(TAG, "Back from intermediate Douyin page to reach home: attempt=${attempt + 1}, success=$backed")
                        Thread.sleep(1200)
                        return@repeat
                        }
                        else -> Unit
                    }
                } finally {
                    currentRoot.recycle()
                }
            }

            val tappedHome = tapDouyinHomeBottomTab()
            if (tappedHome) {
                Log.d(TAG, "Tapped Douyin home bottom tab on attempt=${attempt + 1}")
                Thread.sleep(1200)
                return@repeat
            }

            val backed = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Log.d(TAG, "Performed Douyin back to reach home: attempt=${attempt + 1}, success=$backed")
            Thread.sleep(1200)
        }

        val rootNode = service.rootInActiveWindow ?: return false
        return try {
            when (douyinPageClassifier.classify(rootNode).kind) {
                DouyinPageKind.GROUPBUY_HOME,
                DouyinPageKind.HOME_FEED -> true
                else -> false
            }
        } finally {
            rootNode.recycle()
        }
    }

    fun matchCurrentPageTexts(
        requiredAllTexts: List<String> = emptyList(),
        requiredAnyTexts: List<String> = emptyList(),
        absentTexts: List<String> = emptyList()
    ): PageTextMatchResult {
        val normalizedAllTexts = requiredAllTexts
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val normalizedAnyTexts = requiredAnyTexts
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val normalizedAbsentTexts = absentTexts
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        if (normalizedAllTexts.isEmpty() && normalizedAnyTexts.isEmpty() && normalizedAbsentTexts.isEmpty()) {
            return PageTextMatchResult(matched = false)
        }

        val rootNode = service.rootInActiveWindow ?: return PageTextMatchResult(matched = false)

        return try {
            val pageText = NodeUtils.getAllNodeText(
                rootNode,
                maxDepth = 18,
                maxNodes = 420,
                maxTextLength = 7000
            )
            val matchedAllTexts = normalizedAllTexts.filter { target ->
                pageText.contains(target, ignoreCase = true)
            }
            val matchedAnyTexts = normalizedAnyTexts.filter { target ->
                pageText.contains(target, ignoreCase = true)
            }
            val presentNoneTexts = normalizedAbsentTexts.filter { target ->
                pageText.contains(target, ignoreCase = true)
            }
            val allMatched = normalizedAllTexts.isEmpty() ||
                matchedAllTexts.size == normalizedAllTexts.size
            val anyMatched = normalizedAnyTexts.isEmpty() || matchedAnyTexts.isNotEmpty()
            val noneMatched = presentNoneTexts.isEmpty()

            PageTextMatchResult(
                matched = allMatched && anyMatched && noneMatched,
                matchedAllTexts = matchedAllTexts,
                matchedAnyTexts = matchedAnyTexts,
                presentNoneTexts = presentNoneTexts
            )
        } finally {
            rootNode.recycle()
        }
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
            // 方法 1: 直接尝试 ACTION_SET_TEXT。部分 ROM 上 actionList/isEditable 不可靠，
            // 但直接执行仍然能成功。
            if (trySetText(searchBox, "")) {
                Log.d(TAG, "Cleared search box with direct setText")
                return
            }

            refetchFocusedSearchBox()?.let { refreshedSearchBox ->
                try {
                    if (trySetText(refreshedSearchBox, "")) {
                        Log.d(TAG, "Cleared search box with refreshed setText")
                        return
                    }
                } finally {
                    refreshedSearchBox.recycle()
                }
            }

            // 方法 2: 使用 setText（如果显式声明支持）
            if (supportsSetText(searchBox)) {
                val arguments = android.os.Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                searchBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                Log.d(TAG, "Cleared search box with legacy setText")
                return
            }
            
            // 方法 3: 长按全选后删除
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
            // 方法 1: 直接尝试 ACTION_SET_TEXT，再按需要刷新节点重试。
            if (trySetText(searchBox, text)) {
                Log.d(TAG, "Input text with direct setText: $text")
                return
            }

            refetchFocusedSearchBox()?.let { refreshedSearchBox ->
                try {
                    if (trySetText(refreshedSearchBox, text)) {
                        Log.d(TAG, "Input text with refreshed setText: $text")
                        return
                    }
                } finally {
                    refreshedSearchBox.recycle()
                }
            }

            // 方法 2: 使用 setText（如果显式声明支持）
            if (supportsSetText(searchBox)) {
                val arguments = android.os.Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                searchBox.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                searchBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                Log.d(TAG, "Input text with legacy setText: $text")
                return
            }
            
            // 方法 3: 点击搜索框后使用输入法
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
            if (isOnDouyinSearchInputPage()) {
                dismissCommonPopupActions(maxActions = 1)

                val douyinSubmitNode = findDouyinSearchSubmitButtonNode(rootNode)
                if (douyinSubmitNode != null) {
                    try {
                        val clicked = clickNodeWithTrace("douyin_search_submit_node", douyinSubmitNode)
                        if (clicked) {
                            Log.d(TAG, "Clicked Douyin search submit button by node")
                            return true
                        }

                        val tapped = tapNodeCenter(douyinSubmitNode, "douyin_search_submit_node_bounds")
                        if (tapped) {
                            Log.d(TAG, "Tapped Douyin search submit button by node bounds")
                            return true
                        }
                    } finally {
                        douyinSubmitNode.recycle()
                    }
                }

                val tapped = tapScreen(
                    DOUYIN_SEARCH_SUBMIT_TAP_X,
                    DOUYIN_SEARCH_SUBMIT_TAP_Y,
                    "douyin_search_submit_preset"
                )
                if (tapped) {
                    Log.d(TAG, "Tapped Douyin search submit button with preset tap")
                }
                return tapped
            }

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
                val clicked = clickNodeWithTrace("search_submit_keyword", button)
                Log.d(TAG, "Clicked search button by keyword: $clicked")
                if (clicked) {
                    return true
                }

                val tapped = tapNodeCenter(button, "search_submit_keyword_bounds")
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
                val clicked = clickNodeWithTrace("search_submit_id", buttonById)
                Log.d(TAG, "Clicked search button by id: $clicked")
                if (clicked) {
                    return true
                }

                val tapped = tapNodeCenter(buttonById, "search_submit_id_bounds")
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
                val clicked = clickNodeWithTrace("open_search_entry", entryNode)
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
            val pageSnapshot = douyinPageClassifier.classify(rootNode)
            if (pageSnapshot.kind == DouyinPageKind.GROUPBUY_HOME) {
                return true
            }

            if (pageSnapshot.signals.hasSelectedGroupBuyTab) {
                Log.d(TAG, "Douyin group buy tab already selected")
                return true
            }

            if (!isSafeToTapDouyinGroupBuyTab(rootNode)) {
                Log.w(TAG, "Skip Douyin group buy tab tap: current page is not a safe home/group-buy page")
                return false
            }

            val tabNode = NodeUtils.findNodeByCondition(
                rootNode,
                condition = { node: AccessibilityNodeInfo ->
                    val nodeText = getComparableNodeText(node)
                    nodeText.contains("团购", ignoreCase = true)
                },
                maxDepth = 24
            )

            if (tabNode != null) {
                try {
                    val nodeText = getComparableNodeText(tabNode)
                    if (nodeText.contains("已选中，团购", ignoreCase = true) ||
                        nodeText.contains("团购，按钮", ignoreCase = true)
                    ) {
                        Log.d(TAG, "Douyin group buy tab already selected by node")
                        return true
                    }

                    val clicked = NodeUtils.clickNode(tabNode)
                    recordNodeClickTrace("douyin_groupbuy_tab_node", tabNode, clicked)
                    Log.d(TAG, "Selected Douyin group buy tab: $clicked")
                    if (clicked) {
                        return true
                    }
                } finally {
                    tabNode.recycle()
                }
            }

            val tapped = tapScreen(
                DOUYIN_GROUPBUY_TAB_TAP_X,
                DOUYIN_GROUPBUY_TAB_TAP_Y,
                "douyin_groupbuy_tab_preset"
            )
            if (tapped) {
                Log.d(TAG, "Selected Douyin group buy tab with preset tap")
                return true
            }

            return false
        } finally {
            rootNode.recycle()
        }
    }

    private fun isLikelyDouyinGroupBuyPage(rootNode: AccessibilityNodeInfo): Boolean {
        return douyinPageClassifier.classify(rootNode).kind == DouyinPageKind.GROUPBUY_HOME
    }

    private fun isLikelyDouyinHomePage(rootNode: AccessibilityNodeInfo): Boolean {
        val minBottomTabTop = (service.resources.displayMetrics.heightPixels * 0.82f).toInt()
        val pageText = NodeUtils.getAllNodeText(
            rootNode,
            maxDepth = 18,
            maxNodes = 420,
            maxTextLength = 7000
        )
        val hitCount = DOUYIN_HOME_PAGE_KEYWORDS.count { keyword ->
            pageText.contains(keyword, ignoreCase = true)
        }
        if (hitCount >= 4) {
            return true
        }

        val homeTabNode = NodeUtils.findNodeByCondition(
            rootNode,
            condition = { node ->
                val text = getComparableNodeText(node)
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                text.contains("首页", ignoreCase = true) &&
                    bounds.top >= minBottomTabTop
            },
            maxDepth = 20
        )

        val matched = homeTabNode != null
        homeTabNode?.recycle()
        return matched
    }

    private fun tapDouyinHomeBottomTab(): Boolean {
        val minBottomTabTop = (service.resources.displayMetrics.heightPixels * 0.82f).toInt()
        val rootNode = service.rootInActiveWindow
        if (rootNode != null) {
            try {
                val homeNode = NodeUtils.findNodeByCondition(
                    rootNode,
                    condition = { node ->
                        val text = getComparableNodeText(node)
                        val bounds = Rect().also { node.getBoundsInScreen(it) }
                        text.contains("首页", ignoreCase = true) &&
                            bounds.top >= minBottomTabTop
                    },
                    maxDepth = 20
                )

                if (homeNode != null) {
                    try {
                        val clicked = NodeUtils.clickNode(homeNode)
                        recordNodeClickTrace("douyin_home_bottom_tab_node", homeNode, clicked)
                        if (clicked) {
                            return true
                        }

                        val tapped = tapNodeCenter(homeNode, "douyin_home_bottom_tab_bounds")
                        if (tapped) {
                            return true
                        }
                    } finally {
                        homeNode.recycle()
                    }
                }
            } finally {
                rootNode.recycle()
            }
        }

        return tapScreen(
            DOUYIN_HOME_BOTTOM_TAB_TAP_X,
            DOUYIN_HOME_BOTTOM_TAB_TAP_Y,
            "douyin_home_bottom_tab_preset"
        )
    }

    private fun waitForDouyinGroupBuyPage(timeoutMs: Long): Boolean {
        val startAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startAt < timeoutMs) {
            val rootNode = service.rootInActiveWindow
            if (rootNode != null) {
                try {
                    val kind = douyinPageClassifier.classify(rootNode).kind
                    if (kind == DouyinPageKind.GROUPBUY_HOME) {
                        return true
                    }
                } finally {
                    rootNode.recycle()
                }
            }
            Thread.sleep(300)
        }
        return false
    }

    private fun openDouyinGroupBuySearchEntry(): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false

        try {
            val pageSnapshot = douyinPageClassifier.classify(rootNode)
            if (pageSnapshot.kind == DouyinPageKind.GROUPBUY_SEARCH_INPUT) {
                return true
            }
            if (pageSnapshot.kind != DouyinPageKind.GROUPBUY_HOME) {
                Log.w(TAG, "Skip Douyin group buy search entry tap: current page is not group-buy home")
                return false
            }

            val entryNode = findDouyinGroupBuySearchEntryNode(rootNode)

            if (entryNode != null) {
                try {
                    val clicked = NodeUtils.clickNode(entryNode)
                    recordNodeClickTrace("douyin_groupbuy_search_entry_node", entryNode, clicked)
                    if (clicked) {
                        Log.d(TAG, "Opened Douyin group buy search entry by node click")
                        return true
                    }

                    val tapped = tapNodeCenter(entryNode, "douyin_groupbuy_search_entry_bounds")
                    if (tapped) {
                        Log.d(TAG, "Opened Douyin group buy search entry by node bounds")
                        return true
                    }
                } finally {
                    entryNode.recycle()
                }
            }
            Log.w(TAG, "Douyin group-buy search entry node not found, stop to avoid wrong search path")
            return false
        } finally {
            rootNode.recycle()
        }
    }

    private fun waitForDouyinSearchInputPage(timeoutMs: Long): Boolean {
        val startAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startAt < timeoutMs) {
            val rootNode = service.rootInActiveWindow
            if (rootNode != null) {
                try {
                    if (douyinPageClassifier.classify(rootNode).kind == DouyinPageKind.GROUPBUY_SEARCH_INPUT) {
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

    private fun isOnDouyinSearchInputPage(): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false

        try {
            return douyinPageClassifier.classify(rootNode).kind == DouyinPageKind.GROUPBUY_SEARCH_INPUT
        } finally {
            rootNode.recycle()
        }
    }

    private fun waitForDouyinMerchantResultPage(merchantName: String, timeoutMs: Long): Boolean {
        val startAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startAt < timeoutMs) {
            val rootNode = service.rootInActiveWindow
            if (rootNode != null) {
                try {
                    if (douyinPageClassifier.classify(rootNode, merchantName).kind == DouyinPageKind.MERCHANT_RESULT_LIST) {
                        return true
                    }
                } finally {
                    rootNode.recycle()
                }
            }
            Thread.sleep(300)
        }
        return false
    }

    private fun isLikelyDouyinMerchantResultPage(
        rootNode: AccessibilityNodeInfo,
        merchantName: String
    ): Boolean {
        return douyinPageClassifier.classify(rootNode, merchantName).kind == DouyinPageKind.MERCHANT_RESULT_LIST
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
                maxDepth = MERCHANT_RESULT_NODE_MAX_DEPTH
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
        val merchantEntryBand = findMerchantEntryBand(node)
        val entryBandBounds = Rect()
        merchantEntryBand?.getBoundsInScreen(entryBandBounds)
        merchantEntryBand?.recycle()
        val hasEntryBand = !entryBandBounds.isEmpty
        val entryBandInPrimaryZone = hasEntryBand && entryBandBounds.top in 420..1400
        val entryBandTooLow = hasEntryBand && entryBandBounds.top >= 1800
        val hasProductContext = MERCHANT_RESULT_PRODUCT_HINTS.any { hint ->
            contextText.contains(hint, ignoreCase = true)
        }

        // 抖音结果页里同名文本很多，只有位于上半屏入口带的候选才允许参与点击。
        if (!hasEntryBand || !entryBandInPrimaryZone) {
            return 0
        }
        if (entryBandTooLow) {
            return 0
        }
        if (hasProductContext && !hasStructuredContext && !hasReviewContext && !hasRepeatCustomerContext) {
            return 0
        }

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
        if (hasEntryBand) {
            score += 160
            if (entryBandBounds.width() >= 900) {
                score += 80
            }
            if (entryBandBounds.height() in 40..110) {
                score += 40
            }
        } else {
            score -= 220
        }
        if (entryBandInPrimaryZone) {
            score += 140
        } else if (entryBandTooLow) {
            score -= 260
        } else if (bounds.top in 420..1200) {
            score += 35
        } else {
            score -= 120
        }
        if (node.isClickable) {
            score += 10
        }
        if (node.className?.toString()?.contains("TextView", ignoreCase = true) == true) {
            score += 10
        }
        if (hasProductContext) {
            score -= 180
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
        val tappedBand = tapMerchantEntryBand(
            merchantNode,
            "merchant_result_entry_band_r$round"
        )
        if (tappedBand && waitForMerchantDetailPage(merchantName, MERCHANT_RESULT_OPEN_TIMEOUT_MS)) {
            Log.i(TAG, "Merchant result opened by entry band tap: name=$merchantName, round=$round")
            return true
        }

        Log.w(
            TAG,
            "Merchant result did not open detail page by entry band: name=$merchantName, round=$round, tappedBand=$tappedBand"
        )
        return false
    }

    private fun openMerchantWithPresetEntryBand(
        merchantName: String,
        round: Int,
        fallbackOnly: Boolean = false
    ): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false

        try {
            if (!isLikelyDouyinMerchantResultPage(rootNode, merchantName)) {
                return false
            }
        } finally {
            rootNode.recycle()
        }

        val tapped = tapScreen(
            DOUYIN_MERCHANT_ENTRY_BAND_TAP_X,
            DOUYIN_MERCHANT_ENTRY_BAND_TAP_Y,
            "merchant_result_preset_band_r$round"
        )
        if (!tapped) {
            return false
        }

        val opened = waitForMerchantDetailPage(merchantName, MERCHANT_RESULT_OPEN_TIMEOUT_MS)
        if (opened) {
            Log.i(
                TAG,
                "Merchant result opened by preset entry band: name=$merchantName, round=$round, fallbackOnly=$fallbackOnly"
            )
        }
        return opened
    }

    private fun waitForMerchantDetailPage(merchantName: String, timeoutMs: Long): Boolean {
        val startAt = System.currentTimeMillis()

        while (System.currentTimeMillis() - startAt < timeoutMs) {
            val rootNode = service.rootInActiveWindow
            if (rootNode != null) {
                try {
                    val kind = douyinPageClassifier.classify(rootNode, merchantName).kind
                    if (kind == DouyinPageKind.MERCHANT_HOME || kind == DouyinPageKind.MERCHANT_TAIL) {
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

    fun waitForMerchantHomepageAnchors(merchantName: String, timeoutMs: Long): Boolean {
        val normalizedTarget = normalizeText(merchantName)
        if (pollMerchantHomepageAnchors(normalizedTarget, timeoutMs)) {
            return true
        }

        val confirmedByGraceAnchors = pollMerchantHomepageAnchors(
            normalizedTarget = normalizedTarget,
            timeoutMs = MERCHANT_RESULT_OPEN_GRACE_MS
        )
        if (confirmedByGraceAnchors) {
            Log.d(TAG, "Merchant detail page confirmed by homepage anchors during grace window")
        }
        return confirmedByGraceAnchors
    }

    fun isOnMerchantDetailPage(merchantName: String): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false

        return try {
            val kind = douyinPageClassifier.classify(rootNode, merchantName).kind
            kind == DouyinPageKind.MERCHANT_HOME || kind == DouyinPageKind.MERCHANT_TAIL
        } finally {
            rootNode.recycle()
        }
    }

    fun recoverMerchantDetailPage(
        merchantName: String,
        maxBackAttempts: Int = 2
    ): Boolean {
        if (isOnMerchantDetailPage(merchantName)) {
            return true
        }

        repeat(maxBackAttempts) { attempt ->
            val backed = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            appendTapTraceLine("${System.currentTimeMillis()} label=recover_merchant_back attempt=${attempt + 1} success=$backed")
            if (!backed) {
                return@repeat
            }

            if (waitForMerchantDetailPage(merchantName, 1_800L)) {
                Log.i(TAG, "Recovered merchant detail page by back action: attempt=${attempt + 1}")
                return true
            }

            Thread.sleep(350)
        }

        return isOnMerchantDetailPage(merchantName)
    }

    fun isOnDouyinMerchantResultPage(merchantName: String): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false

        return try {
            isLikelyDouyinMerchantResultPage(rootNode, merchantName)
        } finally {
            rootNode.recycle()
        }
    }

    private fun pollMerchantHomepageAnchors(normalizedTarget: String, timeoutMs: Long): Boolean {
        val startAt = System.currentTimeMillis()

        while (System.currentTimeMillis() - startAt < timeoutMs) {
            val rootNode = service.rootInActiveWindow
            if (rootNode != null) {
                try {
                    if (hasMerchantHomepageAnchors(rootNode, normalizedTarget)) {
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
        val kind = douyinPageClassifier.classify(rootNode, normalizedTarget).kind
        if (kind == DouyinPageKind.MERCHANT_HOME || kind == DouyinPageKind.MERCHANT_TAIL) {
            return true
        }

        if (hasMerchantHomepageAnchors(rootNode, normalizedTarget)) {
            return true
        }

        val pageText = NodeUtils.getAllNodeText(
            rootNode,
            maxDepth = 18,
            maxNodes = 360,
            maxTextLength = 5000
        )
        val normalizedPageText = normalizeText(pageText)
        val hasMerchantName = normalizedTarget.isBlank() || normalizedPageText.contains(normalizedTarget)
        val detailSignalCount = MERCHANT_DETAIL_PAGE_HINTS.count { hint ->
            pageText.contains(hint, ignoreCase = true)
        }
        val hasDetailSignal = detailSignalCount > 0
        val hasTailSignal = MERCHANT_TAIL_SECTION_HINTS.any { hint ->
            pageText.contains(hint, ignoreCase = true)
        }
        val hasCommerceSignal = MERCHANT_DETAIL_CARD_HINTS.any { hint ->
            pageText.contains(hint, ignoreCase = true)
        }
        val hasStickyTopBarSignal =
            pageText.contains("收藏", ignoreCase = true) ||
                pageText.contains("关注", ignoreCase = true)
        val hasBottomActionBar = hasMerchantBottomActionBarAnchor(rootNode)
        val hasVisibleSearchInput = NodeUtils.findNodeByCondition(
            rootNode,
            condition = { node -> isLikelySearchInput(node) },
            maxDepth = 18
        )?.let { node ->
            node.recycle()
            true
        } ?: false

        if (hasVisibleSearchInput && !hasStickyTopBarSignal && !hasBottomActionBar) {
            return false
        }

        if (hasMerchantName && hasDetailSignal) {
            return true
        }

        if (hasStickyTopBarSignal && hasBottomActionBar && hasTailSignal && hasCommerceSignal) {
            return true
        }

        return hasStickyTopBarSignal && hasCommerceSignal
    }

    private fun isSafeToTapDouyinGroupBuyTab(rootNode: AccessibilityNodeInfo): Boolean {
        return when (douyinPageClassifier.classify(rootNode).kind) {
            DouyinPageKind.HOME_FEED,
            DouyinPageKind.GROUPBUY_HOME -> true
            else -> false
        }
    }

    private fun findDouyinGroupBuySearchEntryNode(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return NodeUtils.findNodeByCondition(
            rootNode,
            condition = { node: AccessibilityNodeInfo ->
                val viewId = node.viewIdResourceName?.lowercase().orEmpty()
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                val nodeText = getComparableNodeText(node)
                viewId.contains("et_search_kw") &&
                    !isLikelySearchInput(node) &&
                    bounds.top in 280..420 &&
                    bounds.bottom in 340..460 &&
                    DOUYIN_GROUPBUY_SEARCH_ENTRY_HINTS.any { hint ->
                        nodeText.contains(hint, ignoreCase = true)
                    }
            },
            maxDepth = 24
        )?.let { AccessibilityNodeInfo.obtain(it) }
    }

    private fun findDouyinTopSearchButtonNode(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return NodeUtils.findNodeByCondition(
            rootNode,
            condition = { node ->
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                val text = getComparableNodeText(node)
                val viewId = node.viewIdResourceName?.lowercase().orEmpty()
                val className = node.className?.toString().orEmpty()
                bounds.top in 120..320 &&
                    bounds.right >= service.resources.displayMetrics.widthPixels - 260 &&
                    (
                        text.contains("搜索", ignoreCase = true) ||
                            viewId.contains("4_s") ||
                            (className.contains("Button", ignoreCase = true) && text.isNotBlank())
                    )
            },
            maxDepth = 32
        )?.let { AccessibilityNodeInfo.obtain(it) }
    }

    private fun findDouyinSearchSubmitButtonNode(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val width = service.resources.displayMetrics.widthPixels
        return NodeUtils.findNodeByCondition(
            rootNode,
            condition = { node ->
                if (isLikelySearchInput(node)) {
                    return@findNodeByCondition false
                }

                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (bounds.isEmpty || bounds.top !in 100..360 || bounds.right < width - 280) {
                    return@findNodeByCondition false
                }

                val text = getComparableNodeText(node)
                val viewId = node.viewIdResourceName?.lowercase().orEmpty()
                viewId.contains("4_s") ||
                    text.equals("搜索", ignoreCase = true) ||
                    (node.isClickable && text.contains("搜索", ignoreCase = true))
            },
            maxDepth = 24
        )?.let { AccessibilityNodeInfo.obtain(it) }
    }

    private fun hasSelectedDouyinGroupBuyTab(rootNode: AccessibilityNodeInfo): Boolean {
        val groupBuyTabNode = NodeUtils.findNodeByCondition(
            rootNode,
            condition = { node ->
                val text = getComparableNodeText(node)
                text.contains("已选中，团购", ignoreCase = true) ||
                    text == "团购" ||
                    text.contains("团购，按钮", ignoreCase = true)
            },
            maxDepth = 32
        )

        val matched = groupBuyTabNode != null
        groupBuyTabNode?.recycle()
        return matched
    }

    private fun hasMerchantHomepageAnchors(
        rootNode: AccessibilityNodeInfo,
        normalizedTarget: String
    ): Boolean {
        return hasMerchantHeaderAnchor(rootNode, normalizedTarget) &&
            hasMerchantBottomActionBarAnchor(rootNode)
    }

    private fun hasMerchantHeaderAnchor(
        rootNode: AccessibilityNodeInfo,
        normalizedTarget: String
    ): Boolean {
        val nodes = NodeUtils.findNodesByCondition(
            rootNode,
            condition = { node ->
                val text = NodeUtils.getNodeText(node)
                if (text.isBlank()) {
                    false
                } else {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    !bounds.isEmpty &&
                        bounds.top in 450..1500 &&
                        bounds.bottom <= 1700
                }
            },
            maxDepth = 24
        )

        var hasMerchantTitle = normalizedTarget.isBlank()
        var hintMatches = 0

        try {
            nodes.forEach { node ->
                val text = NodeUtils.getNodeText(node)
                val normalizedText = normalizeText(text)
                if (!hasMerchantTitle && normalizedTarget.isNotBlank() && normalizedText.contains(normalizedTarget)) {
                    hasMerchantTitle = true
                }
                if (MERCHANT_HOME_TOP_HINTS.any { hint -> text.contains(hint, ignoreCase = true) }) {
                    hintMatches++
                }
            }
        } finally {
            NodeUtils.recycleNodes(nodes)
        }

        return hasMerchantTitle && hintMatches > 0
    }

    private fun hasMerchantBottomActionBarAnchor(rootNode: AccessibilityNodeInfo): Boolean {
        val metrics = service.resources.displayMetrics
        val minTop = (metrics.heightPixels * 0.88f).toInt()
        val minWidth = (metrics.widthPixels * 0.98f).toInt()

        val bottomBarNode = NodeUtils.findNodeByCondition(
            rootNode,
            condition = { node ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                !bounds.isEmpty &&
                    bounds.top >= minTop &&
                    bounds.left <= 10 &&
                    bounds.right >= metrics.widthPixels - 10 &&
                    bounds.width() >= minWidth &&
                    bounds.height() in 140..260 &&
                    !node.isScrollable
            },
            maxDepth = 18
        )

        return bottomBarNode?.let { node ->
            node.recycle()
            true
        } ?: false
    }

    private fun tapMerchantEntryBand(
        merchantNode: AccessibilityNodeInfo,
        label: String
    ): Boolean {
        val titleBounds = Rect()
        merchantNode.getBoundsInScreen(titleBounds)
        if (titleBounds.isEmpty) {
            appendTapTraceLine("${System.currentTimeMillis()} label=$label skipped=empty_title_bounds")
            return false
        }

        val candidateBand = findMerchantEntryBand(merchantNode)
        if (candidateBand == null) {
            appendTapTraceLine("${System.currentTimeMillis()} label=$label skipped=no_entry_band")
            return false
        }

        val targetBounds = Rect()
        val targetX: Int
        val targetY: Int

        return try {
            candidateBand.getBoundsInScreen(targetBounds)
            if (targetBounds.isEmpty) {
                appendTapTraceLine("${System.currentTimeMillis()} label=$label skipped=empty_target_bounds")
                return false
            }

            val metrics = service.resources.displayMetrics
            targetX = titleBounds.centerX()
                .coerceIn(targetBounds.left + 48, targetBounds.right - 48)
                .coerceIn(0, metrics.widthPixels - 1)
            targetY = titleBounds.centerY()
                .coerceIn(targetBounds.top + 16, targetBounds.bottom - 16)
                .coerceIn(0, metrics.heightPixels - 1)
            tapScreen(
                targetX,
                targetY,
                label
            )
        } finally {
            candidateBand.recycle()
        }
    }

    private fun findMerchantEntryBand(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val titleBounds = Rect()
        node.getBoundsInScreen(titleBounds)
        if (titleBounds.isEmpty) {
            return null
        }

        var currentParent = node.parent
        var depth = 0
        var candidateBand: AccessibilityNodeInfo? = null
        var bestBandScore = Int.MIN_VALUE

        while (currentParent != null && depth < 8) {
            val parentBounds = Rect()
            currentParent.getBoundsInScreen(parentBounds)

            val containsTitle =
                !parentBounds.isEmpty &&
                    parentBounds.left <= titleBounds.left &&
                    parentBounds.right >= titleBounds.right &&
                    parentBounds.top <= titleBounds.top + 24 &&
                    parentBounds.bottom >= titleBounds.bottom
            val widthFits = parentBounds.width() >= maxOf(titleBounds.width() + 240, 760)
            val heightFits = parentBounds.height() in 48..140
            val topFits = parentBounds.top in 420..2200

            if (containsTitle && widthFits && heightFits && topFits) {
                val bandScore =
                    parentBounds.width() -
                        abs(parentBounds.height() - titleBounds.height()) -
                        (parentBounds.top / 6)
                if (bandScore > bestBandScore) {
                    candidateBand?.recycle()
                    candidateBand = AccessibilityNodeInfo.obtain(currentParent)
                    bestBandScore = bandScore
                }
            }

            val nextParent = currentParent.parent
            currentParent.recycle()
            currentParent = nextParent
            depth++
        }

        return candidateBand
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

    private fun trySetText(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val arguments = android.os.Bundle()
            arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } catch (e: Exception) {
            Log.w(TAG, "Direct setText failed: ${e.message}")
            false
        }
    }

    private fun refetchFocusedSearchBox(): AccessibilityNodeInfo? {
        val rootNode = service.rootInActiveWindow ?: return null

        try {
            val focusedNode = NodeUtils.findNodeByCondition(
                rootNode,
                condition = { node: AccessibilityNodeInfo ->
                    isLikelySearchInput(node) && (node.isFocused || node.isFocusable || node.isEditable)
                },
                maxDepth = SEARCH_NODE_MAX_DEPTH
            )

            return focusedNode?.let { AccessibilityNodeInfo.obtain(it) }
        } finally {
            rootNode.recycle()
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

    private fun beginTapTraceRun(label: String) {
        appendTapTraceLine("")
        appendTapTraceLine("=== $label @${System.currentTimeMillis()} ===")
    }

    private fun appendTapTraceLine(line: String) {
        try {
            val traceFile = File(service.filesDir, TAP_TRACE_FILE_NAME)
            if (traceFile.exists() && traceFile.length() > TAP_TRACE_MAX_BYTES) {
                traceFile.writeText("")
            }
            traceFile.appendText("$line\n")
        } catch (_: Exception) {
        }
    }

    private fun recordTapTrace(
        label: String,
        x: Int,
        y: Int,
        dispatched: Boolean? = null,
        note: String = ""
    ) {
        val status = when (dispatched) {
            true -> "dispatched=true"
            false -> "dispatched=false"
            null -> "dispatched=pending"
        }
        val suffix = if (note.isBlank()) "" else " note=$note"
        appendTapTraceLine("${System.currentTimeMillis()} label=$label x=$x y=$y $status$suffix")
    }

    private fun recordNodeClickTrace(
        label: String,
        node: AccessibilityNodeInfo,
        clicked: Boolean? = null
    ) {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val status = when (clicked) {
            true -> "clicked=true"
            false -> "clicked=false"
            null -> "clicked=pending"
        }
        val text = getComparableNodeText(node).take(48).replace("\n", " ")
        appendTapTraceLine(
            "${System.currentTimeMillis()} label=$label bounds=${bounds.flattenToString()} $status text=$text"
        )
    }

    private fun clickNodeWithTrace(label: String, node: AccessibilityNodeInfo): Boolean {
        recordNodeClickTrace(label, node)
        val clicked = NodeUtils.clickNode(node)
        recordNodeClickTrace(label, node, clicked)
        return clicked
    }
    
    /**
     * 点击坐标
     */
    private fun click(x: Int, y: Int) {
        tapScreen(x, y, "click")
    }

    fun tapScreen(x: Int, y: Int, label: String = "tap"): Boolean {
        recordTapTrace(label, x, y)
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        val dispatched = service.dispatchGesture(gesture, null, null)
        recordTapTrace(label, x, y, dispatched)
        return dispatched
    }

    private fun tapNodeCenter(node: AccessibilityNodeInfo, label: String = "node_center"): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) {
            return false
        }
        return tapScreen(bounds.centerX(), bounds.centerY(), label)
    }

    private fun dismissCommonPopupActions(maxActions: Int = 2): Int {
        if (maxActions <= 0) {
            return 0
        }

        var dismissedCount = 0
        repeat(maxActions) {
            val dismissed = clickPopupActionByKeyword()
            if (!dismissed) {
                return dismissedCount
            }
            dismissedCount++
            Thread.sleep(450)
        }
        return dismissedCount
    }

    private fun clickPopupActionByKeyword(): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false

        try {
            val metrics = service.resources.displayMetrics
            val minTop = (metrics.heightPixels * 0.12f).toInt()
            val maxBottom = (metrics.heightPixels * 0.98f).toInt()

            for (keyword in POPUP_DISMISS_KEYWORDS) {
                val actionNode = NodeUtils.findNodeByCondition(
                    rootNode,
                    condition = { node ->
                        val text = getComparableNodeText(node)
                        if (text.isBlank()) {
                            return@findNodeByCondition false
                        }

                        val matchedKeyword =
                            text.equals(keyword, ignoreCase = true) ||
                                text.contains(keyword, ignoreCase = true)
                        if (!matchedKeyword) {
                            return@findNodeByCondition false
                        }

                        val bounds = Rect().also { node.getBoundsInScreen(it) }
                        !bounds.isEmpty &&
                            bounds.top >= minTop &&
                            bounds.bottom <= maxBottom &&
                            (node.isClickable || node.isFocusable)
                    },
                    maxDepth = 26
                )

                if (actionNode != null) {
                    try {
                        val clicked = clickNodeWithTrace("popup_action_$keyword", actionNode)
                        val dispatched = if (clicked) {
                            true
                        } else {
                            tapNodeCenter(actionNode, "popup_action_${keyword}_bounds")
                        }
                        if (dispatched) {
                            Log.i(TAG, "Popup action dismissed by keyword: $keyword")
                            return true
                        }
                    } finally {
                        actionNode.recycle()
                    }
                }
            }
        } finally {
            rootNode.recycle()
        }

        return false
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
