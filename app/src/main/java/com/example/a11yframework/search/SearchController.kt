package com.example.a11yframework.search

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.a11yframework.utils.NodeUtils

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
    fun search(keyword: String): Boolean {
        Log.i(TAG, "Searching for: $keyword")
        var searchBox: AccessibilityNodeInfo? = null

        return try {
            // 1. 定位搜索框
            searchBox = prepareSearchBox()
            if (searchBox == null) {
                Log.e(TAG, "Search box not found")
                return false
            }
            
            // 2. 清空搜索框
            clearSearchBox(searchBox)
            
            // 3. 输入搜索内容
            inputText(searchBox, keyword)
            
            // 4. 点击搜索按钮
            val submitted = clickSearchButton(searchBox)
            
            Log.i(TAG, "Search executed: $keyword, submitted=$submitted")
            submitted
            
        } catch (e: Exception) {
            Log.e(TAG, "Search error", e)
            false
        } finally {
            searchBox?.recycle()
        }
    }

    private fun prepareSearchBox(): AccessibilityNodeInfo? {
        findSearchBox()?.let { return it }

        if (!openSearchEntry()) {
            return null
        }

        Thread.sleep(1200)
        return findSearchBox()
    }
    
    /**
     * 定位搜索框
     */
    private fun findSearchBox(): AccessibilityNodeInfo? {
        val rootNode = service.rootInActiveWindow ?: return null
        
        try {
            // 方法 1: 通过关键词查找
            val byKeyword = NodeUtils.findNodeByCondition(rootNode, condition = { node: AccessibilityNodeInfo ->
                val text = NodeUtils.getNodeText(node).lowercase()
                SEARCH_KEYWORDS.any { keyword -> text.contains(keyword.lowercase()) }
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
    private fun clickSearchButton(searchBox: AccessibilityNodeInfo? = null): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false
        
        try {
            // 方法 1: 通过关键词查找搜索按钮
            val button = NodeUtils.findNodeByCondition(rootNode, condition = { node: AccessibilityNodeInfo ->
                if (!node.isClickable) return@findNodeByCondition false
                
                val text = NodeUtils.getNodeText(node).lowercase()
                SEARCH_BUTTON_KEYWORDS.any { keyword -> text.contains(keyword.lowercase()) }
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

    private fun openSearchEntry(): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false

        try {
            val entryNode = NodeUtils.findNodeByCondition(rootNode, condition = { node: AccessibilityNodeInfo ->
                if (!node.isClickable) return@findNodeByCondition false

                val nodeText = NodeUtils.getNodeText(node).lowercase()
                val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
                val viewId = node.viewIdResourceName?.lowercase() ?: ""

                SEARCH_KEYWORDS.any { keyword ->
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
}
