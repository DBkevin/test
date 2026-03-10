package com.example.a11yframework.core

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍插件接口
 * 
 * 每个目标 APP 需要实现一个插件，定义：
 * - 如何识别目标 APP
 * - 如何抓取数据
 * - 如何处理数据
 */
interface IAccessibilityPlugin {
    
    /**
     * 插件唯一标识
     * 例如："meituan", "douyin"
     */
    val pluginId: String
    
    /**
     * 插件名称（人类可读）
     */
    val pluginName: String
    
    /**
     * 目标 APP 包名列表
     * 例如：["com.sankuai.meituan", "com.sankuai.meituan.takeoutnew"]
     */
    val targetPackages: List<String>
    
    /**
     * 初始化插件
     * 在服务启动时调用，可加载配置、初始化数据库等
     */
    fun initialize(service: AccessibilityService)
    
    /**
     * 清理资源
     * 在服务停止时调用
     */
    fun cleanup()
    
    /**
     * 检查当前界面是否是需要抓取的页面
     * 
     * @param nodeInfo 当前窗口的根节点
     * @return true 表示需要处理，false 表示忽略
     */
    fun isTargetPage(nodeInfo: AccessibilityNodeInfo?): Boolean
    
    /**
     * 执行数据抓取
     * 
     * @param nodeInfo 当前窗口的根节点
     * @return 抓取到的数据列表
     */
    fun scrapeData(nodeInfo: AccessibilityNodeInfo?): List<ScrapedData>
    
    /**
     * 处理抓取到的数据
     * 可以过滤、转换、增强数据
     */
    fun processData(data: List<ScrapedData>): List<ScrapedData>
    
    /**
     * 当插件被激活时调用（切换到目标 APP）
     */
    fun onActivate()
    
    /**
     * 当插件被停用时调用（离开目标 APP）
     */
    fun onDeactivate()
}

/**
 * 抓取到的数据结构
 */
data class ScrapedData(
    val timestamp: Long = System.currentTimeMillis(),
    val pluginId: String,
    val pageType: String,          // 页面类型，如 "shop_list", "search_result"
    val dataType: String,          // 数据类型，如 "group_buy", "ranking"
    val content: Map<String, String>,  // 实际数据，key-value 形式
    val rawText: String = "",      // 原始文本（可选）
    val metadata: Map<String, Any> = emptyMap()  // 元数据
)
