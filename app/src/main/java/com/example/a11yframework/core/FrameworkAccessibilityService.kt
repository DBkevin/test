package com.example.a11yframework.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.example.a11yframework.config.ConfigManager
import com.example.a11yframework.data.DataStore
import com.example.a11yframework.plugins.MeituanPlugin
import com.example.a11yframework.plugins.DouyinPlugin

/**
 * 核心无障碍服务
 * 
 * 负责：
 * - 管理插件生命周期
 * - 监听窗口事件
 * - 调度抓取任务
 */
class FrameworkAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "A11yFramework"
        
        // 单例引用，方便插件访问服务
        var instance: FrameworkAccessibilityService? = null
            private set
    }
    
    private val pluginManager = PluginManager()
    
    // 延迟初始化（避免 by lazy 与方法签名冲突）
    private var _dataStore: DataStore? = null
    private var _configManager: ConfigManager? = null
    
    val dataStore: DataStore
        get() {
            if (_dataStore == null) {
                _dataStore = DataStore(this)
            }
            return _dataStore!!
        }
    
    val configManager: ConfigManager
        get() {
            if (_configManager == null) {
                _configManager = ConfigManager(this)
            }
            return _configManager!!
        }
    
    // 当前激活的插件
    private var activePlugin: IAccessibilityPlugin? = null
    
    // 防抖：避免同一页面重复抓取
    private var lastScrapeTime = 0L
    private var lastPackageName: String = ""
    private val SCRAPE_COOLDOWN = 3000L  // 3 秒冷却时间
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "Service created")
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        
        try {
            // 配置服务类型
            val info = AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                       AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                notificationTimeout = 100
            }
            serviceInfo = info
            
            Log.i(TAG, "Service connected")
            
            // 注册并初始化插件
            registerPlugins()
            pluginManager.getAllPlugins().forEach { plugin ->
                try {
                    plugin.initialize(this)
                    Log.i(TAG, "Plugin initialized: ${plugin.pluginName}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize plugin: ${plugin.pluginName}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onServiceConnected", e)
        }
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        val packageName = event.packageName?.toString() ?: return
        val eventType = event.eventType
        
        // 窗口状态变化时检查是否需要切换插件
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleWindowChange(packageName)
        }
        
        // 内容变化时尝试抓取
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            handleContentChange(packageName)
        }
    }
    
    /**
     * 处理窗口切换
     */
    private fun handleWindowChange(packageName: String) {
        if (packageName == lastPackageName) return
        
        lastPackageName = packageName
        Log.d(TAG, "Window changed to: $packageName")
        
        // 查找匹配的插件
        val matchedPlugin = pluginManager.findPluginForPackage(packageName)
        
        // 停用旧插件
        activePlugin?.onDeactivate()
        activePlugin = null
        
        // 激活新插件
        matchedPlugin?.let { plugin ->
            Log.i(TAG, "Activating plugin: ${plugin.pluginName}")
            activePlugin = plugin
            plugin.onActivate()
        }
    }
    
    /**
     * 处理内容变化（抓取数据）
     */
    private fun handleContentChange(packageName: String) {
        // 冷却检查
        val now = System.currentTimeMillis()
        if (now - lastScrapeTime < SCRAPE_COOLDOWN) return
        
        // 只处理目标 APP
        val plugin = activePlugin ?: return
        if (packageName !in plugin.targetPackages) return
        
        // 获取当前窗口内容
        val rootNode = rootInActiveWindow ?: return
        
        try {
            // 检查是否是目标页面
            if (!plugin.isTargetPage(rootNode)) {
                Log.d(TAG, "Not a target page, skipping")
                return
            }
            
            Log.d(TAG, "Target page detected, scraping...")
            
            // 抓取数据
            val rawData = plugin.scrapeData(rootNode)
            
            if (rawData.isNotEmpty()) {
                // 处理数据
                val processedData = plugin.processData(rawData)
                
                // 存储数据
                dataStore.saveData(processedData)
                
                Log.i(TAG, "Scraped ${processedData.size} records")
            }
            
            lastScrapeTime = now
        } finally {
            rootNode.recycle()
        }
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }
    
    override fun onDestroy() {
        Log.i(TAG, "Service destroying")
        
        // 清理所有插件
        pluginManager.getAllPlugins().forEach { it.cleanup() }
        
        instance = null
        super.onDestroy()
    }
    
    /**
     * 注册所有插件
     * 在这里添加新的插件
     */
    private fun registerPlugins() {
        pluginManager.registerPlugin(MeituanPlugin())
        pluginManager.registerPlugin(DouyinPlugin())
        // 添加新插件：pluginManager.registerPlugin(YourNewPlugin())
    }
}
