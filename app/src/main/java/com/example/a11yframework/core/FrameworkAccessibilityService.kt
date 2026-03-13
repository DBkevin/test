package com.example.a11yframework.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.example.a11yframework.appplugin.AppPluginManager
import com.example.a11yframework.appplugin.PluginInstallResult
import com.example.a11yframework.capture.CaptureCoordinator
import com.example.a11yframework.capture.CaptureExecutionResult
import com.example.a11yframework.config.ConfigManager
import com.example.a11yframework.data.DataStore
import com.example.a11yframework.plugins.MeituanPlugin
import com.example.a11yframework.plugins.DouyinPlugin
import com.example.a11yframework.remote.RemoteCommandManager
import com.example.a11yframework.remote.HospitalTask
import com.example.a11yframework.remote.TaskStatus
import com.example.a11yframework.rule.engine.RuleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 核心无障碍服务
 */
class FrameworkAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "A11yFramework"
        var instance: FrameworkAccessibilityService? = null
            private set
    }
    
    val appPluginManager by lazy { AppPluginManager(this) }
    private val pluginManager = PluginManager()
    private val captureCoordinator by lazy { CaptureCoordinator(this) }
    private val remoteCommandManager by lazy { RemoteCommandManager(this) }
    private val ruleEngine by lazy { RuleEngine(this) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
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

            appPluginManager.installBundledPlugins()
            Log.i(TAG, "App plugin manager ready, loaded ${appPluginManager.getPluginCount()} bundles")
            
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

            Log.i(TAG, "Rule engine ready, loaded ${ruleEngine.getRuleCount()} rules")
            setupRemoteCommandFlow()
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
        captureCoordinator.onWindowChanged(packageName)
        
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
        val cooldownMs = captureCoordinator.getScrapeCooldownMs(packageName, SCRAPE_COOLDOWN)
        if (now - lastScrapeTime < cooldownMs) return

        val hasRule = ruleEngine.hasRulesForPackage(packageName)
        val plugin = activePlugin
        if (!hasRule && plugin == null) return
        if (!hasRule && plugin != null && packageName !in plugin.targetPackages) return
        
        val rootNode = rootInActiveWindow ?: return
        
        try {
            if (!captureCoordinator.shouldScrapePage(packageName)) {
                Log.d(TAG, "Capture stage not ready for scraping: $packageName")
                return
            }

            if (hasRule) {
                val ruleResult = ruleEngine.execute(packageName, rootNode)
                if (ruleResult.matched) {
                    val preparedRuleData = captureCoordinator.prepareCapturedRecords(
                        packageName,
                        ruleResult.data
                    )

                    if (preparedRuleData.isNotEmpty()) {
                        val appendResult = captureCoordinator.onRecordsCaptured(
                            packageName,
                            preparedRuleData
                        )
                        if (captureCoordinator.isCollectingForPackage(packageName)) {
                            Log.i(
                                TAG,
                                "Rule collected ${preparedRuleData.size} records from ${ruleResult.ruleId}, added=${appendResult.addedCount}, updated=${appendResult.updatedCount}"
                            )
                        } else {
                            dataStore.saveData(preparedRuleData)
                            Log.i(
                                TAG,
                                "Rule scraped ${preparedRuleData.size} records from ${ruleResult.ruleId}"
                            )
                        }
                        lastScrapeTime = now
                        return
                    } else {
                        Log.d(TAG, "Rule matched but no data extracted: ${ruleResult.ruleId}")
                    }

                    if (!captureCoordinator.isCollectingForPackage(packageName)) {
                        lastScrapeTime = now
                        return
                    }
                }
            }

            val activePlugin = plugin ?: return
            if (!activePlugin.isTargetPage(rootNode)) {
                Log.d(TAG, "Not a target page, skipping")
                return
            }
            
            Log.d(TAG, "Target page detected, scraping...")
            
            val rawData = activePlugin.scrapeData(rootNode)
            
            if (rawData.isNotEmpty()) {
                val processedData = activePlugin.processData(rawData)
                val preparedPluginData = captureCoordinator.prepareCapturedRecords(
                    packageName,
                    processedData
                )
                if (preparedPluginData.isNotEmpty()) {
                    val appendResult = captureCoordinator.onRecordsCaptured(
                        packageName,
                        preparedPluginData
                    )
                    if (captureCoordinator.isCollectingForPackage(packageName)) {
                        Log.i(
                            TAG,
                            "Collected ${preparedPluginData.size} plugin records, added=${appendResult.addedCount}, updated=${appendResult.updatedCount}"
                        )
                    } else {
                        dataStore.saveData(preparedPluginData)
                        Log.i(TAG, "Scraped ${preparedPluginData.size} records")
                    }
                }
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
        captureCoordinator.cancelActiveCapture("service destroyed")
        remoteCommandManager.stopPolling()
        serviceScope.cancel()
        pluginManager.getAllPlugins().forEach { it.cleanup() }
        instance = null
        super.onDestroy()
    }
    
    private fun registerPlugins() {
        // 旧版 Kotlin 插件仍保留为兼容兜底。
        pluginManager.registerPlugin(MeituanPlugin())
        pluginManager.registerPlugin(DouyinPlugin())
    }

    private fun setupRemoteCommandFlow() {
        remoteCommandManager.taskExecutor = { task ->
            captureCoordinator.executeTask(task)
        }
        remoteCommandManager.onExecutionStopped = {
            captureCoordinator.cancelActiveCapture("remote stop command")
        }
        remoteCommandManager.startPolling()
    }

    fun launchTargetApp(packageName: String): Boolean {
        return try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                Log.e(TAG, "Launch intent not found: $packageName")
                false
            } else {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch target app: $packageName", e)
            false
        }
    }

    fun getCurrentActivePackage(): String = lastPackageName

    fun reloadRuntimePlugins(): Int {
        appPluginManager.reloadPlugins()
        ruleEngine.reload()
        Log.i(TAG, "Runtime plugins reloaded: ${appPluginManager.getPluginCount()}")
        return appPluginManager.getPluginCount()
    }

    fun installRuntimePlugin(
        manifestJson: String,
        ruleFiles: Map<String, String>
    ): PluginInstallResult {
        val result = appPluginManager.installOrUpdatePlugin(
            manifestJson = manifestJson,
            ruleFiles = ruleFiles
        )

        if (result.success) {
            ruleEngine.reload()
            Log.i(TAG, "Runtime plugin installed: ${result.pluginId}@${result.version}")
        } else {
            Log.w(TAG, "Runtime plugin install failed: ${result.errorMessage}")
        }

        return result
    }

    fun startLocalCapture(
        hospitalName: String,
        targetPackage: String = "com.ss.android.ugc.aweme",
        onCompleted: ((CaptureExecutionResult) -> Unit)? = null
    ): Boolean {
        val normalizedHospitalName = hospitalName.trim()
        if (normalizedHospitalName.isBlank()) {
            return false
        }

        val task = HospitalTask(
            id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            hospitalName = normalizedHospitalName,
            status = TaskStatus.PENDING,
            createdAt = System.currentTimeMillis(),
            targetPackage = targetPackage
        )

        serviceScope.launch {
            val result = captureCoordinator.executeTask(task)
            onCompleted?.let { callback ->
                Handler(Looper.getMainLooper()).post {
                    callback(result)
                }
            }
        }

        return true
    }
}
