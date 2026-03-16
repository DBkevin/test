package com.example.a11yframework.plugins

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
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
 * 当前主链路：
 * 1. 进入店铺首页
 * 2. 识别当前可见团购卡
 * 3. 自动下拉主列表
 * 4. 直到连续多轮没有新卡时结束
 */
class DouyinPlugin : IAccessibilityPlugin {

    companion object {
        private const val TAG = "DouyinPlugin"

        val DOUYIN_PACKAGES = listOf(
            "com.ss.android.ugc.aweme",
            "com.ss.android.ugc.aweme.lite"
        )

        private val HOSPITAL_KEYWORDS = listOf("医院", "门诊", "整形", "美容", "医美", "clinic")
        private val SHOP_PAGE_SIGNALS = listOf("收藏", "关注", "回头客", "无隐形消费", "领券抢购")

        private const val PAGE_HOSPITAL_DETAIL = "hospital_detail"
        private const val GROUP_BUY_DATA_TYPE = "hospital_group_buy"

        private const val MAX_CAPTURE_SCROLLS = 12
        private const val MAX_STALE_SCROLLS = 2
        private const val SCROLL_GUARD_MS = 1200L
        private const val MIN_CARD_WIDTH = 900
        private const val MIN_CARD_HEIGHT = 140
        private const val MIN_CARD_TOP = 1500

        private val TITLE_REGEX = Regex("""^(.*?)(?=,周|,至少提前|,随时退|,原价|,现价|,已售)""")
        private val ORIGINAL_PRICE_REGEX = Regex("""原价\s*([0-9]+(?:\.[0-9]+)?)元""")
        private val CURRENT_PRICE_REGEX = Regex("""现价\s*([0-9]+(?:\.[0-9]+)?)元""")
        private val SALES_REGEX = Regex("""(已售[0-9A-Za-z+\-千wW万]+)""")

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
            if (!normalized.contains("现价") || !normalized.contains("已售")) return null

            val title = TITLE_REGEX.find(normalized)?.groupValues?.getOrNull(1)
                ?.let(::normalizeCardText)
                ?.trim(',', '，')
                .orEmpty()
            if (title.isBlank()) return null

            val price = CURRENT_PRICE_REGEX.find(normalized)?.groupValues?.getOrNull(1).orEmpty()
            val originalPrice = ORIGINAL_PRICE_REGEX.find(normalized)?.groupValues?.getOrNull(1).orEmpty()
            val sales = SALES_REGEX.find(normalized)?.groupValues?.getOrNull(1).orEmpty()

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
    ) {
        fun identityKey(merchantName: String): String {
            fun normalize(value: String): String {
                return value.trim().lowercase().replace("\\s+".toRegex(), " ")
            }

            return listOf(
                normalize(merchantName),
                normalize(title),
                normalize(price),
                normalize(originalPrice)
            ).joinToString("|")
        }
    }

    private data class CaptureSession(
        var merchantName: String = "",
        val seenItemKeys: MutableSet<String> = linkedSetOf(),
        var staleScrolls: Int = 0,
        var scrollCount: Int = 0,
        var completed: Boolean = false,
        var lastScrollAt: Long = 0L
    )

    private var service: AccessibilityService? = null
    private var currentMode: String = "feed"
    private var keywords: List<String> = emptyList()
    private var captureSession = CaptureSession()

    override val pluginId: String = "douyin"
    override val pluginName: String = "功能 A"
    override val targetPackages: List<String> = DOUYIN_PACKAGES

    override fun initialize(service: AccessibilityService) {
        this.service = service
        Log.i(TAG, "Plugin initialized")

        val frameworkService = service as? FrameworkAccessibilityService
        val configManager: ConfigManager? = frameworkService?.configManager

        val loadedKeywords = configManager?.getPluginConfigList(pluginId, "keywords")
        keywords = loadedKeywords?.filter { it.isNotEmpty() } ?: listOf("郑州美莱")
        currentMode = configManager?.getPluginConfigString(pluginId, "scrapeMode", "feed") ?: "feed"
        resetSession()

        Log.d(TAG, "Config loaded: mode=$currentMode, keywords=$keywords")
    }

    override fun cleanup() {
        resetSession()
        service = null
        Log.i(TAG, "Plugin cleaned up")
    }

    override fun onActivate() {
        resetSession()
        Log.i(TAG, "Plugin activated")
    }

    override fun onDeactivate() {
        resetSession()
        Log.i(TAG, "Plugin deactivated")
    }

    override fun isTargetPage(nodeInfo: AccessibilityNodeInfo?): Boolean {
        if (nodeInfo == null) return false

        val merchantName = extractMerchantName(nodeInfo)
        if (merchantName.isBlank()) return false

        val pageText = getNodeText(nodeInfo)
        val signalCount = SHOP_PAGE_SIGNALS.count { signal ->
            pageText.contains(signal, ignoreCase = true)
        }
        val visibleCards = findVisibleGroupBuyCards(nodeInfo)

        val isTarget = signalCount >= 2 && visibleCards.isNotEmpty()
        if (isTarget) {
            Log.i(
                TAG,
                "Target merchant page detected: merchant=$merchantName, signals=$signalCount, cards=${visibleCards.size}"
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

            ensureSession(hospitalInfo.hospitalName)

            val visibleItems = extractGroupBuyList(nodeInfo, hospitalInfo.hospitalName)
            if (visibleItems.isEmpty()) {
                Log.d(TAG, "No visible group-buy cards on current screen")
                return emptyList()
            }

            val newItems = visibleItems.filter { item ->
                captureSession.seenItemKeys.add(item.identityKey(hospitalInfo.hospitalName))
            }

            if (newItems.isEmpty()) {
                captureSession.staleScrolls += 1
                Log.d(TAG, "No new group-buy cards found, stale=${captureSession.staleScrolls}")
            } else {
                captureSession.staleScrolls = 0
                Log.i(
                    TAG,
                    "Collected ${newItems.size} new group-buy cards, total=${captureSession.seenItemKeys.size}"
                )
            }

            val results = newItems.map { groupBuy ->
                ScrapedData(
                    pluginId = pluginId,
                    pageType = PAGE_HOSPITAL_DETAIL,
                    dataType = GROUP_BUY_DATA_TYPE,
                    content = mapOf(
                        "merchantName" to hospitalInfo.hospitalName,
                        "hospitalName" to hospitalInfo.hospitalName,
                        "honors" to hospitalInfo.honors,
                        "groupBuyTitle" to groupBuy.title,
                        "price" to groupBuy.price,
                        "originalPrice" to groupBuy.originalPrice,
                        "sales" to groupBuy.sales,
                        "cardText" to groupBuy.rawText,
                        "rawText" to groupBuy.rawText
                    ),
                    rawText = groupBuy.rawText,
                    metadata = mapOf(
                        "merchantName" to hospitalInfo.hospitalName,
                        "captureMode" to "auto_scroll",
                        "scrollCount" to captureSession.scrollCount
                    )
                )
            }

            continueCaptureIfNeeded(nodeInfo)
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

    private fun ensureSession(merchantName: String) {
        if (captureSession.merchantName != merchantName) {
            captureSession = CaptureSession(merchantName = merchantName)
            Log.i(TAG, "Start capture session for merchant=$merchantName")
        }
    }

    private fun resetSession() {
        captureSession = CaptureSession()
    }

    private fun continueCaptureIfNeeded(rootNode: AccessibilityNodeInfo) {
        if (captureSession.completed) return

        if (captureSession.scrollCount >= MAX_CAPTURE_SCROLLS) {
            captureSession.completed = true
            Log.i(TAG, "Capture session finished: reached max scroll count")
            return
        }

        if (captureSession.staleScrolls > MAX_STALE_SCROLLS) {
            captureSession.completed = true
            Log.i(TAG, "Capture session finished: no new cards after repeated scrolls")
            return
        }

        val now = System.currentTimeMillis()
        if (now - captureSession.lastScrollAt < SCROLL_GUARD_MS) return

        val scrolled = scrollMainContentList(rootNode)
        if (scrolled) {
            captureSession.scrollCount += 1
            captureSession.lastScrollAt = now
            Log.i(TAG, "Triggered scroll ${captureSession.scrollCount}/$MAX_CAPTURE_SCROLLS")
        } else {
            captureSession.completed = true
            Log.i(TAG, "Capture session finished: main list can no longer scroll")
        }
    }

    private fun extractHospitalInfo(rootNode: AccessibilityNodeInfo): HospitalInfo {
        val merchantName = extractMerchantName(rootNode)
        val honors = extractShopSignals(rootNode)
        Log.d(TAG, "Hospital info: name=$merchantName, honors=$honors")
        return HospitalInfo(merchantName, honors)
    }

    private fun extractMerchantName(rootNode: AccessibilityNodeInfo): String {
        val candidates = NodeUtils.findNodesByCondition(rootNode, { node ->
            val label = NodeUtils.getNodeText(node)
            if (label.isBlank()) return@findNodesByCondition false
            if (label.contains("现价") || label.contains("已售")) return@findNodesByCondition false

            val rect = Rect()
            node.getBoundsInScreen(rect)

            rect.top in 400..1200 &&
                rect.width() >= 400 &&
                rect.height() <= 180 &&
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

    private fun extractShopSignals(rootNode: AccessibilityNodeInfo): String {
        val signalNodes = NodeUtils.findNodesByCondition(rootNode, { node ->
            val label = NodeUtils.getNodeText(node)
            if (label.isBlank()) return@findNodesByCondition false

            val rect = Rect()
            node.getBoundsInScreen(rect)

            rect.top in 700..1500 &&
                SHOP_PAGE_SIGNALS.any { signal -> label.contains(signal) }
        }, maxDepth = 24)

        return signalNodes
            .map { normalizeCardText(NodeUtils.getNodeText(it)) }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" | ")
    }

    private fun extractGroupBuyList(rootNode: AccessibilityNodeInfo, merchantName: String): List<GroupBuyInfo> {
        val cards = findVisibleGroupBuyCards(rootNode)
        if (cards.isEmpty()) return emptyList()

        val items = cards.mapNotNull { cardNode ->
            extractGroupBuyItem(cardNode, merchantName)
        }.distinctBy { it.identityKey(merchantName) }

        Log.d(TAG, "Visible group-buy cards: ${items.size}")
        return items
    }

    private fun findVisibleGroupBuyCards(rootNode: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val candidates = NodeUtils.findNodesByCondition(rootNode, { node ->
            val label = NodeUtils.getNodeText(node)
            if (label.isBlank()) return@findNodesByCondition false
            if (!label.contains("现价") || !label.contains("已售")) return@findNodesByCondition false

            val rect = Rect()
            node.getBoundsInScreen(rect)

            rect.top >= MIN_CARD_TOP &&
                rect.width() >= MIN_CARD_WIDTH &&
                rect.height() >= MIN_CARD_HEIGHT &&
                rect.left <= 100 &&
                rect.right >= 1200
        }, maxDepth = 32)

        return candidates
            .distinctBy { nodeBoundsKey(it) }
            .sortedBy { nodeTop(it) }
    }

    private fun extractGroupBuyItem(cardNode: AccessibilityNodeInfo, merchantName: String): GroupBuyInfo? {
        val ownLabel = normalizeCardText(NodeUtils.getNodeText(cardNode))
        val rawCardText = if (ownLabel.isNotBlank()) ownLabel else normalizeCardText(getNodeText(cardNode))
        val parsed = parseGroupBuyCardText(rawCardText) ?: return null

        if (parsed.title.contains(merchantName)) {
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

    private fun scrollMainContentList(rootNode: AccessibilityNodeInfo): Boolean {
        val listNode = findMainScrollableList(rootNode) ?: return false

        if (NodeUtils.scrollNode(listNode, forward = true)) {
            Log.d(TAG, "Scrolled main list via ACTION_SCROLL_FORWARD")
            return true
        }

        val rect = Rect()
        listNode.getBoundsInScreen(rect)
        val gestureResult = performSwipeUp(rect)
        Log.d(TAG, "Scrolled main list via gesture: $gestureResult")
        return gestureResult
    }

    private fun findMainScrollableList(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val scrollables = NodeUtils.findNodesByCondition(rootNode, { node ->
            if (!node.isScrollable) return@findNodesByCondition false
            val className = node.className?.toString().orEmpty()
            className.contains("RecyclerView")
        }, maxDepth = 24)

        return scrollables.maxWithOrNull(
            compareBy<AccessibilityNodeInfo>(
                { if ((it.viewIdResourceName ?: "").contains(":id/gp3")) 1 else 0 }
            ).thenBy { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                rect.width() * rect.height()
            }
        )
    }

    private fun performSwipeUp(rect: Rect): Boolean {
        val currentService = service ?: return false

        val startX = rect.centerX().toFloat()
        val startY = (rect.bottom - rect.height() * 0.18f)
        val endY = (rect.top + rect.height() * 0.25f)

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()

        return currentService.dispatchGesture(gesture, null, null)
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
