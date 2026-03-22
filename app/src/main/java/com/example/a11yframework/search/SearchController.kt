package com.example.a11yframework.search

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.a11yframework.core.FrameworkAccessibilityService
import com.example.a11yframework.utils.NodeUtils
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

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

    private enum class DouyinMerchantActionBandType {
        EXPAND,
        TAIL_COLLAPSE
    }

    private data class DouyinMerchantActionBand(
        val bounds: Rect,
        val type: DouyinMerchantActionBandType,
        val bottomBarOffset: Int
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
        private const val DOUYIN_MERCHANT_GROUPBUY_TAB_TAP_X = 177
        private const val DOUYIN_MERCHANT_GROUPBUY_TAB_TAP_Y = 392
        private const val DOUYIN_SEARCH_SUBMIT_TAP_X = 1331
        private const val DOUYIN_SEARCH_SUBMIT_TAP_Y = 215
        private const val DOUYIN_HOME_BOTTOM_TAB_TAP_X = 113
        private const val DOUYIN_HOME_BOTTOM_TAB_TAP_Y = 3020
        private const val DOUYIN_MERCHANT_ENTRY_BAND_TAP_X = 823
        private const val DOUYIN_MERCHANT_ENTRY_BAND_TAP_Y = 516
        private const val DOUYIN_GROUPBUY_WAIT_TIMEOUT_MS = 4_000L
        private const val DOUYIN_SEARCH_INPUT_WAIT_TIMEOUT_MS = 4_000L
        private const val DOUYIN_SEARCH_RESULT_WAIT_TIMEOUT_MS = 7_000L
        private const val DOUYIN_HOME_PREPARE_MAX_ATTEMPTS = 4
        private const val DOUYIN_GROUPBUY_PRE_SEARCH_SCROLL_ROUNDS = 2
        private const val DOUYIN_GROUPBUY_PRE_SEARCH_SCROLL_SETTLE_MS = 900L
        private const val DOUYIN_GROUPBUY_LIVE_REMINDER_WAIT_MS = 15_000L
        private const val TAP_TRACE_FILE_NAME = "tap-trace-latest.txt"
        private const val TAP_TRACE_MAX_BYTES = 64 * 1024L
        private const val DOUYIN_EXPAND_ACTION_VALIDATION_DELAY_MS = 700L
        private const val DOUYIN_EXPAND_BAND_MIN_HEIGHT = 56
        private const val DOUYIN_EXPAND_BAND_MAX_HEIGHT = 180
        private const val DOUYIN_EXPAND_CARD_GAP_MIN = 24
        private const val DOUYIN_EXPAND_BAND_MIN_BOTTOM_BAR_OFFSET = 1_350
        private const val DOUYIN_EXPAND_BAND_MAX_BOTTOM_BAR_OFFSET = 2_100
        private const val DOUYIN_TAIL_BAND_MIN_BOTTOM_BAR_OFFSET = 760
        private const val DOUYIN_TAIL_BAND_MAX_BOTTOM_BAR_OFFSET = 1_260
        private const val DOUYIN_ACTION_BAND_BOTTOM_BAR_CLEARANCE = 320

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
            "展开全部",
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
        private val MERCHANT_OVERLAY_CLOSE_HINTS = listOf("关闭", "跳过", "暂不", "以后再说", "我知道了", "知道了")
        private val MERCHANT_GROUPBUY_CARD_SIGNAL_HINTS = listOf(
            "领券抢购",
            "去抢购",
            "已售",
            "现价",
            "原价",
            "人逛过",
            "至少提前",
            "随时退"
        )
        private val DOUYIN_GROUPBUY_TAB_TAP_RECT = Rect(900, 150, 1200, 300)
        private val DOUYIN_GROUPBUY_INLINE_SEARCH_BAR_RECT = Rect(28, 292, 1139, 436)
        private val DOUYIN_GROUPBUY_INLINE_KEYWORD_ENTRY_TAP_RECT = Rect(220, 300, 930, 440)
        private val DOUYIN_GROUPBUY_SEARCH_ENTRY_TAP_RECT = Rect(80, 320, 500, 420)
        private val DOUYIN_MERCHANT_GROUPBUY_TAB_TAP_RECT = Rect(80, 300, 320, 500)
        private val DOUYIN_SEARCH_SUBMIT_TAP_RECT = Rect(1220, 138, 1440, 292)
        private val DOUYIN_MERCHANT_ENTRY_BAND_RECT = Rect(263, 487, 1384, 708)
        private val DOUYIN_EXPAND_TAP_POINTS = listOf(
            0.50f to 0.68f,
            0.62f to 0.64f,
            0.50f to 0.34f
        )

        // 抖音搜索页面特征
        private const val DOUYIN_SEARCH_ACTIVITY = "com.ss.android.ugc.aweme.search.activity.SearchResultActivity"
        private const val DOUYIN_SEARCH_BULLET_ACTIVITY = "com.ss.android.ugc.aweme.bullet.SearchSynthesisBulletActivity"
        private const val DOUYIN_LIFE_POI_ACTIVITY = "com.bytedance.locallife.page.poi.LifePoiActivity"
        private val DOUYIN_SIDE_DRAWER_HINTS = listOf(
            "通知消息",
            "常用小程序",
            "常用功能",
            "更多功能",
            "钱包服务",
            "扫一扫",
            "乘车码",
            "设置"
        )
        
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

        when (getCurrentDouyinPageKind(keyword)) {
            DouyinPageKind.GROUPBUY_SEARCH_INPUT -> {
                Log.d(TAG, "Douyin search input already visible, skip home/group-buy preparation")
            }
            DouyinPageKind.GROUPBUY_HOME -> {
                Log.d(TAG, "Douyin group-buy page already visible, skip group-buy tab selection")
            }
            else -> {
                if (!prepareDouyinHomePage()) {
                    Log.w(TAG, "Douyin home page not confirmed before selecting group buy tab")
                    return false
                }

                val tabSelected = selectDouyinGroupBuyTab()
                if (!tabSelected) {
                    Log.e(TAG, "Abort Douyin search because group-buy tab could not be selected")
                    return false
                }
                Thread.sleep(1200)
            }
        }

        if (getCurrentDouyinPageKind(keyword) != DouyinPageKind.GROUPBUY_SEARCH_INPUT &&
            !waitForDouyinGroupBuyPage(DOUYIN_GROUPBUY_WAIT_TIMEOUT_MS)
        ) {
            Log.e(TAG, "Abort Douyin search because group-buy page was not confirmed after tab selection")
            return false
        }

        if (getCurrentDouyinPageKind(keyword) == DouyinPageKind.GROUPBUY_HOME) {
            stabilizeDouyinGroupBuyBeforeSearch()
        }

        val searchEntryOpened = openDouyinGroupBuySearchEntry()
        if (!searchEntryOpened) {
            Log.e(TAG, "Douyin group buy search entry not found")
            return false
        }

        if (!waitForDouyinSearchInputPage(DOUYIN_SEARCH_INPUT_WAIT_TIMEOUT_MS)) {
            Log.e(TAG, "Douyin dedicated search input page not reached")
            return false
        }

        val submitted = submitDouyinDedicatedSearch(keyword)

        if (!submitted) {
            return false
        }

        if (!waitForDouyinMerchantResultPage(keyword, DOUYIN_SEARCH_RESULT_WAIT_TIMEOUT_MS)) {
            Log.e(TAG, "Douyin merchant result page not confirmed after search submit")
            return false
        }

        return true
    }

    private fun submitDouyinDedicatedSearch(keyword: String): Boolean {
        if (!isOnDouyinSearchInputPage()) {
            Log.e(TAG, "Abort Douyin dedicated search submit: current page is not dedicated search input")
            return false
        }

        val searchBox = findDouyinDedicatedSearchBox()
        if (searchBox == null) {
            Log.e(TAG, "Douyin dedicated search box not found")
            return false
        }

        return try {
            if (!clearDouyinDedicatedSearchBox(searchBox)) {
                Log.e(TAG, "Failed to clear Douyin dedicated search box")
                return false
            }
            if (!inputDouyinDedicatedSearchText(searchBox, keyword)) {
                Log.e(TAG, "Failed to input Douyin dedicated search keyword")
                return false
            }

            val submitted = clickDouyinDedicatedSearchSubmit(searchBox)
            Log.i(TAG, "Douyin dedicated search executed: $keyword, submitted=$submitted")
            submitted
        } finally {
            searchBox.recycle()
        }
    }

    private fun findDouyinDedicatedSearchBox(): AccessibilityNodeInfo? {
        val rootNode = service.rootInActiveWindow ?: return null

        try {
            val searchBox = NodeUtils.findNodeByCondition(
                rootNode,
                condition = { node: AccessibilityNodeInfo ->
                    val viewId = node.viewIdResourceName?.lowercase().orEmpty()
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    viewId.contains("et_search_kw") &&
                        isLikelySearchInput(node) &&
                        bounds.top in 80..360 &&
                        bounds.height() in 60..180
                },
                maxDepth = SEARCH_NODE_MAX_DEPTH
            )

            if (searchBox != null) {
                recordNodeClickTrace("douyin_search_input_box_candidate", searchBox)
                return AccessibilityNodeInfo.obtain(searchBox)
            }

            return null
        } finally {
            rootNode.recycle()
        }
    }

    private fun clickDouyinDedicatedSearchSubmit(searchBox: AccessibilityNodeInfo?): Boolean {
        if (!isOnDouyinSearchInputPage()) {
            return false
        }

        val submitNode = findDouyinDedicatedSearchSubmitNode()
        try {
            if (submitNode != null) {
                val tapped = tapNodeCenterWithinRect(
                    submitNode,
                    DOUYIN_SEARCH_SUBMIT_TAP_RECT,
                    "douyin_dedicated_search_submit_bounds"
                )
                if (tapped) {
                    Log.d(TAG, "Tapped Douyin dedicated search submit button with node bounds")
                    return true
                }

                val clicked = NodeUtils.clickNode(submitNode)
                recordNodeClickTrace("douyin_dedicated_search_submit_node", submitNode, clicked)
                if (clicked) {
                    Log.d(TAG, "Clicked Douyin dedicated search submit button by node action")
                    return true
                }
            }
        } finally {
            submitNode?.recycle()
        }

        val tapped = tapRect(
            DOUYIN_SEARCH_SUBMIT_TAP_RECT,
            "douyin_dedicated_search_submit_rect",
            horizontalBias = 0.54f,
            verticalBias = 0.52f
        )
        if (tapped) {
            Log.d(TAG, "Tapped Douyin dedicated search submit button with rect tap")
            return true
        }

        if (searchBox != null) {
            val imeResult = searchBox.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            )
            if (imeResult) {
                Log.d(TAG, "Submitted Douyin dedicated search with IME action")
                return true
            }
        }

        return false
    }

    private fun clearDouyinDedicatedSearchBox(searchBox: AccessibilityNodeInfo): Boolean {
        if (!isOnDouyinSearchInputPage()) {
            return false
        }

        return trySetDouyinDedicatedSearchText(searchBox, "")
    }

    private fun inputDouyinDedicatedSearchText(searchBox: AccessibilityNodeInfo, text: String): Boolean {
        if (!isOnDouyinSearchInputPage()) {
            return false
        }

        return trySetDouyinDedicatedSearchText(searchBox, text)
    }

    private fun trySetDouyinDedicatedSearchText(
        searchBox: AccessibilityNodeInfo,
        text: String
    ): Boolean {
        if (trySetText(searchBox, text)) {
            Log.d(TAG, "Set Douyin dedicated search text with direct setText: $text")
            return true
        }

        val refreshedSearchBox = findDouyinDedicatedSearchBox()
        try {
            if (refreshedSearchBox != null && trySetText(refreshedSearchBox, text)) {
                Log.d(TAG, "Set Douyin dedicated search text with refreshed setText: $text")
                return true
            }
        } finally {
            refreshedSearchBox?.recycle()
        }

        return false
    }

    private fun findDouyinDedicatedSearchSubmitNode(): AccessibilityNodeInfo? {
        val rootNode = service.rootInActiveWindow ?: return null

        try {
            val submitNode = NodeUtils.findNodeByCondition(
                rootNode,
                condition = { node: AccessibilityNodeInfo ->
                    val viewId = node.viewIdResourceName?.lowercase().orEmpty()
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    val nodeText = getComparableNodeText(node)
                    !isLikelySearchInput(node) &&
                        isRectUsable(bounds) &&
                        overlaps(bounds, DOUYIN_SEARCH_SUBMIT_TAP_RECT) &&
                        bounds.top in 80..340 &&
                        (
                            nodeText.contains("搜索", ignoreCase = true) ||
                                node.contentDescription?.toString().orEmpty().contains("搜索", ignoreCase = true) ||
                                viewId.contains("search")
                            )
                },
                maxDepth = SEARCH_NODE_MAX_DEPTH
            )

            return submitNode?.let { AccessibilityNodeInfo.obtain(it) }
        } finally {
            rootNode.recycle()
        }
    }

    fun openMerchantResult(merchantName: String, maxScrollRounds: Int = 3): Boolean {
        appendTapTraceLine("=== open_merchant_result:$merchantName @${System.currentTimeMillis()} ===")

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

            if (settleRound < 2) {
                Thread.sleep(1200)
            }
        }

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

    fun expandVisibleDouyinMerchantSections(maxClicks: Int = 2): Int {
        if (maxClicks <= 0) {
            return 0
        }

        if (hasVisibleDouyinMerchantTailBoundary()) {
            Log.d(TAG, "Skip expanding Douyin merchant sections because tail boundary is already visible")
            return 0
        }

        var expandedCount = clickAnyText(
            targetTexts = listOf("展开更多", "展开全部"),
            exactMatch = false,
            maxClicks = maxClicks
        )
        if (expandedCount >= maxClicks) {
            return expandedCount
        }

        repeat(maxClicks - expandedCount) { round ->
            if (hasVisibleDouyinMerchantTailBoundary()) {
                return expandedCount
            }
            val label = "douyin_expand_band_r${expandedCount + round}"
            if (!tapDouyinMerchantExpandBand(label)) {
                return expandedCount
            }
            expandedCount++
        }

        return expandedCount
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
            val rootNode = service.rootInActiveWindow
            if (rootNode != null) {
                try {
                    val snapshot = douyinPageClassifier.classify(rootNode)
                    when (snapshot.kind) {
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
                    val snapshot = douyinPageClassifier.classify(currentRoot)
                    val currentWindowClassName = getCurrentDouyinWindowClassName()
                    when {
                        isDouyinSearchDriftWindow(currentWindowClassName) -> {
                            val backed = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                            Log.d(
                                TAG,
                                "Back from Douyin drift search page to reach home: attempt=${attempt + 1}, class=$currentWindowClassName, success=$backed"
                            )
                            Thread.sleep(1200)
                            return@repeat
                        }
                        isDouyinSideDrawerPage(currentRoot) -> {
                            val backed = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                            Log.d(TAG, "Back from Douyin side drawer to reach home: attempt=${attempt + 1}, success=$backed")
                            Thread.sleep(1200)
                            return@repeat
                        }
                        snapshot.kind in setOf(
                            DouyinPageKind.MERCHANT_HOME,
                            DouyinPageKind.MERCHANT_TAIL,
                            DouyinPageKind.MERCHANT_RESULT_LIST,
                            DouyinPageKind.RECOMMENDATION
                        ) -> {
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

            if (isOnDouyinSearchInputPage()) {
                val tapped = tapRect(
                    DOUYIN_SEARCH_SUBMIT_TAP_RECT,
                    "douyin_search_submit_rect",
                    horizontalBias = 0.54f,
                    verticalBias = 0.52f
                )
                if (tapped) {
                    Log.d(TAG, "Tapped Douyin search submit button with rect tap")
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
                Log.d(TAG, "Douyin group buy home already visible")
                return true
            }
            if (pageSnapshot.signals.hasSelectedGroupBuyTab) {
                Log.d(TAG, "Douyin group buy channel looks selected but group-buy home is not confirmed yet")
            }

            if (!isSafeToTapDouyinGroupBuyTab(rootNode)) {
                Log.w(TAG, "Skip Douyin group buy tab tap: current page is not a safe home/group-buy page")
                return false
            }

            val tabNode = NodeUtils.findNodeByCondition(
                rootNode,
                condition = { node: AccessibilityNodeInfo ->
                    val nodeText = getComparableNodeText(node)
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    nodeText.contains("团购", ignoreCase = true) &&
                        isRectUsable(bounds) &&
                        overlaps(bounds, DOUYIN_GROUPBUY_TAB_TAP_RECT)
                },
                maxDepth = 24
            )

            if (tabNode != null) {
                try {
                    val clicked = NodeUtils.clickNode(tabNode)
                    recordNodeClickTrace("douyin_groupbuy_tab_node", tabNode, clicked)
                    Log.d(TAG, "Selected Douyin group buy tab: $clicked")
                    if (clicked) {
                        return true
                    }

                    val tapped = tapNodeCenterWithinRect(
                        tabNode,
                        DOUYIN_GROUPBUY_TAB_TAP_RECT,
                        "douyin_groupbuy_tab_bounds"
                    )
                    if (tapped) {
                        return true
                    }
                } finally {
                    tabNode.recycle()
                }
            }

            val tapped = tapRect(
                DOUYIN_GROUPBUY_TAB_TAP_RECT,
                "douyin_groupbuy_tab_rect",
                horizontalBias = 0.60f,
                verticalBias = 0.52f
            )
            if (tapped) {
                Log.d(TAG, "Selected Douyin group buy tab with rect tap")
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
        val currentRoot = service.rootInActiveWindow
        if (currentRoot != null) {
            try {
                if (isDouyinSearchDriftWindow(getCurrentDouyinWindowClassName()) || isDouyinSideDrawerPage(currentRoot)) {
                    Log.w(TAG, "Skip Douyin home bottom tab tap on drift-search page or side drawer")
                    return false
                }
            } finally {
                currentRoot.recycle()
            }
        }

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

    private fun stabilizeDouyinGroupBuyBeforeSearch() {
        Log.i(
            TAG,
            "Stabilize Douyin group-buy page before search: down=${DOUYIN_GROUPBUY_PRE_SEARCH_SCROLL_ROUNDS}, up=${DOUYIN_GROUPBUY_PRE_SEARCH_SCROLL_ROUNDS}, wait=${DOUYIN_GROUPBUY_LIVE_REMINDER_WAIT_MS}ms"
        )

        repeat(DOUYIN_GROUPBUY_PRE_SEARCH_SCROLL_ROUNDS) { round ->
            val scrolled = scrollCurrentPage(forward = true)
            Log.d(TAG, "Douyin pre-search downward settle scroll=${round + 1}, success=$scrolled")
            if (!scrolled) {
                return@repeat
            }
            Thread.sleep(DOUYIN_GROUPBUY_PRE_SEARCH_SCROLL_SETTLE_MS)
        }

        repeat(DOUYIN_GROUPBUY_PRE_SEARCH_SCROLL_ROUNDS) { round ->
            val scrolled = scrollCurrentPage(forward = false)
            Log.d(TAG, "Douyin pre-search upward restore scroll=${round + 1}, success=$scrolled")
            if (!scrolled) {
                return@repeat
            }
            Thread.sleep(DOUYIN_GROUPBUY_PRE_SEARCH_SCROLL_SETTLE_MS)
        }

        // Let transient live-reminder overlays dismiss themselves before opening search.
        Thread.sleep(DOUYIN_GROUPBUY_LIVE_REMINDER_WAIT_MS)
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

            val useInlineKeywordEntry =
                pageSnapshot.signals.hasSelectedGroupBuyTab &&
                    pageSnapshot.signals.hasTopSearchButton

            if (useInlineKeywordEntry) {
                val inlineEntryNode = findDouyinInlineKeywordEntryNode(rootNode)
                try {
                    if (inlineEntryNode == null) {
                        Log.w(TAG, "Abort inline Douyin keyword entry tap because no stable keyword node was resolved")
                        return false
                    }

                    recordNodeClickTrace("douyin_groupbuy_inline_keyword_candidate", inlineEntryNode)
                    val tapped = tapNodeCenterWithinRect(
                        inlineEntryNode,
                        DOUYIN_GROUPBUY_INLINE_KEYWORD_ENTRY_TAP_RECT,
                        "douyin_groupbuy_inline_keyword_bounds"
                    )
                    if (tapped) {
                        Log.d(TAG, "Opened Douyin group buy search entry with inline keyword node bounds")
                        return true
                    }
                } finally {
                    inlineEntryNode?.recycle()
                }

                Log.w(TAG, "Abort inline Douyin keyword entry tap because node-bounds tap failed")
                return false
            }

            val entryTapRect = resolveDouyinGroupBuySearchEntryTapRect(rootNode)
            val entryNode = findDouyinGroupBuySearchEntryNode(rootNode)
            try {
                if (entryNode != null) {
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
                }
            } finally {
                entryNode?.recycle()
            }

            val entryTapped = tapRect(
                entryTapRect,
                "douyin_groupbuy_search_entry_rect",
                horizontalBias = 0.50f,
                verticalBias = 0.50f
            )
            if (entryTapped) {
                Log.d(TAG, "Opened Douyin group buy search entry with rect tap")
                return true
            }

            val tapped = tapRect(
                entryTapRect,
                "douyin_groupbuy_search_entry_rect_fallback",
                horizontalBias = when {
                    pageSnapshot.signals.hasSelectedGroupBuyTab -> 0.46f
                    else -> 0.50f
                },
                verticalBias = 0.52f
            )
            if (tapped) {
                Log.d(TAG, "Opened Douyin group buy search entry with rect fallback")
            }
            return tapped
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
                    if (isLikelyDouyinMerchantResultPage(rootNode, merchantName)) {
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
        val snapshot = douyinPageClassifier.classify(rootNode, merchantName)
        if (snapshot.kind == DouyinPageKind.MERCHANT_RESULT_LIST) {
            return true
        }

        if (!isCurrentDouyinSearchResultActivity()) {
            return false
        }

        val merchantNode = findMerchantResultNode(rootNode, merchantName)
        merchantNode?.recycle()
        return merchantNode != null
    }

    private fun findMerchantResultNode(merchantName: String): AccessibilityNodeInfo? {
        val rootNode = service.rootInActiveWindow ?: return null

        try {
            return findMerchantResultNode(rootNode, merchantName)
        } finally {
            rootNode.recycle()
        }
    }

    private fun findMerchantResultNode(
        rootNode: AccessibilityNodeInfo,
        merchantName: String
    ): AccessibilityNodeInfo? {
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

                val bounds = Rect().also { node.getBoundsInScreen(it) }
                val text = normalizeText(getComparableNodeText(node))
                text.isNotBlank() &&
                    bounds.top in 360..1800 &&
                    (
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
    }

    private fun isCurrentDouyinSearchResultActivity(): Boolean {
        val frameworkService = service as? FrameworkAccessibilityService ?: return false
        return frameworkService.getCurrentWindowClassName() == DOUYIN_SEARCH_ACTIVITY
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

        val clicked = clickNodeWithTrace("merchant_result_accessibility_click_r$round", merchantNode)
        if (clicked && waitForMerchantDetailPage(merchantName, MERCHANT_RESULT_OPEN_TIMEOUT_MS)) {
            Log.i(TAG, "Merchant result opened by accessibility click: name=$merchantName, round=$round")
            return true
        }

        Log.w(
            TAG,
            "Merchant result did not open detail page: name=$merchantName, round=$round, tappedBand=$tappedBand, clicked=$clicked"
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

        val tapped = tapRect(
            DOUYIN_MERCHANT_ENTRY_BAND_RECT,
            "merchant_result_preset_band_r$round",
            horizontalBias = 0.50f,
            verticalBias = 0.20f
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
            val currentWindowClassName = (service as? FrameworkAccessibilityService)
                ?.getCurrentWindowClassName()
                .orEmpty()
            if (currentWindowClassName.contains(DOUYIN_LIFE_POI_ACTIVITY, ignoreCase = true)) {
                return true
            }

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

    fun isOnDouyinMerchantResultPage(merchantName: String): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false

        return try {
            isLikelyDouyinMerchantResultPage(rootNode, merchantName)
        } finally {
            rootNode.recycle()
        }
    }

    internal fun getCurrentDouyinPageKindForMerchant(merchantName: String): DouyinPageKind {
        return getCurrentDouyinPageKind(merchantName)
    }

    fun hasVisibleDouyinMerchantTailBoundary(): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false

        return try {
            val snapshot = douyinPageClassifier.classify(rootNode)
            if (snapshot.kind == DouyinPageKind.RECOMMENDATION ||
                snapshot.kind == DouyinPageKind.MERCHANT_TAIL
            ) {
                return true
            }

            findDouyinMerchantActionBand(rootNode)?.type == DouyinMerchantActionBandType.TAIL_COLLAPSE
        } finally {
            rootNode.recycle()
        }
    }

    fun ensureMerchantGroupBuyTab(merchantName: String, timeoutMs: Long = 2_500L): Boolean {
        val currentWindowClassName = (service as? FrameworkAccessibilityService)
            ?.getCurrentWindowClassName()
            .orEmpty()
        if (!currentWindowClassName.contains(DOUYIN_LIFE_POI_ACTIVITY, ignoreCase = true)) {
            return false
        }

        val rootNode = service.rootInActiveWindow ?: return false
        try {
            val snapshot = douyinPageClassifier.classify(rootNode, merchantName)
            if (snapshot.kind == DouyinPageKind.MERCHANT_HOME || snapshot.kind == DouyinPageKind.MERCHANT_TAIL) {
                return true
            }

            val groupBuyTabNode = NodeUtils.findNodeByCondition(
                rootNode,
                condition = { node ->
                    val text = getComparableNodeText(node)
                    if (!text.contains("团购", ignoreCase = true)) {
                        false
                    } else {
                        val bounds = Rect().also { node.getBoundsInScreen(it) }
                        isRectUsable(bounds) &&
                            overlaps(bounds, DOUYIN_MERCHANT_GROUPBUY_TAB_TAP_RECT)
                    }
                },
                maxDepth = 24
            )

            if (groupBuyTabNode != null) {
                try {
                    val clicked = NodeUtils.clickNode(groupBuyTabNode)
                    recordNodeClickTrace("douyin_merchant_groupbuy_tab_node", groupBuyTabNode, clicked)
                    if (clicked) {
                        Thread.sleep(1_000L)
                        if (waitForMerchantHomepageAnchors(merchantName, timeoutMs)) {
                            return true
                        }
                    }

                    val tapped = tapNodeCenterWithinRect(
                        groupBuyTabNode,
                        DOUYIN_MERCHANT_GROUPBUY_TAB_TAP_RECT,
                        "douyin_merchant_groupbuy_tab_bounds"
                    )
                    if (tapped) {
                        Thread.sleep(1_000L)
                        if (waitForMerchantHomepageAnchors(merchantName, timeoutMs)) {
                            return true
                        }
                    }
                } finally {
                    groupBuyTabNode.recycle()
                }
            }
        } finally {
            rootNode.recycle()
        }

        val presetTapped = tapScreen(
            DOUYIN_MERCHANT_GROUPBUY_TAB_TAP_X,
            DOUYIN_MERCHANT_GROUPBUY_TAB_TAP_Y,
            "douyin_merchant_groupbuy_tab_point_fallback"
        )
        if (!presetTapped && !tapRect(
                DOUYIN_MERCHANT_GROUPBUY_TAB_TAP_RECT,
                "douyin_merchant_groupbuy_tab_rect",
                horizontalBias = 0.42f,
                verticalBias = 0.52f
            )
        ) {
            return false
        }

        Thread.sleep(1_000L)
        return waitForMerchantHomepageAnchors(merchantName, timeoutMs)
    }

    fun dismissDouyinMerchantOverlay(): Boolean {
        val dismissed = clickAnyText(
            targetTexts = MERCHANT_OVERLAY_CLOSE_HINTS,
            exactMatch = false,
            maxClicks = 1
        ) > 0
        if (dismissed) {
            Log.i(TAG, "Dismissed Douyin merchant overlay by close hint")
        }
        return dismissed
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
        val currentWindowClassName = (service as? FrameworkAccessibilityService)
            ?.getCurrentWindowClassName()
            .orEmpty()
        if (currentWindowClassName.contains(DOUYIN_LIFE_POI_ACTIVITY, ignoreCase = true)) {
            return true
        }

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
        if (isDouyinSearchDriftWindow(getCurrentDouyinWindowClassName()) || isDouyinSideDrawerPage(rootNode)) {
            return false
        }
        return when (douyinPageClassifier.classify(rootNode).kind) {
            DouyinPageKind.HOME_FEED,
            DouyinPageKind.GROUPBUY_HOME -> true
            else -> false
        }
    }

    private fun getCurrentDouyinWindowClassName(): String {
        return (service as? FrameworkAccessibilityService)
            ?.getCurrentWindowClassName()
            .orEmpty()
    }

    private fun isDouyinSearchDriftWindow(windowClassName: String): Boolean {
        return windowClassName.contains(DOUYIN_SEARCH_BULLET_ACTIVITY, ignoreCase = true) ||
            windowClassName.contains(DOUYIN_SEARCH_ACTIVITY, ignoreCase = true)
    }

    private fun isDouyinSideDrawerPage(rootNode: AccessibilityNodeInfo): Boolean {
        val pageText = NodeUtils.getAllNodeText(
            rootNode,
            maxDepth = 20,
            maxNodes = 420,
            maxTextLength = 7000
        )
        val hintCount = DOUYIN_SIDE_DRAWER_HINTS.count { hint ->
            pageText.contains(hint, ignoreCase = true)
        }
        return hintCount >= 3
    }

    private fun findDouyinInlineKeywordEntryNode(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = NodeUtils.findNodesByCondition(
            rootNode,
            condition = { node: AccessibilityNodeInfo ->
                val viewId = node.viewIdResourceName?.lowercase().orEmpty()
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                val nodeText = getComparableNodeText(node)
                !isLikelySearchInput(node) &&
                    isRectUsable(bounds) &&
                    overlaps(bounds, DOUYIN_GROUPBUY_INLINE_KEYWORD_ENTRY_TAP_RECT) &&
                    bounds.left >= DOUYIN_GROUPBUY_INLINE_KEYWORD_ENTRY_TAP_RECT.left - 40 &&
                    bounds.right <= DOUYIN_GROUPBUY_INLINE_KEYWORD_ENTRY_TAP_RECT.right + 40 &&
                    bounds.top in 280..460 &&
                    (
                        viewId.contains("et_search_kw") ||
                            DOUYIN_GROUPBUY_SEARCH_ENTRY_HINTS.any { hint ->
                                nodeText.contains(hint, ignoreCase = true)
                            }
                        )
            },
            maxDepth = 28
        )

        try {
            val bestCandidate = candidates.maxByOrNull { candidate ->
                val bounds = Rect().also { candidate.getBoundsInScreen(it) }
                val viewId = candidate.viewIdResourceName?.lowercase().orEmpty()
                val text = getComparableNodeText(candidate)

                var score = 0
                if (viewId.contains("et_search_kw")) {
                    score += 280
                }
                if (text.isNotBlank()) {
                    score += 120
                }
                if (DOUYIN_GROUPBUY_SEARCH_ENTRY_HINTS.any { hint -> text.contains(hint, ignoreCase = true) }) {
                    score += 120
                }
                if (bounds.left in 220..360) {
                    score += 180
                }
                if (bounds.right in 820..980) {
                    score += 180
                }
                if (bounds.width() in 420..760) {
                    score += 260
                } else if (bounds.width() in 180..420) {
                    score += 120
                } else {
                    score -= 140
                }
                if (bounds.height() in 80..160) {
                    score += 180
                } else if (bounds.height() in 36..100) {
                    score += 100
                } else {
                    score -= 80
                }
                score -= bounds.width() * bounds.height() / 500
                score
            } ?: return null

            return AccessibilityNodeInfo.obtain(bestCandidate)
        } finally {
            NodeUtils.recycleNodes(candidates)
        }
    }

    private fun findDouyinGroupBuySearchEntryNode(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return NodeUtils.findNodeByCondition(
            rootNode,
            condition = { node: AccessibilityNodeInfo ->
                val viewId = node.viewIdResourceName?.lowercase().orEmpty()
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                val nodeText = getComparableNodeText(node)
                !isLikelySearchInput(node) &&
                    isRectUsable(bounds) &&
                    overlaps(bounds, DOUYIN_GROUPBUY_SEARCH_ENTRY_TAP_RECT) &&
                    (
                        viewId.contains("et_search_kw") ||
                            DOUYIN_GROUPBUY_SEARCH_ENTRY_HINTS.any { hint ->
                                nodeText.contains(hint, ignoreCase = true)
                            }
                        )
            },
            maxDepth = 24
        )?.let { AccessibilityNodeInfo.obtain(it) }
    }

    private fun resolveDouyinGroupBuySearchEntryTapRect(rootNode: AccessibilityNodeInfo): Rect {
        val hasInlineKeywordEntry = NodeUtils.findNodeByCondition(
            rootNode,
            condition = { node ->
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                val text = getComparableNodeText(node)
                isRectUsable(bounds) &&
                    overlaps(bounds, DOUYIN_GROUPBUY_INLINE_SEARCH_BAR_RECT) &&
                    !isLikelySearchInput(node) &&
                    (
                        node.viewIdResourceName?.lowercase()?.contains("et_search_kw") == true ||
                            text.contains("搜索", ignoreCase = true) ||
                            text.contains("郑州", ignoreCase = true) ||
                            text.contains("美莱", ignoreCase = true)
                        )
            },
            maxDepth = 24
        ) != null

        return if (hasInlineKeywordEntry) {
            DOUYIN_GROUPBUY_INLINE_KEYWORD_ENTRY_TAP_RECT
        } else {
            DOUYIN_GROUPBUY_SEARCH_ENTRY_TAP_RECT
        }
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
        return findMerchantBottomActionBarRect(rootNode) != null
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

    private fun tapDouyinMerchantExpandBand(label: String): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false
        val beforeCardCount: Int
        val actionBand: DouyinMerchantActionBand

        try {
            val snapshot = douyinPageClassifier.classify(rootNode)
            if (snapshot.kind != DouyinPageKind.MERCHANT_HOME &&
                snapshot.kind != DouyinPageKind.MERCHANT_TAIL
            ) {
                return false
            }

            beforeCardCount = countVisibleDouyinMerchantCards(rootNode)
            actionBand = findDouyinMerchantActionBand(rootNode) ?: return false
            if (actionBand.type != DouyinMerchantActionBandType.EXPAND) {
                Log.d(
                    TAG,
                    "Skip Douyin merchant band tap because current action band is tail: offset=${actionBand.bottomBarOffset}, band=${actionBand.bounds.flattenToString()}"
                )
                return false
            }
        } finally {
            rootNode.recycle()
        }

        DOUYIN_EXPAND_TAP_POINTS.forEachIndexed { index, (horizontalBias, verticalBias) ->
            val tapped = tapRect(
                actionBand.bounds,
                "${label}_a$index",
                horizontalBias = horizontalBias,
                verticalBias = verticalBias
            )
            if (!tapped) {
                return@forEachIndexed
            }

            Thread.sleep(DOUYIN_EXPAND_ACTION_VALIDATION_DELAY_MS)
            if (didDouyinExpandBandTakeEffect(beforeCardCount, actionBand)) {
                Log.i(
                    TAG,
                    "Expanded Douyin merchant section by band tap: label=$label, attempt=$index, offset=${actionBand.bottomBarOffset}, band=${actionBand.bounds.flattenToString()}"
                )
                return true
            }
        }

        return false
    }

    private fun didDouyinExpandBandTakeEffect(
        beforeCardCount: Int,
        beforeBand: DouyinMerchantActionBand
    ): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false

        return try {
            val snapshot = douyinPageClassifier.classify(rootNode)
            if (snapshot.kind == DouyinPageKind.MERCHANT_TAIL) {
                return true
            }
            if (snapshot.kind == DouyinPageKind.RECOMMENDATION) {
                return false
            }

            val afterCardCount = countVisibleDouyinMerchantCards(rootNode)
            if (afterCardCount > beforeCardCount) {
                return true
            }

            val afterBand = findDouyinMerchantActionBand(rootNode)
            if (afterBand == null) {
                return true
            }
            if (afterBand.type == DouyinMerchantActionBandType.TAIL_COLLAPSE) {
                return true
            }

            val movedEnough =
                abs(afterBand.bounds.top - beforeBand.bounds.top) > 120 ||
                    abs(afterBand.bounds.bottom - beforeBand.bounds.bottom) > 120 ||
                    abs(afterBand.bottomBarOffset - beforeBand.bottomBarOffset) > 120
            movedEnough && afterCardCount >= beforeCardCount
        } finally {
            rootNode.recycle()
        }
    }

    private fun findDouyinMerchantActionBand(rootNode: AccessibilityNodeInfo): DouyinMerchantActionBand? {
        val cardBounds = findVisibleDouyinMerchantCardBounds(rootNode)
        if (cardBounds.isEmpty()) {
            return null
        }

        val lastCardBottom = cardBounds.maxOf { it.bottom }
        val bottomBarRect = findMerchantBottomActionBarRect(rootNode) ?: return null
        val metrics = service.resources.displayMetrics

        val bandNodes = NodeUtils.findNodesByCondition(
            rootNode,
            condition = { node ->
                val text = getComparableNodeText(node)
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                text.isBlank() &&
                    isRectUsable(bounds) &&
                    bounds.top >= lastCardBottom - 40 &&
                    bounds.top in 760 until bottomBarRect.top &&
                    bounds.height() in DOUYIN_EXPAND_BAND_MIN_HEIGHT..DOUYIN_EXPAND_BAND_MAX_HEIGHT &&
                    bounds.width() >= metrics.widthPixels - 360 &&
                    bounds.left <= 320 &&
                    bounds.bottom <= bottomBarRect.top - DOUYIN_ACTION_BAND_BOTTOM_BAR_CLEARANCE &&
                    !node.isScrollable
            },
            maxDepth = 30
        )

        try {
            return bandNodes
                .map { node -> Rect().also { node.getBoundsInScreen(it) } }
                .distinctBy { it.flattenToString() }
                .filter { bounds ->
                    bounds.top >= lastCardBottom - 40 &&
                        bounds.bottom > lastCardBottom + DOUYIN_EXPAND_CARD_GAP_MIN
                }
                .sortedBy { bounds -> bounds.top }
                .mapNotNull { bounds ->
                    classifyDouyinMerchantActionBand(bounds, bottomBarRect.top)
                }
                .firstOrNull()
        } finally {
            NodeUtils.recycleNodes(bandNodes)
        }
    }

    private fun classifyDouyinMerchantActionBand(
        bounds: Rect,
        bottomBarTop: Int
    ): DouyinMerchantActionBand? {
        val offset = bottomBarTop - bounds.centerY()
        val type = when (offset) {
            in DOUYIN_EXPAND_BAND_MIN_BOTTOM_BAR_OFFSET..DOUYIN_EXPAND_BAND_MAX_BOTTOM_BAR_OFFSET ->
                DouyinMerchantActionBandType.EXPAND
            in DOUYIN_TAIL_BAND_MIN_BOTTOM_BAR_OFFSET..DOUYIN_TAIL_BAND_MAX_BOTTOM_BAR_OFFSET ->
                DouyinMerchantActionBandType.TAIL_COLLAPSE
            else -> null
        } ?: return null

        return DouyinMerchantActionBand(
            bounds = Rect(bounds),
            type = type,
            bottomBarOffset = offset
        )
    }

    private fun findVisibleDouyinMerchantCardBounds(rootNode: AccessibilityNodeInfo): List<Rect> {
        val cardNodes = NodeUtils.findNodesByCondition(
            rootNode,
            condition = { node ->
                val text = getComparableNodeText(node)
                if (text.isBlank()) {
                    return@findNodesByCondition false
                }

                val bounds = Rect().also { node.getBoundsInScreen(it) }
                isRectUsable(bounds) &&
                    bounds.top in 320..2600 &&
                    bounds.height() in 120..420 &&
                    bounds.width() >= 900 &&
                    bounds.left <= 120 &&
                    MERCHANT_GROUPBUY_CARD_SIGNAL_HINTS.any { hint ->
                        text.contains(hint, ignoreCase = true)
                    } &&
                    !MERCHANT_OVERLAY_CLOSE_HINTS.any { hint ->
                        text.contains(hint, ignoreCase = true)
                    }
            },
            maxDepth = 30
        )

        return try {
            cardNodes
                .map { node -> Rect().also { node.getBoundsInScreen(it) } }
                .distinctBy { it.flattenToString() }
                .sortedBy { it.top }
        } finally {
            NodeUtils.recycleNodes(cardNodes)
        }
    }

    private fun countVisibleDouyinMerchantCards(rootNode: AccessibilityNodeInfo): Int {
        return findVisibleDouyinMerchantCardBounds(rootNode).size
    }

    private fun findMerchantBottomActionBarRect(rootNode: AccessibilityNodeInfo): Rect? {
        val metrics = service.resources.displayMetrics
        val minTop = (metrics.heightPixels * 0.88f).toInt()
        val minWidth = (metrics.widthPixels * 0.98f).toInt()

        val bottomBarNodes = NodeUtils.findNodesByCondition(
            rootNode,
            condition = { node ->
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                isRectUsable(bounds) &&
                    bounds.top >= minTop &&
                    bounds.left <= 10 &&
                    bounds.right >= metrics.widthPixels - 10 &&
                    bounds.width() >= minWidth &&
                    bounds.height() in 140..260 &&
                    !node.isScrollable
            },
            maxDepth = 18
        )

        return try {
            bottomBarNodes
                .map { node -> Rect().also { node.getBoundsInScreen(it) } }
                .maxByOrNull { bounds -> bounds.top }
        } finally {
            NodeUtils.recycleNodes(bottomBarNodes)
        }
    }

    /**
     * 点击坐标
     */
    private fun click(x: Int, y: Int) {
        tapScreen(x, y, "click")
    }

    private fun tapRect(
        rect: Rect,
        label: String,
        horizontalBias: Float = 0.5f,
        verticalBias: Float = 0.5f
    ): Boolean {
        if (!isRectUsable(rect)) {
            return false
        }

        val x = (rect.left + rect.width() * horizontalBias)
            .roundToInt()
            .coerceIn(rect.left + 1, rect.right - 1)
        val y = (rect.top + rect.height() * verticalBias)
            .roundToInt()
            .coerceIn(rect.top + 1, rect.bottom - 1)
        return tapScreen(x, y, label)
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

    private fun tapNodeCenterWithinRect(
        node: AccessibilityNodeInfo,
        targetRect: Rect,
        label: String
    ): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!isRectUsable(bounds)) {
            return false
        }

        val clampedLeft = maxOf(bounds.left, targetRect.left)
        val clampedTop = maxOf(bounds.top, targetRect.top)
        val clampedRight = minOf(bounds.right, targetRect.right)
        val clampedBottom = minOf(bounds.bottom, targetRect.bottom)
        if (clampedRight <= clampedLeft || clampedBottom <= clampedTop) {
            return false
        }

        return tapRect(
            Rect(clampedLeft, clampedTop, clampedRight, clampedBottom),
            label
        )
    }

    private fun isRectUsable(rect: Rect): Boolean {
        return !rect.isEmpty && rect.width() > 1 && rect.height() > 1
    }

    private fun overlaps(bounds: Rect, target: Rect): Boolean {
        if (!isRectUsable(bounds) || !isRectUsable(target)) {
            return false
        }

        val overlap = Rect(bounds)
        if (!overlap.intersect(target)) {
            return false
        }

        return overlap.width() >= minOf(bounds.width(), target.width()) / 4 &&
            overlap.height() >= minOf(bounds.height(), target.height()) / 4
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
