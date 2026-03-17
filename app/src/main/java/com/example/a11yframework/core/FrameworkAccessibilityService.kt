package com.example.a11yframework.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
import kotlinx.coroutines.CancellationException
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
        private const val LOCAL_CAPTURE_PREFS = "local_capture_state"
        private const val KEY_PENDING_HOSPITAL_NAME = "pending_hospital_name"
        private const val KEY_PENDING_TARGET_PACKAGE = "pending_target_package"
        private const val KEY_PENDING_LAUNCH_TARGET_APP = "pending_launch_target_app"
        private const val KEY_PENDING_UPDATED_AT = "pending_updated_at"
        private const val PENDING_CAPTURE_TTL_MS = 10 * 60 * 1000L
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
    @Volatile
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
            resumePendingLocalCaptureIfNeeded()
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
        scrapeCurrentWindow(packageName, force = false)
    }

    private fun scrapeCurrentWindow(
        packageName: String,
        force: Boolean
    ): Boolean {
        val now = System.currentTimeMillis()
        val cooldownMs = captureCoordinator.getScrapeCooldownMs(packageName, SCRAPE_COOLDOWN)
        if (!force && now - lastScrapeTime < cooldownMs) return false

        val hasRule = ruleEngine.hasRulesForPackage(packageName)
        val plugin = activePlugin ?: pluginManager.findPluginForPackage(packageName)?.also { fallbackPlugin ->
            if (activePlugin == null) {
                Log.i(TAG, "Activating plugin from current package snapshot: ${fallbackPlugin.pluginName}")
                activePlugin = fallbackPlugin
                fallbackPlugin.onActivate()
            }
        }
        if (!hasRule && plugin == null) return false
        if (!hasRule && plugin != null && packageName !in plugin.targetPackages) return false
        
        val rootNode = rootInActiveWindow ?: return false
        var scraped = false
        
        try {
            if (!captureCoordinator.shouldScrapePage(packageName)) {
                Log.d(TAG, "Capture stage not ready for scraping: $packageName")
                return false
            }

            if (force) {
                Log.d(TAG, "Force scraping current page snapshot: $packageName")
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
                        return true
                    } else {
                        Log.d(TAG, "Rule matched but no data extracted: ${ruleResult.ruleId}")
                    }

                    if (!captureCoordinator.isCollectingForPackage(packageName)) {
                        lastScrapeTime = now
                        return false
                    }
                }
            }

            val activePlugin = plugin ?: return false
            if (!activePlugin.isTargetPage(rootNode)) {
                Log.d(TAG, "Not a target page, skipping")
                return false
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
                    scraped = true
                }
            }
            
            lastScrapeTime = now
            return scraped
        } catch (e: Exception) {
            Log.e(TAG, "Error scraping data", e)
            return false
        } finally {
            rootNode.recycle()
        }
    }

    fun scrapeCurrentPageNow(packageName: String): Boolean {
        return scrapeCurrentWindow(packageName, force = true)
    }

    fun isCurrentTargetPage(packageName: String): Boolean {
        val plugin = activePlugin ?: pluginManager.findPluginForPackage(packageName) ?: return false
        val rootNode = rootInActiveWindow ?: return false

        return try {
            val rootPackage = rootNode.packageName?.toString().orEmpty()
            rootPackage == packageName && plugin.isTargetPage(rootNode)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect current target page", e)
            false
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

    fun getCurrentActivePackage(): String {
        val rootNode = rootInActiveWindow
        try {
            val packageFromRoot = rootNode?.packageName?.toString()?.trim().orEmpty()
            if (packageFromRoot.isNotEmpty()) {
                return packageFromRoot
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect active root package", e)
        } finally {
            rootNode?.recycle()
        }

        return lastPackageName
    }

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
        launchTargetApp: Boolean = false,
        onCompleted: ((CaptureExecutionResult) -> Unit)? = null
    ): Boolean {
        val normalizedHospitalName = hospitalName.trim()
        if (normalizedHospitalName.isBlank()) {
            return false
        }

        persistPendingLocalCapture(
            hospitalName = normalizedHospitalName,
            targetPackage = targetPackage,
            launchTargetApp = launchTargetApp
        )

        executePendingLocalCapture(
            pendingCapture = PendingLocalCapture(
                hospitalName = normalizedHospitalName,
                targetPackage = targetPackage,
                launchTargetApp = launchTargetApp
            ),
            onCompleted = onCompleted
        )

        return true
    }

    private fun executePendingLocalCapture(
        pendingCapture: PendingLocalCapture,
        onCompleted: ((CaptureExecutionResult) -> Unit)? = null
    ) {
        val task = HospitalTask(
            id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            hospitalName = pendingCapture.hospitalName,
            status = TaskStatus.PENDING,
            createdAt = System.currentTimeMillis(),
            targetPackage = pendingCapture.targetPackage
        )

        serviceScope.launch {
            try {
                val result = captureCoordinator.executeTask(
                    task = task,
                    launchTargetApp = pendingCapture.launchTargetApp
                )
                clearPendingLocalCapture()
                onCompleted?.let { callback ->
                    Handler(Looper.getMainLooper()).post {
                        callback(result)
                    }
                }
            } catch (e: CancellationException) {
                Log.i(
                    TAG,
                    "Local capture interrupted, keep pending state for resume: ${pendingCapture.hospitalName}"
                )
                throw e
            }
        }
    }

    private fun resumePendingLocalCaptureIfNeeded() {
        if (captureCoordinator.hasActiveCapture()) {
            return
        }

        val pendingCapture = readPendingLocalCapture() ?: return
        Log.i(
            TAG,
            "Resuming pending local capture after service reconnect: hospital=${pendingCapture.hospitalName}, target=${pendingCapture.targetPackage}, launch=${pendingCapture.launchTargetApp}"
        )
        executePendingLocalCapture(pendingCapture)
    }

    private fun localCapturePrefs() = getSharedPreferences(LOCAL_CAPTURE_PREFS, MODE_PRIVATE)

    private fun persistPendingLocalCapture(
        hospitalName: String,
        targetPackage: String,
        launchTargetApp: Boolean
    ) {
        localCapturePrefs().edit()
            .putString(KEY_PENDING_HOSPITAL_NAME, hospitalName)
            .putString(KEY_PENDING_TARGET_PACKAGE, targetPackage)
            .putBoolean(KEY_PENDING_LAUNCH_TARGET_APP, launchTargetApp)
            .putLong(KEY_PENDING_UPDATED_AT, System.currentTimeMillis())
            .apply()
        Log.i(
            TAG,
            "Pending local capture saved: hospital=$hospitalName, target=$targetPackage, launch=$launchTargetApp"
        )
    }

    private fun clearPendingLocalCapture() {
        val prefs = localCapturePrefs()
        if (!prefs.contains(KEY_PENDING_HOSPITAL_NAME)) {
            return
        }

        prefs.edit()
            .remove(KEY_PENDING_HOSPITAL_NAME)
            .remove(KEY_PENDING_TARGET_PACKAGE)
            .remove(KEY_PENDING_LAUNCH_TARGET_APP)
            .remove(KEY_PENDING_UPDATED_AT)
            .apply()
        Log.i(TAG, "Pending local capture cleared")
    }

    private fun readPendingLocalCapture(): PendingLocalCapture? {
        val prefs = localCapturePrefs()
        val hospitalName = prefs.getString(KEY_PENDING_HOSPITAL_NAME, null)?.trim().orEmpty()
        if (hospitalName.isEmpty()) {
            return null
        }

        val updatedAt = prefs.getLong(KEY_PENDING_UPDATED_AT, 0L)
        if (updatedAt > 0L && System.currentTimeMillis() - updatedAt > PENDING_CAPTURE_TTL_MS) {
            Log.i(TAG, "Pending local capture expired, clearing stale state: $hospitalName")
            clearPendingLocalCapture()
            return null
        }

        return PendingLocalCapture(
            hospitalName = hospitalName,
            targetPackage = prefs.getString(
                KEY_PENDING_TARGET_PACKAGE,
                "com.ss.android.ugc.aweme"
            ) ?: "com.ss.android.ugc.aweme",
            launchTargetApp = prefs.getBoolean(KEY_PENDING_LAUNCH_TARGET_APP, false)
        )
    }

    private data class PendingLocalCapture(
        val hospitalName: String,
        val targetPackage: String,
        val launchTargetApp: Boolean
    )
}
