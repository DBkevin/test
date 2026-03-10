package com.example.a11yframework.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.example.a11yframework.config.ConfigManager
import com.example.a11yframework.data.DataStore
import com.example.a11yframework.plugins.MeituanPlugin
import com.example.a11yframework.plugins.DouyinPlugin

/**
 * 核心无障碍服务
 */
class FrameworkAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "A11yFramework"
        var instance: FrameworkAccessibilityService? = null
            private set
    }
    
    private val pluginManager = PluginManager()
    
    // 延迟初始化
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
    
    private var activePlugin: IAccessibilityPlugin? = null
    private var lastScrapeTime = 0L
    private var lastPackageName: String = ""
    private val SCRAPE_COOLDOWN = 3000L
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "Service created")
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            // 配置服务
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
        
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleWindowChange(packageName)
        }
        
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            handleContentChange(packageName)
        }
    }
    
    private fun handleWindowChange(packageName: String) {
        if (packageName == lastPackageName) return
        
        lastPackageName = packageName
        Log.d(TAG, "Window changed to: $packageName")
        
        activePlugin?.onDeactivate()
        activePlugin = null
        
        val matchedPlugin = pluginManager.findPluginForPackage(packageName)
        matchedPlugin?.let { plugin ->
            Log.i(TAG, "Activating plugin: ${plugin.pluginName}")
            activePlugin = plugin
            plugin.onActivate()
        }
    }
    
    private fun handleContentChange(packageName: String) {
        val now = System.currentTimeMillis()
        if (now - lastScrapeTime < SCRAPE_COOLDOWN) return
        
        val plugin = activePlugin ?: return
        if (packageName !in plugin.targetPackages) return
        
        val rootNode = rootInActiveWindow ?: return
        
        try {
            if (!plugin.isTargetPage(rootNode)) {
                Log.d(TAG, "Not a target page, skipping")
                return
            }
            
            Log.d(TAG, "Target page detected, scraping...")
            
            val rawData = plugin.scrapeData(rootNode)
            
            if (rawData.isNotEmpty()) {
                val processedData = plugin.processData(rawData)
                dataStore.saveData(processedData)
                Log.i(TAG, "Scraped ${processedData.size} records")
            }
            
            lastScrapeTime = now
        } catch (e: Exception) {
            Log.e(TAG, "Error scraping data", e)
        } finally {
            rootNode.recycle()
        }
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }
    
    override fun onDestroy() {
        Log.i(TAG, "Service destroying")
        pluginManager.getAllPlugins().forEach { it.cleanup() }
        instance = null
        super.onDestroy()
    }
    
    private fun registerPlugins() {
        pluginManager.registerPlugin(MeituanPlugin())
        pluginManager.registerPlugin(DouyinPlugin())
    }
}
