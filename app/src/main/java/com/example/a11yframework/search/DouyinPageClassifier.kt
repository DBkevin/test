package com.example.a11yframework.search

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.a11yframework.core.FrameworkAccessibilityService
import com.example.a11yframework.utils.NodeUtils

internal enum class DouyinPageKind {
    UNKNOWN,
    HOME_FEED,
    GROUPBUY_HOME,
    GROUPBUY_SEARCH_INPUT,
    MERCHANT_RESULT_LIST,
    MERCHANT_HOME,
    MERCHANT_TAIL,
    RECOMMENDATION
}

internal data class DouyinPageSignals(
    val pageText: String = "",
    val currentWindowClassName: String = "",
    val hasSelectedGroupBuyTab: Boolean = false,
    val hasSelectedRecommendationTab: Boolean = false,
    val hasTopSearchButton: Boolean = false,
    val hasSearchEntryNode: Boolean = false,
    val hasSearchSignal: Boolean = false,
    val hasSearchInput: Boolean = false,
    val hasSearchSubmitButton: Boolean = false,
    val hasSearchHistorySignal: Boolean = false,
    val hasSearchSuggestionSignal: Boolean = false,
    val hasLocationSignal: Boolean = false,
    val hasBottomHomeTab: Boolean = false,
    val hasGroupBuyKeywordCluster: Boolean = false,
    val hasMerchantNodeForTarget: Boolean = false,
    val hasMerchantResultSignals: Boolean = false,
    val hasSearchResultTabCluster: Boolean = false,
    val hasMerchantHeaderAnchor: Boolean = false,
    val hasMerchantBottomActionBar: Boolean = false,
    val hasMerchantTailSignal: Boolean = false,
    val hasMerchantCommerceSignal: Boolean = false,
    val hasDistanceRecommendationSignal: Boolean = false,
    val hasRecommendationSignal: Boolean = false
)

internal data class DouyinPageSnapshot(
    val kind: DouyinPageKind,
    val signals: DouyinPageSignals
)

internal class DouyinPageClassifier(
    private val service: AccessibilityService
) {

    companion object {
        private val GROUPBUY_PAGE_KEYWORDS = listOf("附近好店", "美食", "休闲娱乐", "景点/周边游", "酒店民宿", "丽人")
        private val GROUPBUY_SEARCH_ENTRY_HINTS = listOf("美莱团购", "郑州", "搜索")
        private val MERCHANT_RESULT_CONTEXT_HINTS = listOf("评价", "回头客", "km", "m", "/人", "人均", "价格优惠")
        private val MERCHANT_HOME_TOP_HINTS = listOf("关注", "回头客", "无隐形消费", "详情", "在线咨询", "电话")
        private val MERCHANT_TAIL_SECTION_HINTS = listOf("展开更多", "展开全部", "收起", "预约到店送好礼", "预约到店专属礼", "用户评价")
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
        private val RECOMMENDATION_HINTS = listOf("你可能感兴趣的地点", "你可能感兴趣", "猜你喜欢", "发现同城")
        private val BOTTOM_ACTION_BAR_HINTS = listOf("医疗美容", "订单", "预约有礼", "在线咨询")
        private val SEARCH_HISTORY_HINTS = listOf("历史记录")
        private val SEARCH_SUGGESTION_HINTS = listOf("猜你想搜", "换一换", "语音搜索")
        private const val DOUYIN_LIFE_POI_ACTIVITY = "com.bytedance.locallife.page.poi.LifePoiActivity"
        private const val DOUYIN_SEARCH_RESULT_ACTIVITY =
            "com.ss.android.ugc.aweme.search.activity.SearchResultWithAssignUiModePoiLifeActivity"
        private val DISTANCE_HINT_REGEX = Regex("""\d+(?:\.\d+)?\s*(?:km|m)""", RegexOption.IGNORE_CASE)

        internal fun hasStrongGroupBuyHomeSignals(signals: DouyinPageSignals): Boolean {
            val hasSearchAnchor =
                signals.hasTopSearchButton || signals.hasSearchSignal || signals.hasSearchEntryNode
            val hasGroupBuyHomeContext =
                signals.hasBottomHomeTab ||
                    signals.hasGroupBuyKeywordCluster ||
                    signals.hasSearchEntryNode
            return signals.hasSelectedGroupBuyTab &&
                hasSearchAnchor &&
                hasGroupBuyHomeContext &&
                !signals.hasMerchantBottomActionBar
        }

        internal fun resolveKind(signals: DouyinPageSignals): DouyinPageKind {
            if (signals.hasSearchInput &&
                signals.hasSearchSubmitButton &&
                !signals.currentWindowClassName.contains(DOUYIN_LIFE_POI_ACTIVITY, ignoreCase = true) &&
                !signals.hasMerchantBottomActionBar &&
                !signals.hasMerchantCommerceSignal &&
                (signals.hasSearchHistorySignal || signals.hasSearchSuggestionSignal)
            ) {
                return DouyinPageKind.GROUPBUY_SEARCH_INPUT
            }
            if (signals.hasBottomHomeTab &&
                !signals.hasSelectedGroupBuyTab &&
                !signals.hasMerchantBottomActionBar &&
                !signals.hasMerchantCommerceSignal &&
                (signals.hasSelectedRecommendationTab || signals.hasTopSearchButton)
            ) {
                return DouyinPageKind.HOME_FEED
            }
            if (hasStrongGroupBuyHomeSignals(signals) && !signals.hasRecommendationSignal) {
                return DouyinPageKind.GROUPBUY_HOME
            }
            if (signals.hasRecommendationSignal) {
                return DouyinPageKind.RECOMMENDATION
            }
            if (signals.hasMerchantNodeForTarget &&
                !signals.hasMerchantBottomActionBar &&
                (signals.hasMerchantResultSignals || signals.hasSearchResultTabCluster)
            ) {
                return DouyinPageKind.MERCHANT_RESULT_LIST
            }
            if (signals.hasMerchantHeaderAnchor && signals.hasMerchantBottomActionBar) {
                return DouyinPageKind.MERCHANT_HOME
            }
            if (signals.hasMerchantBottomActionBar &&
                signals.hasMerchantTailSignal &&
                signals.hasMerchantCommerceSignal
            ) {
                return DouyinPageKind.MERCHANT_TAIL
            }
            if (hasStrongGroupBuyHomeSignals(signals) &&
                !signals.hasSearchResultTabCluster &&
                !signals.hasRecommendationSignal
            ) {
                return DouyinPageKind.GROUPBUY_HOME
            }
            if (signals.hasBottomHomeTab && !signals.hasSelectedGroupBuyTab) {
                return DouyinPageKind.HOME_FEED
            }
            return DouyinPageKind.UNKNOWN
        }

        internal fun normalizeText(text: String): String {
            return text.lowercase()
                .replace("[\\s·•|｜/\\\\-]+".toRegex(), "")
                .replace("[^\\p{L}\\p{N}]".toRegex(), "")
        }
    }

    fun classify(rootNode: AccessibilityNodeInfo, merchantName: String = ""): DouyinPageSnapshot {
        val signals = collectSignals(rootNode, merchantName)
        return DouyinPageSnapshot(
            kind = resolveKind(signals),
            signals = signals
        )
    }

    private fun collectSignals(
        rootNode: AccessibilityNodeInfo,
        merchantName: String
    ): DouyinPageSignals {
        val pageText = NodeUtils.getAllNodeText(
            rootNode,
            maxDepth = 22,
            maxNodes = 520,
            maxTextLength = 9000
        )
        val currentWindowClassName = (service as? FrameworkAccessibilityService)
            ?.getCurrentWindowClassName()
            .orEmpty()
        val normalizedTarget = normalizeText(merchantName)

        val hasSelectedGroupBuyTab = NodeUtils.findNodeByCondition(
            rootNode,
            condition = { node ->
                val text = NodeUtils.getNodeText(node)
                if (text.isBlank()) {
                    false
                } else {
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    bounds.top in 120..760 &&
                        bounds.height() in 40..180 &&
                        (
                            text.contains("已选中，团购", ignoreCase = true) ||
                                text.contains("团购，已选中", ignoreCase = true) ||
                                (text == "团购" && (node.isSelected || node.isChecked || node.isFocused)) ||
                                (text.contains("团购", ignoreCase = true) && node.isSelected)
                            )
                }
            },
            maxDepth = 32
        )?.let { node ->
            node.recycle()
            true
        } ?: false
        val hasSelectedRecommendationTab = NodeUtils.findNodeByCondition(
            rootNode,
            condition = { node ->
                val text = NodeUtils.getNodeText(node)
                if (text.isBlank()) {
                    false
                } else {
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    bounds.top in 120..760 &&
                        bounds.height() in 40..180 &&
                        (
                            text.contains("已选中，推荐", ignoreCase = true) ||
                                text.contains("推荐，已选中", ignoreCase = true) ||
                                (text == "推荐" && (node.isSelected || node.isChecked || node.isFocused)) ||
                                (text.contains("推荐", ignoreCase = true) && node.isSelected)
                            )
                }
            },
            maxDepth = 28
        )?.let { node ->
            node.recycle()
            true
        } ?: false

        val hasTopSearchButton = NodeUtils.findNodeByCondition(
            rootNode,
            condition = { node ->
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                val text = NodeUtils.getNodeText(node)
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
        )?.let { node ->
            node.recycle()
            true
        } ?: false

        val hasSearchEntryNode = NodeUtils.findNodeByCondition(
            rootNode,
            condition = { node ->
                val viewId = node.viewIdResourceName?.lowercase().orEmpty()
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                val text = NodeUtils.getNodeText(node)
                viewId.contains("et_search_kw") &&
                    !isLikelySearchInput(node) &&
                    bounds.top in 220..520 &&
                    bounds.bottom in 280..620 &&
                    GROUPBUY_SEARCH_ENTRY_HINTS.any { hint ->
                        text.contains(hint, ignoreCase = true)
                    }
            },
            maxDepth = 28
        )?.let { node ->
            node.recycle()
            true
        } ?: false

        val hasSearchInput = NodeUtils.findNodeByCondition(
            rootNode,
            condition = { node ->
                val viewId = node.viewIdResourceName?.lowercase().orEmpty()
                viewId.contains("et_search_kw") && isLikelySearchInput(node)
            },
            maxDepth = 20
        )?.let { node ->
            node.recycle()
            true
        } ?: false

        val hasSearchSubmitButton = NodeUtils.findNodeByCondition(
            rootNode,
            condition = { node ->
                val viewId = node.viewIdResourceName?.lowercase().orEmpty()
                val text = NodeUtils.getNodeText(node)
                viewId.contains("4_s") || text.contains("搜索", ignoreCase = true)
            },
            maxDepth = 20
        )?.let { node ->
            node.recycle()
            true
        } ?: false

        val hasBottomHomeTab = pageText.contains("首页", ignoreCase = true) &&
            pageText.contains("我", ignoreCase = true)
        val hasSearchHistorySignal = SEARCH_HISTORY_HINTS.any { hint ->
            pageText.contains(hint, ignoreCase = true)
        }
        val hasSearchSuggestionSignal = SEARCH_SUGGESTION_HINTS.any { hint ->
            pageText.contains(hint, ignoreCase = true)
        }
        val hasLocationSignal = pageText.contains("郑州", ignoreCase = true) ||
            pageText.contains("同城", ignoreCase = true)
        val hasSearchSignal = pageText.contains("搜索", ignoreCase = true)
        val hasGroupBuyKeywordCluster = GROUPBUY_PAGE_KEYWORDS.count { keyword ->
            pageText.contains(keyword, ignoreCase = true)
        } >= 2

        val hasMerchantResultSignals =
            Regex("\\d+条评价").containsMatchIn(pageText) ||
                pageText.contains("/人") ||
                pageText.contains("消费人数") ||
                pageText.contains("回头客")
        val hasSearchResultTabCluster =
            currentWindowClassName.contains(DOUYIN_SEARCH_RESULT_ACTIVITY, ignoreCase = true) ||
                (
                    pageText.contains("团购", ignoreCase = true) &&
                        pageText.contains("直播", ignoreCase = true) &&
                        pageText.contains("视频", ignoreCase = true)
                    )

        val hasMerchantNodeForTarget = normalizedTarget.isNotBlank() && NodeUtils.findNodeByCondition(
            rootNode,
            condition = { node ->
                if (isLikelySearchInput(node)) {
                    return@findNodeByCondition false
                }
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                val text = normalizeText(NodeUtils.getNodeText(node))
                bounds.top in 180..1700 && text.isNotBlank() && text.contains(normalizedTarget)
            },
            maxDepth = 32
        )?.let { node ->
            node.recycle()
            true
        } ?: false

        val hasMerchantHeaderAnchor = hasMerchantHeaderAnchor(rootNode, normalizedTarget)
        val hasMerchantBottomActionBar = hasMerchantBottomActionBarAnchor(rootNode, pageText)
        val hasMerchantTailSignal = MERCHANT_TAIL_SECTION_HINTS.any { hint ->
            pageText.contains(hint, ignoreCase = true)
        }
        val hasMerchantCommerceSignal = MERCHANT_DETAIL_CARD_HINTS.any { hint ->
            pageText.contains(hint, ignoreCase = true)
        }
        val distanceSignalCount = DISTANCE_HINT_REGEX.findAll(pageText).take(3).count()
        val hasDistanceRecommendationSignal = distanceSignalCount >= 2
        val hasRecommendationSignal =
            hasSelectedRecommendationTab ||
                RECOMMENDATION_HINTS.any { hint ->
                    pageText.contains(hint, ignoreCase = true)
                } ||
                (
                    hasDistanceRecommendationSignal &&
                        hasMerchantBottomActionBar &&
                        !hasMerchantHeaderAnchor &&
                        !hasMerchantTailSignal
                    )

        return DouyinPageSignals(
            pageText = pageText,
            currentWindowClassName = currentWindowClassName,
            hasSelectedGroupBuyTab = hasSelectedGroupBuyTab,
            hasSelectedRecommendationTab = hasSelectedRecommendationTab,
            hasTopSearchButton = hasTopSearchButton,
            hasSearchEntryNode = hasSearchEntryNode,
            hasSearchSignal = hasSearchSignal,
            hasSearchInput = hasSearchInput,
            hasSearchSubmitButton = hasSearchSubmitButton,
            hasSearchHistorySignal = hasSearchHistorySignal,
            hasSearchSuggestionSignal = hasSearchSuggestionSignal,
            hasLocationSignal = hasLocationSignal,
            hasBottomHomeTab = hasBottomHomeTab,
            hasGroupBuyKeywordCluster = hasGroupBuyKeywordCluster,
            hasMerchantNodeForTarget = hasMerchantNodeForTarget,
            hasMerchantResultSignals = hasMerchantResultSignals,
            hasSearchResultTabCluster = hasSearchResultTabCluster,
            hasMerchantHeaderAnchor = hasMerchantHeaderAnchor,
            hasMerchantBottomActionBar = hasMerchantBottomActionBar,
            hasMerchantTailSignal = hasMerchantTailSignal,
            hasMerchantCommerceSignal = hasMerchantCommerceSignal,
            hasDistanceRecommendationSignal = hasDistanceRecommendationSignal,
            hasRecommendationSignal = hasRecommendationSignal
        )
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
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
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
                if (!hasMerchantTitle &&
                    normalizedTarget.isNotBlank() &&
                    normalizedText.contains(normalizedTarget)
                ) {
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

    private fun hasMerchantBottomActionBarAnchor(
        rootNode: AccessibilityNodeInfo,
        pageText: String
    ): Boolean {
        val metrics = service.resources.displayMetrics
        val minTop = (metrics.heightPixels * 0.88f).toInt()
        val minWidth = (metrics.widthPixels * 0.98f).toInt()
        val hasBottomTexts = BOTTOM_ACTION_BAR_HINTS.all { hint ->
            pageText.contains(hint, ignoreCase = true)
        }
        if (!hasBottomTexts) {
            return false
        }

        val bottomBarNode = NodeUtils.findNodeByCondition(
            rootNode,
            condition = { node ->
                val bounds = Rect().also { node.getBoundsInScreen(it) }
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

        val matched = bottomBarNode != null
        bottomBarNode?.recycle()
        return matched
    }

    private fun isLikelySearchInput(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false

        val className = node.className?.toString().orEmpty()
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        val text = NodeUtils.getNodeText(node)
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val looksLikeEditableClass =
            className.contains("EditText", ignoreCase = true) ||
                className.contains("AutoCompleteTextView", ignoreCase = true)
        val looksLikeSearchFieldId =
            viewId.contains("search") ||
                viewId.contains("et_search") ||
                viewId.contains("search_kw") ||
                viewId.contains("search_input")
        val hintLikeSearch = text.contains("搜索", ignoreCase = true) ||
            node.hintText?.toString()?.contains("搜索", ignoreCase = true) == true

        return (looksLikeEditableClass || looksLikeSearchFieldId || node.isEditable) &&
            bounds.width() >= 300 &&
            bounds.height() >= 80 &&
            (hintLikeSearch || looksLikeSearchFieldId || looksLikeEditableClass)
    }
}
