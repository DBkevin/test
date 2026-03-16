package com.example.a11yframework.utils

import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

/**
 * 无障碍节点工具类
 * 
 * 提供常用的节点操作工具方法
 */
object NodeUtils {
    
    private const val TAG = "NodeUtils"
    
    /**
     * 打印节点树（调试用）
     */
    fun printNodeTree(rootNode: AccessibilityNodeInfo?, maxDepth: Int = 5) {
        if (rootNode == null) {
            Log.d(TAG, "Root node is null")
            return
        }
        
        Log.d(TAG, "=== Node Tree ===")
        printNodeRecursive(rootNode, 0, maxDepth)
        Log.d(TAG, "=== End Tree ===")
    }
    
    private fun printNodeRecursive(node: AccessibilityNodeInfo, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        
        val indent = "  ".repeat(depth)
        val className = node.className ?: "null"
        val text = node.text?.toString()?.take(50) ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val isClickable = node.isClickable
        val isScrollable = node.isScrollable
        
        Log.d(TAG, "$indent[$className] text=\"$text\" id=\"$viewId\" clickable=$isClickable scrollable=$isScrollable")
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                printNodeRecursive(child, depth + 1, maxDepth)
                child.recycle()
            }
        }
    }
    
    /**
     * 查找第一个匹配文本的节点
     */
    fun findFirstNodeByText(rootNode: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (rootNode == null) return null
        
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        return nodes.firstOrNull()
    }
    
    /**
     * 查找所有匹配文本的节点
     */
    fun findNodesByText(rootNode: AccessibilityNodeInfo?, text: String): List<AccessibilityNodeInfo> {
        if (rootNode == null) return emptyList()
        return rootNode.findAccessibilityNodeInfosByText(text)
    }
    
    /**
     * 查找所有匹配 className 的节点
     */
    fun findNodesByClassName(rootNode: AccessibilityNodeInfo?, className: String): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        if (rootNode == null) return results
        
        findNodesByClassNameRecursive(rootNode, className, results)
        return results
    }
    
    private fun findNodesByClassNameRecursive(
        node: AccessibilityNodeInfo,
        className: String,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.className == className) {
            // 注意：这里不 recycle，调用者负责
            results.add(node)
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findNodesByClassNameRecursive(child, className, results)
                // 如果不是结果节点，需要 recycle
                if (child !in results) {
                    child.recycle()
                }
            }
        }
    }
    
    /**
     * 获取节点及其所有子节点的文本
     */
    fun getAllNodeText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        
        val sb = StringBuilder()
        collectTextRecursive(node, sb)
        return sb.toString().trim()
    }
    
    private fun collectTextRecursive(node: AccessibilityNodeInfo, sb: StringBuilder) {
        node.text?.let { sb.append(it.toString()).append(" ") }
        node.contentDescription?.let { sb.append(it.toString()).append(" ") }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectTextRecursive(child, sb)
                child.recycle()
            }
        }
    }
    
    /**
     * 向上查找父节点
     */
    fun findParentNode(node: AccessibilityNodeInfo?, maxDepth: Int = 10): AccessibilityNodeInfo? {
        if (node == null) return null
        
        var parent = node.parent
        var depth = 0
        
        while (parent != null && depth < maxDepth) {
            return parent  // 返回直接父节点
        }
        
        return parent
    }
    
    /**
     * 查找满足条件的祖先节点
     */
    fun findAncestorByCondition(
        node: AccessibilityNodeInfo?,
        condition: (AccessibilityNodeInfo) -> Boolean,
        maxDepth: Int = 10
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        
        var parent = node.parent
        var depth = 0
        
        while (parent != null && depth < maxDepth) {
            if (condition(parent)) {
                return parent
            }
            val grandParent = parent.parent
            parent.recycle()
            parent = grandParent
            depth++
        }
        
        return parent
    }
    
    /**
     * 查找满足条件的子节点
     */
    fun findChildByCondition(
        node: AccessibilityNodeInfo?,
        condition: (AccessibilityNodeInfo) -> Boolean,
        maxDepth: Int = 5
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        
        return findChildByConditionRecursive(node, condition, 0, maxDepth)
    }
    
    private fun findChildByConditionRecursive(
        node: AccessibilityNodeInfo,
        condition: (AccessibilityNodeInfo) -> Boolean,
        depth: Int,
        maxDepth: Int
    ): AccessibilityNodeInfo? {
        if (depth > maxDepth) return null
        
        if (condition(node)) {
            return node
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val result = findChildByConditionRecursive(child, condition, depth + 1, maxDepth)
                if (result != null) {
                    return result
                }
                child.recycle()
            }
        }
        
        return null
    }
    
    /**
     * 点击节点
     */
    fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        
        return if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            // 尝试点击父节点
            var parent = node.parent
            var clicked = false
            var depth = 0
            
            while (parent != null && depth < 5 && !clicked) {
                if (parent.isClickable) {
                    clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                if (!clicked) {
                    val grandParent = parent.parent
                    parent.recycle()
                    parent = grandParent
                }
                depth++
            }
            
            clicked
        }
    }
    
    /**
     * 滚动节点
     */
    fun scrollNode(node: AccessibilityNodeInfo?, forward: Boolean = true): Boolean {
        if (node == null || !node.isScrollable) return false
        
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        
        return node.performAction(action)
    }
    
    /**
     * 获取节点文本
     */
    fun getNodeText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""

        val text = node.text?.toString()?.trim().orEmpty()
        val contentDesc = node.contentDescription?.toString()?.trim().orEmpty()

        return when {
            text.isNotEmpty() && contentDesc.isNotEmpty() && text != contentDesc -> "$text $contentDesc"
            text.isNotEmpty() -> text
            else -> contentDesc
        }.trim()
    }
    
    /**
     * 查找第一个满足条件的节点
     */
    fun findNodeByCondition(
        rootNode: AccessibilityNodeInfo?,
        condition: (AccessibilityNodeInfo) -> Boolean,
        maxDepth: Int = 10
    ): AccessibilityNodeInfo? {
        if (rootNode == null) return null
        return findNodeByConditionRecursive(rootNode, condition, 0, maxDepth)
    }
    
    private fun findNodeByConditionRecursive(
        node: AccessibilityNodeInfo,
        condition: (AccessibilityNodeInfo) -> Boolean,
        depth: Int,
        maxDepth: Int
    ): AccessibilityNodeInfo? {
        if (depth > maxDepth) return null
        
        if (condition(node)) {
            return node
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val result = findNodeByConditionRecursive(child, condition, depth + 1, maxDepth)
                if (result != null) {
                    return result
                }
                child.recycle()
            }
        }
        
        return null
    }
    
    /**
     * 查找所有满足条件的节点
     */
    fun findNodesByCondition(
        rootNode: AccessibilityNodeInfo?,
        condition: (AccessibilityNodeInfo) -> Boolean,
        maxDepth: Int = 10
    ): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        if (rootNode == null) return results
        
        findNodesByConditionRecursive(rootNode, condition, results, 0, maxDepth)
        return results
    }
    
    private fun findNodesByConditionRecursive(
        node: AccessibilityNodeInfo,
        condition: (AccessibilityNodeInfo) -> Boolean,
        results: MutableList<AccessibilityNodeInfo>,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth) return
        
        if (condition(node)) {
            results.add(node)
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findNodesByConditionRecursive(child, condition, results, depth + 1, maxDepth)
                if (child !in results) {
                    child.recycle()
                }
            }
        }
    }
    
    /**
     * 安全地 recycle 节点列表
     */
    fun recycleNodes(nodes: List<AccessibilityNodeInfo?>) {
        nodes.forEach { node ->
            try {
                node?.recycle()
            } catch (e: Exception) {
                Log.w(TAG, "Error recycling node", e)
            }
        }
    }
}
