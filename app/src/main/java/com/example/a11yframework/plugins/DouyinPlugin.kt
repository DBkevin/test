package com.example.a11yframework.plugins

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.a11yframework.config.ConfigManager
import com.example.a11yframework.core.FrameworkAccessibilityService
import com.example.a11yframework.core.IAccessibilityPlugin
import com.example.a11yframework.core.ScrapedData
import com.example.a11yframework.utils.NodeUtils

/**
 * 抖音插件 - 医美店铺团购采集
 *
 * 当前职责只做两件事：
 * 1. 识别当前是否是店铺首页/团购列表页
 * 2. 提取当前视口内可见的团购卡
 *
 * 滚动、展开、聚合由 CaptureCoordinator 统一负责。
 */
class DouyinPlugin : IAccessibilityPlugin {

    companion object {
        private const val TAG = "DouyinPlugin"

        val DOUYIN_PACKAGES = listOf(
            "com.ss.android.ugc.aweme",
            "com.ss.android.ugc.aweme.lite"
        )

        private val HOSPITAL_KEYWORDS = listOf("医院", "门诊", "整形", "美容", "医美", "clinic")
        private val SHOP_PAGE_SIGNALS = listOf("收藏", "关注", "回头客", "无隐形消费", "领券抢购", "已售")
        private val HARD_NON_GROUPBUY_MODULE_MARKERS = listOf(
            "你可能感兴趣的地点",
            "你可能感兴趣",
            "猜你喜欢"
        )
        private val NON_MERCHANT_TEXT_MARKERS = listOf(
            "预约到店送好礼",
            "预约到店专属礼",
            "专属礼"
        ) + HARD_NON_GROUPBUY_MODULE_MARKERS
        private val CARD_HINT_KEYWORDS = listOf(
            "现价",
            "原价",
            "已售",
            "人逛过",
            "次卡",
            "周末节假日通用",
            "随时退",
            "至少提前",
            "领券抢购",
            "券后"
        )

        private const val PAGE_HOSPITAL_DETAIL = "hospital_detail"
        private const val GROUP_BUY_DATA_TYPE = "group_buys"
        private const val MIN_CARD_WIDTH = 900
        private const val MIN_CARD_HEIGHT = 120
        private const val MAX_CARD_HEIGHT = 620
        private const val MIN_CARD_TOP = 0
        private val GROUPBUY_TAIL_SIGNALS = listOf(
            "展开更多",
            "收起",
            "预约到店送好礼",
            "预约到店专属礼",
            "用户评价"
        )
        private val GROUPBUY_TAB_ROW_HINTS = listOf("团购", "服务", "评价", "推荐")
        private val GROUPBUY_SECTION_STOP_MARKERS = listOf("展开更多", "收起", "热门服务", "用户评价")
        private val MERCHANT_NAME_EXCLUDE_HINTS = listOf(
            "现价",
            "原价",
            "已售",
            "领券抢购",
            "去抢购",
            "在线咨询",
            "电话",
            "详情",
            "热门服务"
        )

        private val TITLE_REGEX = Regex(
            """^(.*?)(?=,(?:周|至少提前|随时退|原价|现价|已售|[0-9A-Za-z+\-千wW万]+\s*人逛过|次卡|放心付|券后|周末节假日通用|预约|可用)|$)"""
        )
        private val ORIGINAL_PRICE_REGEX = Regex("""原价\s*([0-9]+(?:\.[0-9]+)?)元""")
        private val CURRENT_PRICE_REGEX = Regex("""现价\s*([0-9]+(?:\.[0-9]+)?)元""")
        private val SALES_REGEX = Regex("""(已售[0-9A-Za-z+\-千wW万]+)""")
        private val BROWSE_REGEX = Regex("""([0-9A-Za-z+\-千wW万]+\s*人逛过)""")
        private val DISPLAY_PRICE_REGEX = Regex("""¥\s*([0-9]+(?:\.[0-9]+)?)""")
        private val TITLE_TEXT_REGEX = Regex("""[\p{IsHan}A-Za-z]{2,}""")

        internal data class ParsedCard(
            val title: String,
            val price: String,
            val originalPrice: String,
            val sales: String,
            val rawText: String
        )

        internal fun normalizeCardText(text: String): String {
            return text
                .replace("[\\u200B-\\u200D\\uFEFF]".toRegex(), "")
                .replace("\\s+".toRegex(), " ")
                .replace(" ,", ",")
                .replace(", ", ",")
                .trim()
        }

        internal fun parseGroupBuyCardText(cardText: String): ParsedCard? {
            val normalized = normalizeCardText(cardText)
            if (normalized.isBlank()) return null

            val title = TITLE_REGEX.find(normalized)?.groupValues?.getOrNull(1)
                ?.let(::normalizeCardText)
                ?.trim(',', '，')
                .orEmpty()
            if (title.isBlank()) return null

            val displayPrices = DISPLAY_PRICE_REGEX.findAll(normalized)
                .map { it.groupValues.getOrNull(1).orEmpty() }
                .filter { it.isNotBlank() }
                .toList()

            val price = CURRENT_PRICE_REGEX.find(normalized)?.groupValues?.getOrNull(1)
                ?: displayPrices.getOrNull(0)
                ?: ""
            val originalPrice = ORIGINAL_PRICE_REGEX.find(normalized)?.groupValues?.getOrNull(1)
                ?: displayPrices.getOrNull(1)
                ?: ""
            val sales = SALES_REGEX.find(normalized)?.groupValues?.getOrNull(1)
                ?: BROWSE_REGEX.find(normalized)?.groupValues?.getOrNull(1)
                ?: ""

            val hasCommerceHint = CARD_HINT_KEYWORDS.any { normalized.contains(it) }
            if (price.isBlank() || (!hasCommerceHint && sales.isBlank())) {
                return null
            }

            return ParsedCard(
                title = title,
                price = price,
                originalPrice = originalPrice,
                sales = sales,
                rawText = normalized
            )
        }
    }

    private data class HospitalInfo(
        val hospitalName: String = "",
        val honors: String = ""
    )

    private data class GroupBuyInfo(
        val title: String = "",
        val price: String = "",
        val originalPrice: String = "",
        val sales: String = "",
        val rawText: String = ""
    )

    private data class GroupBuyViewport(
        val top: Int,
        val bottom: Int
    )

    private var service: AccessibilityService? = null
    private var currentMode: String = "feed"
    private var keywords: List<String> = emptyList()
    private var lastMerchantName: String = ""

    override val pluginId: String = "douyin"
    override val pluginName: String = "功能 A"
    override val targetPackages: List<String> = DOUYIN_PACKAGES

    override fun initialize(service: AccessibilityService) {
        this.service = service
        Log.i(TAG, "Plugin initialized")

        val frameworkService = service as? FrameworkAccessibilityService
        val configManager: ConfigManager? = frameworkService?.configManager

        val loadedKeywords = configManager?.getPluginConfigList(pluginId, "keywords")
        keywords = loadedKeywords
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf("郑州美莱")
        currentMode = configManager?.getPluginConfigString(pluginId, "scrapeMode", "feed") ?: "feed"
        lastMerchantName = ""

        Log.d(TAG, "Config loaded: mode=$currentMode, keywords=$keywords")
    }

    override fun cleanup() {
        lastMerchantName = ""
        service = null
        Log.i(TAG, "Plugin cleaned up")
    }

    override fun onActivate() {
        lastMerchantName = ""
        Log.i(TAG, "Plugin activated")
    }

    override fun onDeactivate() {
        lastMerchantName = ""
        Log.i(TAG, "Plugin deactivated")
    }

    override fun isTargetPage(nodeInfo: AccessibilityNodeInfo?): Boolean {
        if (nodeInfo == null) return false

        val pageText = NodeUtils.getAllNodeText(
            nodeInfo,
            maxDepth = 24,
            maxNodes = 520,
            maxTextLength = 10000
        )
        val visibleCards = findVisibleGroupBuyCards(nodeInfo)
        if (containsHardNonGroupBuyModule(pageText) && visibleCards.isEmpty()) {
            Log.d(TAG, "Detected hard non-groupbuy module, skip target page")
            return false
        }

        val signalCount = SHOP_PAGE_SIGNALS.count { signal ->
            pageText.contains(signal, ignoreCase = true)
        }
        val hasTailSignals = GROUPBUY_TAIL_SIGNALS.any { signal ->
            pageText.contains(signal, ignoreCase = true)
        }
        val merchantName = extractMerchantName(nodeInfo)
        val hasConfiguredKeyword = keywords.any { keyword ->
            pageText.contains(keyword, ignoreCase = true)
        }

        val hasMerchantContext =
            merchantName.isNotBlank() ||
                signalCount >= 2 ||
                (signalCount >= 1 && visibleCards.isNotEmpty()) ||
                (hasTailSignals && visibleCards.isNotEmpty()) ||
                (lastMerchantName.isNotBlank() && !containsHardNonGroupBuyModule(pageText))
        val isTarget = visibleCards.isNotEmpty() && (hasMerchantContext || hasConfiguredKeyword)
        if (isTarget) {
            Log.i(
                TAG,
                "Target merchant page detected: merchant=${merchantName.ifBlank { lastMerchantName }}, signals=$signalCount, cards=${visibleCards.size}"
            )
        }
        return isTarget
    }

    override fun scrapeData(nodeInfo: AccessibilityNodeInfo?): List<ScrapedData> {
        if (nodeInfo == null) return emptyList()

        try {
            val hospitalInfo = extractHospitalInfo(nodeInfo)
            if (hospitalInfo.hospitalName.isBlank()) {
                Log.d(TAG, "Merchant name missing, skip capture")
                return emptyList()
            }

            val groupBuyList = extractGroupBuyList(nodeInfo)
            if (groupBuyList.isEmpty()) {
                Log.d(TAG, "No visible group-buy cards on current page")
                return emptyList()
            }

            val results = groupBuyList.map { groupBuy ->
                val merchantName = hospitalInfo.hospitalName
                ScrapedData(
                    pluginId = pluginId,
                    pageType = PAGE_HOSPITAL_DETAIL,
                    dataType = GROUP_BUY_DATA_TYPE,
                    content = mapOf(
                        "merchant_name" to merchantName,
                        "hospital_name" to merchantName,
                        "merchantName" to merchantName,
                        "hospitalName" to merchantName,
                        "honors" to hospitalInfo.honors,
                        "title" to groupBuy.title,
                        "groupBuyTitle" to groupBuy.title,
                        "price" to groupBuy.price,
                        "original_price" to groupBuy.originalPrice,
                        "originalPrice" to groupBuy.originalPrice,
                        "sales" to groupBuy.sales,
                        "card_text" to groupBuy.rawText,
                        "cardText" to groupBuy.rawText,
                        "raw_text" to groupBuy.rawText,
                        "rawText" to groupBuy.rawText
                    ),
                    rawText = groupBuy.rawText,
                    metadata = mapOf(
                        "pageName" to "商家详情页",
                        "pageSignals" to hospitalInfo.honors
                    )
                )
            }

            Log.i(TAG, "Scraped ${results.size} visible cards from ${hospitalInfo.hospitalName}")
            return results
        } catch (e: Exception) {
            Log.e(TAG, "Error scraping data", e)
            return emptyList()
        }
    }

    override fun processData(data: List<ScrapedData>): List<ScrapedData> {
        return data
            .filter { it.content["groupBuyTitle"].isNullOrBlank().not() }
            .map { item ->
                item.copy(
                    content = item.content.mapValues { (_, value) ->
                        value
                            .trim()
                            .replace("[\\u200B-\\u200D\\uFEFF]".toRegex(), "")
                            .replace("\\s+".toRegex(), " ")
                    },
                    rawText = normalizeCardText(item.rawText)
                )
            }
    }

    private fun extractHospitalInfo(rootNode: AccessibilityNodeInfo): HospitalInfo {
        val merchantName = extractMerchantName(rootNode).ifBlank { lastMerchantName }
        if (merchantName.isNotBlank()) {
            lastMerchantName = merchantName
        }
        val honors = extractShopSignals(rootNode)
        Log.d(TAG, "Hospital info: name=$merchantName, honors=$honors")
        return HospitalInfo(merchantName, honors)
    }

    private fun extractMerchantName(rootNode: AccessibilityNodeInfo): String {
        val viewport = resolveGroupBuyViewport(rootNode)
        val firstCardTop = findVisibleGroupBuyCards(rootNode)
            .map { nodeTop(it) }
            .minOrNull()
        val candidateBottom = when {
            firstCardTop == null -> viewport.top - 12
            firstCardTop > viewport.top + 120 -> firstCardTop - 12
            else -> viewport.top - 12
        }.coerceAtLeast(760)

        val candidates = NodeUtils.findNodesByCondition(rootNode, { node ->
            val label = normalizeCardText(NodeUtils.getNodeText(node))
            if (label.isBlank()) return@findNodesByCondition false
            if (MERCHANT_NAME_EXCLUDE_HINTS.any { hint -> label.contains(hint, ignoreCase = true) }) {
                return@findNodesByCondition false
            }
            if (containsNonMerchantTextMarker(label)) return@findNodesByCondition false

            val rect = Rect()
            node.getBoundsInScreen(rect)

            rect.top in 220..1500 &&
                rect.width() >= 400 &&
                rect.height() <= 220 &&
                rect.bottom <= candidateBottom &&
                HOSPITAL_KEYWORDS.any { keyword -> label.contains(keyword, ignoreCase = true) }
        }, maxDepth = 24)

        return candidates
            .map { node ->
                val rect = Rect().also { node.getBoundsInScreen(it) }
                rect.top to normalizeCardText(NodeUtils.getNodeText(node))
            }
            .sortedWith(compareBy<Pair<Int, String>> { it.first }.thenByDescending { it.second.length })
            .map { it.second }
            .firstOrNull()
            .orEmpty()
    }

    private fun containsHardNonGroupBuyModule(text: String): Boolean {
        return HARD_NON_GROUPBUY_MODULE_MARKERS.any { marker ->
            text.contains(marker, ignoreCase = true)
        }
    }

    private fun containsNonMerchantTextMarker(text: String): Boolean {
        return NON_MERCHANT_TEXT_MARKERS.any { marker ->
            text.contains(marker, ignoreCase = true)
        }
    }

    private fun extractShopSignals(rootNode: AccessibilityNodeInfo): String {
        val signalNodes = NodeUtils.findNodesByCondition(rootNode, { node ->
            val label = normalizeCardText(NodeUtils.getNodeText(node))
            if (label.isBlank()) return@findNodesByCondition false
            if (label.length > 32) return@findNodesByCondition false
            if (label.contains("原价") || label.contains("现价")) return@findNodesByCondition false

            val rect = Rect()
            node.getBoundsInScreen(rect)

            rect.top in 300..1400 &&
                rect.bottom <= 1500 &&
                SHOP_PAGE_SIGNALS.any { signal -> label.contains(signal) }
        }, maxDepth = 24)

        return signalNodes
            .map { normalizeCardText(NodeUtils.getNodeText(it)) }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" | ")
    }

    private fun extractGroupBuyList(rootNode: AccessibilityNodeInfo): List<GroupBuyInfo> {
        val cards = findVisibleGroupBuyCards(rootNode)
        if (cards.isEmpty()) return emptyList()

        val skippedSamples = mutableListOf<String>()
        val items = cards.mapNotNull { cardNode ->
            extractGroupBuyItem(cardNode, skippedSamples)
        }.distinctBy { item ->
            listOf(item.title, item.price, item.originalPrice).joinToString("|")
        }

        Log.d(TAG, "Visible group-buy cards: ${items.size}")
        if (items.isEmpty() && skippedSamples.isNotEmpty()) {
            Log.d(TAG, "Skipped group-buy samples: ${skippedSamples.joinToString(" || ")}")
        }
        return items
    }

    private fun findVisibleGroupBuyCards(rootNode: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val viewport = resolveGroupBuyViewport(rootNode)
        val candidates = NodeUtils.findNodesByCondition(rootNode, { node ->
            val label = normalizeCardText(NodeUtils.getNodeText(node))
            if (label.isBlank()) return@findNodesByCondition false
            if (!isLikelyGroupBuyCardLabel(label)) return@findNodesByCondition false
            if (containsNonMerchantTextMarker(label)) return@findNodesByCondition false

            val rect = Rect()
            node.getBoundsInScreen(rect)

            rect.top >= maxOf(MIN_CARD_TOP, viewport.top) &&
                rect.bottom <= viewport.bottom &&
                rect.width() >= MIN_CARD_WIDTH &&
                rect.height() >= MIN_CARD_HEIGHT &&
                rect.height() <= MAX_CARD_HEIGHT &&
                rect.left <= 120 &&
                rect.right >= 1100
        }, maxDepth = 32)

        return candidates
            .distinctBy { nodeBoundsKey(it) }
            .sortedBy { nodeTop(it) }
    }

    private fun extractGroupBuyItem(
        cardNode: AccessibilityNodeInfo,
        skippedSamples: MutableList<String>
    ): GroupBuyInfo? {
        val ownLabel = normalizeCardText(NodeUtils.getNodeText(cardNode))
        val rawCardText = if (ownLabel.isNotBlank()) ownLabel else normalizeCardText(getNodeText(cardNode))
        val parsed = parseGroupBuyCardText(rawCardText)
        if (parsed == null) {
            if (skippedSamples.size < 3 && rawCardText.isNotBlank()) {
                skippedSamples.add(rawCardText.take(140))
            }
            return null
        }

        return GroupBuyInfo(
            title = parsed.title,
            price = parsed.price,
            originalPrice = parsed.originalPrice,
            sales = parsed.sales,
            rawText = parsed.rawText
        )
    }

    private fun isLikelyGroupBuyCardLabel(label: String): Boolean {
        val normalized = normalizeCardText(label)
        if (normalized.isBlank()) return false

        val hasPrice = normalized.contains("现价") ||
            normalized.contains("原价") ||
            DISPLAY_PRICE_REGEX.containsMatchIn(normalized)
        val hasHint = CARD_HINT_KEYWORDS.any { normalized.contains(it) }
        val hasTitleText = TITLE_TEXT_REGEX.containsMatchIn(normalized)
        val hasActionSignal = normalized.contains("领券抢购") || normalized.contains("去抢购")
        val hasSalesSignal = SALES_REGEX.containsMatchIn(normalized) || BROWSE_REGEX.containsMatchIn(normalized)

        return hasPrice && hasHint && hasTitleText && (hasActionSignal || hasSalesSignal)
    }

    private fun resolveGroupBuyViewport(rootNode: AccessibilityNodeInfo): GroupBuyViewport {
        val metrics = service?.resources?.displayMetrics
        val screenHeight = metrics?.heightPixels ?: 3200
        val screenWidth = metrics?.widthPixels ?: 1440

        var top = (screenHeight * 0.40f).toInt()
        var bottom = (screenHeight * 0.992f).toInt()

        val tabRowNodes = NodeUtils.findNodesByCondition(rootNode, { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.isEmpty) return@findNodesByCondition false

            val className = node.className?.toString().orEmpty()
            val label = normalizeCardText(NodeUtils.getNodeText(node))
            val hasTabHint = GROUPBUY_TAB_ROW_HINTS.any { hint -> label.contains(hint, ignoreCase = true) }
            val isTabLikeScrollRow =
                className.contains("ScrollView", ignoreCase = true) &&
                    rect.width() >= (screenWidth * 0.30f).toInt() &&
                    rect.height() in 48..220 &&
                    rect.top in ((screenHeight * 0.22f).toInt()..(screenHeight * 0.78f).toInt())

            hasTabHint || isTabLikeScrollRow
        }, maxDepth = 30)

        try {
            val candidate = tabRowNodes.maxByOrNull { nodeTop(it) }
            if (candidate != null) {
                top = maxOf(top, nodeBottom(candidate) + 16)
            }
        } finally {
            NodeUtils.recycleNodes(tabRowNodes)
        }

        val stopMarkerNodes = NodeUtils.findNodesByCondition(rootNode, { node ->
            val label = normalizeCardText(NodeUtils.getNodeText(node))
            if (label.isBlank() || label.length > 24) return@findNodesByCondition false
            GROUPBUY_SECTION_STOP_MARKERS.any { marker -> label.contains(marker, ignoreCase = true) }
        }, maxDepth = 32)

        try {
            val stopTop = stopMarkerNodes
                .map { nodeTop(it) }
                .filter { markerTop -> markerTop > top + 140 }
                .minOrNull()
            if (stopTop != null) {
                bottom = minOf(bottom, stopTop - 12)
            }
        } finally {
            NodeUtils.recycleNodes(stopMarkerNodes)
        }

        if (bottom - top < 260) {
            top = (screenHeight * 0.42f).toInt()
            bottom = (screenHeight * 0.992f).toInt()
        }

        return GroupBuyViewport(
            top = top.coerceAtLeast(0),
            bottom = bottom.coerceAtMost(screenHeight)
        )
    }

    private fun nodeBoundsKey(node: AccessibilityNodeInfo): String {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return "${rect.left},${rect.top},${rect.right},${rect.bottom}"
    }

    private fun nodeTop(node: AccessibilityNodeInfo): Int {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect.top
    }

    private fun nodeBottom(node: AccessibilityNodeInfo): Int {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect.bottom
    }

    private fun getNodeText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        collectNodeText(node, sb, 0)
        return normalizeCardText(sb.toString())
    }

    private fun collectNodeText(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 12) return

        node.text?.let { sb.append(it.toString()).append(" ") }
        node.contentDescription?.let { sb.append(it.toString()).append(" ") }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectNodeText(child, sb, depth + 1)
                child.recycle()
            }
        }
    }
}
