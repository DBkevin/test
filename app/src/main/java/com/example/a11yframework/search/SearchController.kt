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
        
        // 搜索框特征
        private val SEARCH_KEYWORDS = listOf("搜索", "搜索框", "search", "放大镜")
        private val SEARCH_BUTTON_KEYWORDS = listOf("搜索", "查找", "search", "🔍")
        private val DOUYIN_GROUPBUY_PAGE_KEYWORDS = listOf("附近好店", "美食", "休闲娱乐", "景点/周边游", "酒店民宿", "丽人")
        private val SCROLLABLE_CLASS_KEYWORDS = listOf("RecyclerView", "ListView", "ScrollView", "NestedScrollView", "WebView")
        
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
                    val clicked = NodeUtils.clickNode(merchantNode)
                    Log.i(
                        TAG,
                        "Merchant result click: name=$merchantName, round=$round, clicked=$clicked"
                    )
                    if (clicked) {
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
        maxScrollRounds: Int = 0
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
        findSearchBox(entryKeywords)?.let { return it }

        if (!openSearchEntry(entryKeywords)) {
            return null
        }

        Thread.sleep(1200)
        return findSearchBox(entryKeywords)
    }
    
    /**
     * 定位搜索框
     */
    private fun findSearchBox(searchKeywords: List<String> = SEARCH_KEYWORDS): AccessibilityNodeInfo? {
        val rootNode = service.rootInActiveWindow ?: return null
        
        try {
            // 方法 1: 通过关键词查找
            val byKeyword = NodeUtils.findNodeByCondition(rootNode, condition = { node: AccessibilityNodeInfo ->
                val text = NodeUtils.getNodeText(node).lowercase()
                searchKeywords.any { keyword -> text.contains(keyword.lowercase()) }
            })
            
            if (byKeyword != null) {
                Log.d(TAG, "Found search box by keyword")
                return AccessibilityNodeInfo.obtain(byKeyword)
            }
            
            // 方法 2: 通过 className 查找（EditText）
            val byClass = NodeUtils.findNodeByCondition(rootNode, condition = { node: AccessibilityNodeInfo ->
                node.className?.toString()?.contains("EditText") == true ||
                node.className?.toString()?.contains("edittext") == true
            })
            
            if (byClass != null) {
                Log.d(TAG, "Found search box by class")
                return AccessibilityNodeInfo.obtain(byClass)
            }
            
            // 方法 3: 通过 viewId 查找
            val byId = NodeUtils.findNodeByCondition(rootNode, condition = { node: AccessibilityNodeInfo ->
                val viewId = node.viewIdResourceName ?: return@findNodeByCondition false
                viewId.contains("search") || viewId.contains("Search")
            })
            
            if (byId != null) {
                Log.d(TAG, "Found search box by id")
                return AccessibilityNodeInfo.obtain(byId)
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
            // 方法 1: 通过关键词查找搜索按钮
            val button = NodeUtils.findNodeByCondition(rootNode, condition = { node: AccessibilityNodeInfo ->
                if (!node.isClickable) return@findNodeByCondition false
                
                val text = NodeUtils.getNodeText(node).lowercase()
                buttonKeywords.any { keyword -> text.contains(keyword.lowercase()) }
            })
            
            if (button != null) {
                NodeUtils.clickNode(button)
                Log.d(TAG, "Clicked search button by keyword")
                return true
            }
            
            // 方法 2: 通过 viewId 查找
            val buttonById = NodeUtils.findNodeByCondition(rootNode, condition = { node: AccessibilityNodeInfo ->
                if (!node.isClickable) return@findNodeByCondition false
                
                val viewId = node.viewIdResourceName ?: return@findNodeByCondition false
                viewId.contains("search") || viewId.contains("Search") ||
                viewId.contains("btn") || viewId.contains("button")
            })
            
            if (buttonById != null) {
                NodeUtils.clickNode(buttonById)
                Log.d(TAG, "Clicked search button by id")
                return true
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
            val entryNode = NodeUtils.findNodeByCondition(rootNode, condition = { node: AccessibilityNodeInfo ->
                if (!node.isClickable) return@findNodeByCondition false

                val nodeText = NodeUtils.getNodeText(node).lowercase()
                val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
                val viewId = node.viewIdResourceName?.lowercase() ?: ""

                entryKeywords.any { keyword ->
                    val lowerKeyword = keyword.lowercase()
                    nodeText.contains(lowerKeyword) ||
                        contentDesc.contains(lowerKeyword) ||
                        viewId.contains(lowerKeyword)
                }
            })

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
                    val text = normalizeText(getComparableNodeText(node))
                    text.isNotBlank() && (
                        text.contains(normalizedTarget) || normalizedTarget.contains(text)
                    )
                },
                maxDepth = 20
            )

            val scoredCandidate = candidates
                .map { it to scoreMerchantCandidate(it, normalizedTarget) }
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

    private fun scoreMerchantCandidate(node: AccessibilityNodeInfo, normalizedTarget: String): Int {
        val comparableText = normalizeText(getComparableNodeText(node))
        if (comparableText.isBlank()) {
            return 0
        }
        if (!comparableText.contains(normalizedTarget) && !normalizedTarget.contains(comparableText)) {
            return 0
        }

        var score = 0
        if (comparableText == normalizedTarget) {
            score += 120
        }
        if (comparableText.contains(normalizedTarget)) {
            score += 80
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
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        
        service.dispatchGesture(gesture, null, null)
    }
    
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
