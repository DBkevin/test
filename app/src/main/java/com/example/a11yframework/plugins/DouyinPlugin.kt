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
            "猜你喜欢",
            "发现同城"
        )
        private val NON_MERCHANT_TEXT_MARKERS = listOf(
            "预约到店送好礼",
            "预约到店专属礼",
            "专属礼"
        ) + HARD_NON_GROUPBUY_MODULE_MARKERS
        private val MERCHANT_NAME_STOP_MARKERS = listOf(
            "关注",
            "收藏",
            "回头客",
            "无隐形消费",
            "在线咨询",
            "电话",
            "团购",
            "领券抢购",
            "已售",
            "原价",
            "现价",
            "搜索",
            "返回",
            "展开更多",
            "展开全部",
            "收起"
        )
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
        private const val MIN_CARD_TOP = 0
        private val GROUPBUY_TAIL_SIGNALS = listOf(
            "展开更多",
            "展开全部",
            "收起",
            "预约到店送好礼",
            "预约到店专属礼",
            "用户评价"
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

        internal fun normalizeMerchantLabel(text: String): String {
            val normalized = normalizeCardText(text)
            if (normalized.isBlank()) {
                return ""
            }

            val cutIndex = MERCHANT_NAME_STOP_MARKERS
                .mapNotNull { marker ->
                    normalized.indexOf(marker).takeIf { it > 0 }
                }
                .minOrNull()
                ?: normalized.length

            return normalized
                .substring(0, cutIndex)
                .trim(' ', ',', '，', '|', '｜', '·', '•')
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

    private data class MerchantCandidate(
        val name: String,
        val top: Int,
        val score: Int
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
                (hasTailSignals && visibleCards.isNotEmpty())
        if (isLikelyRecommendationSection(pageText) && !hasMerchantContext && !hasConfiguredKeyword) {
            Log.d(TAG, "Detected hard non-groupbuy module without merchant context, skip target page")
            return false
        }

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
            val merchantName = hospitalInfo.hospitalName.ifBlank {
                keywords.firstOrNull()?.trim().orEmpty()
            }
            if (merchantName.isBlank()) {
                Log.d(TAG, "Merchant name missing and no configured keyword fallback, skip capture")
                return emptyList()
            }
            if (hospitalInfo.hospitalName.isBlank()) {
                Log.d(TAG, "Merchant name missing from detail header, fallback to configured keyword: $merchantName")
            }

            val groupBuyList = extractGroupBuyList(nodeInfo)
            if (groupBuyList.isEmpty()) {
                Log.d(TAG, "No visible group-buy cards on current page")
                return emptyList()
            }

            val results = groupBuyList.map { groupBuy ->
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
                        "pageSignals" to hospitalInfo.honors,
                        "merchantNameSource" to if (hospitalInfo.hospitalName.isBlank()) {
                            "configured_keyword"
                        } else {
                            "detail_header"
                        }
                    )
                )
            }

            Log.i(TAG, "Scraped ${results.size} visible cards from $merchantName")
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
        val firstCardTop = findVisibleGroupBuyCards(rootNode)
            .map { nodeTop(it) }
            .minOrNull()
            ?: Int.MAX_VALUE
        val titleBottomLimit = if (firstCardTop == Int.MAX_VALUE) 1800 else firstCardTop - 24
        val keywordHints = keywords
            .map(::normalizeMerchantLabel)
            .filter { it.isNotBlank() }

        val candidates = NodeUtils.findNodesByCondition(rootNode, { node ->
            val rawLabel = normalizeCardText(NodeUtils.getNodeText(node))
            if (rawLabel.isBlank()) return@findNodesByCondition false
            if (rawLabel.contains("现价") || rawLabel.contains("已售")) return@findNodesByCondition false
            if (containsNonMerchantTextMarker(rawLabel)) return@findNodesByCondition false

            val label = normalizeMerchantLabel(rawLabel)
            if (label.isBlank()) return@findNodesByCondition false

            val rect = Rect()
            node.getBoundsInScreen(rect)

            val matchesConfiguredKeyword = keywordHints.any { keyword ->
                label.contains(keyword, ignoreCase = true)
            }
            val matchesHospitalKeyword = HOSPITAL_KEYWORDS.any { keyword ->
                label.contains(keyword, ignoreCase = true)
            }

            rect.top in 350..1700 &&
                rect.width() >= 280 &&
                rect.height() <= 260 &&
                rect.bottom <= titleBottomLimit &&
                (matchesConfiguredKeyword || matchesHospitalKeyword)
        }, maxDepth = 32)

        try {
            val merchantName = candidates
                .mapNotNull { node ->
                    val rect = Rect().also { node.getBoundsInScreen(it) }
                    val label = normalizeMerchantLabel(NodeUtils.getNodeText(node))
                    if (label.isBlank()) {
                        return@mapNotNull null
                    }

                    val matchesConfiguredKeyword = keywordHints.any { keyword ->
                        label.contains(keyword, ignoreCase = true)
                    }
                    val matchesHospitalKeyword = HOSPITAL_KEYWORDS.any { keyword ->
                        label.contains(keyword, ignoreCase = true)
                    }
                    val score =
                        (if (matchesConfiguredKeyword) 120 else 0) +
                            (if (matchesHospitalKeyword) 40 else 0) +
                            (if (rect.top in 450..1100) 18 else 0) +
                            (if (rect.left <= 120) 12 else 0) +
                            (if (rect.width() >= 600) 10 else 0) -
                            (if (label.length < 4) 40 else 0)

                    MerchantCandidate(
                        name = label,
                        top = rect.top,
                        score = score
                    )
                }
                .sortedWith(
                    compareByDescending<MerchantCandidate> { it.score }
                        .thenBy { it.top }
                        .thenByDescending { it.name.length }
                )
                .firstOrNull()
                ?.name
                .orEmpty()

            if (merchantName.isBlank()) {
                Log.d(
                    TAG,
                    "Merchant title not found: firstCardTop=$firstCardTop, titleBottomLimit=$titleBottomLimit, keywordHints=$keywordHints"
                )
            }
            return merchantName
        } finally {
            NodeUtils.recycleNodes(candidates)
        }
    }

    private fun containsHardNonGroupBuyModule(text: String): Boolean {
        return HARD_NON_GROUPBUY_MODULE_MARKERS.any { marker ->
            text.contains(marker, ignoreCase = true)
        }
    }

    private fun isLikelyRecommendationSection(pageText: String): Boolean {
        return containsHardNonGroupBuyModule(pageText)
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
        val candidates = NodeUtils.findNodesByCondition(rootNode, { node ->
            val label = getNodeText(node)
            if (label.isBlank()) return@findNodesByCondition false
            if (!isLikelyGroupBuyCardLabel(label)) return@findNodesByCondition false

            val rect = Rect()
            node.getBoundsInScreen(rect)

            rect.top >= MIN_CARD_TOP &&
                rect.width() >= MIN_CARD_WIDTH &&
                rect.height() >= MIN_CARD_HEIGHT &&
                rect.left <= 120 &&
                rect.right >= 1180
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

        return hasPrice && hasHint && hasTitleText
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
