package com.example.a11yframework

import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.a11yframework.appplugin.AppPluginManager
import com.example.a11yframework.core.FrameworkAccessibilityService
import com.example.a11yframework.core.ScrapedData
import com.example.a11yframework.data.DataStore
import kotlin.concurrent.thread

/**
 * 主界面
 * 
 * 功能：
 * - 开启/关闭无障碍服务
 * - 查看数据统计
 * - 导出数据
 * - 配置插件
 */
class MainActivity : AppCompatActivity() {
    
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var pluginStatusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var exportButton: Button
    
    private var dataStore: DataStore? = null
    private lateinit var appPluginManager: AppPluginManager

    private val pluginImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            Toast.makeText(this, "未选择插件包", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        importPluginFromUri(uri)
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            
            dataStore = DataStore(this)
            appPluginManager = AppPluginManager(this)
            
            initViews()
            setupListeners()
        } catch (e: Exception) {
            Toast.makeText(this, "启动失败：${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }
    
    override fun onResume() {
        super.onResume()
        updateStatus()
        updateStats()
        updatePluginStatus()
    }
    
    private fun initViews() {
        statusText = findViewById(R.id.statusText)
        statsText = findViewById(R.id.statsText)
        pluginStatusText = findViewById(R.id.pluginStatusText)
        toggleButton = findViewById(R.id.toggleButton)
        exportButton = findViewById(R.id.exportButton)
    }
    
    private fun setupListeners() {
        // 切换服务开关
        toggleButton.setOnClickListener {
            if (isAccessibilityServiceEnabled()) {
                disableAccessibilityService()
            } else {
                openAccessibilitySettings()
            }
        }
        
        // 导出数据
        exportButton.setOnClickListener {
            exportData()
        }

        findViewById<Button>(R.id.importPluginButton).setOnClickListener {
            pluginImportLauncher.launch(arrayOf("application/zip", "application/json", "*/*"))
        }

        findViewById<Button>(R.id.scanPluginInboxButton).setOnClickListener {
            scanPluginInbox()
        }

        findViewById<Button>(R.id.reloadPluginButton).setOnClickListener {
            reloadPlugins()
        }

        findViewById<Button>(R.id.rollbackPluginButton).setOnClickListener {
            rollbackLastPlugin()
        }
        
        // 保存规则
        findViewById<Button>(R.id.saveRulesButton).setOnClickListener {
            saveRules()
        }
        
        // 生成美团测试数据
        findViewById<Button>(R.id.testMeituanButton).setOnClickListener {
            generateTestMeituanData()
        }
        
        // 生成抖音测试数据
        findViewById<Button>(R.id.testDouyinButton).setOnClickListener {
            generateTestDouyinData()
        }
    }
    
    /**
     * 更新服务状态显示
     */
    private fun updateStatus() {
        val isEnabled = isAccessibilityServiceEnabled()
        
        if (isEnabled) {
            statusText.text = getString(R.string.service_status, getString(R.string.service_running))
            toggleButton.text = "停止服务"
        } else {
            statusText.text = getString(R.string.service_status, getString(R.string.service_stopped))
            toggleButton.text = "启动服务"
        }
    }
    
    /**
     * 更新数据统计
     */
    private fun updateStats() {
        try {
            val stats = dataStore?.getStats()
            val total = stats?.get("total") as? Int ?: 0
            val byPlugin = stats?.get("byPlugin") as? Map<String, Int> ?: emptyMap()
            
            val sb = StringBuilder()
            sb.append("总记录数：$total\n\n")
            sb.append("按插件:\n")
            
            byPlugin.forEach { (pluginId, count) ->
                val pluginName = when (pluginId) {
                    "meituan" -> "美团"
                    "douyin" -> "抖音"
                    else -> pluginId
                }
                sb.append("  $pluginName: $count 条\n")
            }
            
            statsText.text = sb.toString()
        } catch (e: Exception) {
            statsText.text = "暂无数据"
        }
    }

    private fun updatePluginStatus() {
        try {
            appPluginManager.reloadPlugins()
            val statuses = appPluginManager.getRuntimePluginStatuses()
            val inboxPath = appPluginManager.getImportInboxDir().absolutePath
            val summary = buildString {
                append("导入目录：\n")
                append(inboxPath)
                append("\n\n")
                if (statuses.isEmpty()) {
                    append("当前没有已安装插件")
                } else {
                    append("已安装插件：\n")
                    statuses.forEach { status ->
                        append("• ${status.pluginName} (${status.pluginId}) v${status.version}")
                        append(if (status.enabled) " [启用]" else " [停用]")
                        append("\n")
                        append("  包名: ${status.appPackages.joinToString(", ")}\n")
                        append("  规则: ${status.ruleCount}，备份: ${status.backupCount}\n")
                    }
                }
            }
            pluginStatusText.text = summary
        } catch (e: Exception) {
            pluginStatusText.text = "插件状态读取失败：${e.message}"
        }
    }
    
    /**
     * 检查无障碍服务是否启用
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        
        for (service in enabledServices) {
            if (service.resolveInfo.serviceInfo.packageName == packageName) {
                return true
            }
        }
        return false
    }
    
    /**
     * 打开无障碍设置页面
     */
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(this, "请在设置中找到 \"${getString(R.string.accessibility_service_name)}\" 并开启", Toast.LENGTH_LONG).show()
    }
    
    /**
     * 禁用无障碍服务（需要用户手动操作）
     */
    private fun disableAccessibilityService() {
        openAccessibilitySettings()
    }
    
    /**
     * 导出数据
     */
    private fun exportData() {
        try {
            val json = dataStore?.exportToJson() ?: run {
                Toast.makeText(this, "暂无数据可导出", Toast.LENGTH_SHORT).show()
                return
            }
            
            val file = getFileStreamPath("exported_data.json")
            file.writeText(json)
            
            Toast.makeText(this, "数据已导出到：${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importPluginFromUri(uri: Uri) {
        thread {
            val result = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    val displayName = queryDisplayName(uri)
                    appPluginManager.importPluginPackage(displayName, input)
                } ?: throw IllegalStateException("无法读取插件文件")
            }

            runOnUiThread {
                result.onSuccess { installResult ->
                    if (installResult.success) {
                        persistLastPluginId(installResult.pluginId)
                        syncRuntimePluginsIfNeeded()
                        updatePluginStatus()
                        Toast.makeText(
                            this,
                            "插件已导入：${installResult.pluginName} v${installResult.version}",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this,
                            "插件导入失败：${installResult.errorMessage}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        "插件导入失败：${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun scanPluginInbox() {
        thread {
            val result = runCatching {
                appPluginManager.installPendingPluginPackages()
            }

            runOnUiThread {
                result.onSuccess { batchResult ->
                    val lastSuccess = batchResult.results.lastOrNull { it.success }
                    if (lastSuccess != null) {
                        persistLastPluginId(lastSuccess.pluginId)
                        syncRuntimePluginsIfNeeded()
                    }

                    updatePluginStatus()
                    val message = when {
                        batchResult.results.isEmpty() -> "导入目录里没有待安装插件包"
                        batchResult.failureCount == 0 ->
                            "已安装 ${batchResult.successCount} 个插件包"
                        else ->
                            "安装完成：成功 ${batchResult.successCount}，失败 ${batchResult.failureCount}"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    Toast.makeText(this, "扫描失败：${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun reloadPlugins() {
        val reloadedCount = FrameworkAccessibilityService.instance?.reloadRuntimePlugins()
        if (reloadedCount == null) {
            appPluginManager.reloadPlugins()
            updatePluginStatus()
            Toast.makeText(this, "插件目录已重载，服务启动后会生效", Toast.LENGTH_LONG).show()
        } else {
            updatePluginStatus()
            Toast.makeText(this, "已重载 $reloadedCount 个插件", Toast.LENGTH_LONG).show()
        }
    }

    private fun rollbackLastPlugin() {
        val pluginId = getLastPluginId()
        if (pluginId.isNullOrBlank()) {
            Toast.makeText(this, "还没有可回滚的最近插件", Toast.LENGTH_LONG).show()
            return
        }

        thread {
            val result = appPluginManager.rollbackPlugin(pluginId)
            runOnUiThread {
                if (result.success) {
                    syncRuntimePluginsIfNeeded()
                    updatePluginStatus()
                    Toast.makeText(
                        this,
                        "已回滚 ${result.pluginName} 到 v${result.version}",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        "回滚失败：${result.errorMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun syncRuntimePluginsIfNeeded() {
        FrameworkAccessibilityService.instance?.reloadRuntimePlugins()
    }

    private fun persistLastPluginId(pluginId: String) {
        getSharedPreferences("plugin_admin", Context.MODE_PRIVATE)
            .edit()
            .putString("last_plugin_id", pluginId)
            .apply()
    }

    private fun getLastPluginId(): String? {
        return getSharedPreferences("plugin_admin", Context.MODE_PRIVATE)
            .getString("last_plugin_id", null)
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (columnIndex >= 0) {
                        return cursor.getString(columnIndex)
                    }
                }
            }
        return uri.lastPathSegment
    }
    
    /**
     * 保存抓取规则
     */
    private fun saveRules() {
        try {
            val keywordsText = findViewById<EditText>(R.id.keywordsEdit).text.toString()
            val keywords = keywordsText.split("，", ",", " ", "\n").map { it.trim() }.filter { it.isNotEmpty() }
            
            if (keywords.isEmpty()) {
                Toast.makeText(this, "请输入至少一个关键词", Toast.LENGTH_SHORT).show()
                return
            }
            
            // 保存到两个插件
            val configManager = FrameworkAccessibilityService.instance?.configManager
            configManager?.setPluginConfigMap("meituan", mapOf("keywords" to keywords))
            configManager?.setPluginConfigMap("douyin", mapOf("keywords" to keywords))
            
            Toast.makeText(this, "✅ 规则已保存：${keywords.joinToString(", ")}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 生成美团测试数据
     */
    private fun generateTestMeituanData() {
        try {
            val testData = listOf(
                ScrapedData(
                    pluginId = "meituan",
                    pageType = "shop_list",
                    dataType = "group_buy",
                    content = mapOf(
                        "shopName" to "测试餐厅 1 号店",
                        "price" to "99",
                        "groupBuyTitle" to "双人豪华套餐",
                        "rawText" to "测试餐厅 1 号店 双人豪华套餐 ¥99"
                    )
                ),
                ScrapedData(
                    pluginId = "meituan",
                    pageType = "shop_list",
                    dataType = "group_buy",
                    content = mapOf(
                        "shopName" to "测试餐厅 2 号店",
                        "price" to "158",
                        "groupBuyTitle" to "四人聚餐套餐",
                        "rawText" to "测试餐厅 2 号店 四人聚餐套餐 ¥158"
                    )
                )
            )
            
            dataStore?.saveData(testData)
            Toast.makeText(this, "已生成 ${testData.size} 条美团测试数据", Toast.LENGTH_LONG).show()
            updateStats()
        } catch (e: Exception) {
            Toast.makeText(this, "生成失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 生成抖音测试数据
     */
    private fun generateTestDouyinData() {
        try {
            val testData = listOf(
                ScrapedData(
                    pluginId = "douyin",
                    pageType = "feed",
                    dataType = "group_buy",
                    content = mapOf(
                        "groupBuyTitle" to "抖音团购测试 - 99 元双人餐",
                        "price" to "99",
                        "rawText" to "抖音团购测试 - 99 元双人餐 已售 1000+"
                    )
                ),
                ScrapedData(
                    pluginId = "douyin",
                    pageType = "feed",
                    dataType = "group_buy",
                    content = mapOf(
                        "groupBuyTitle" to "抖音团购测试 - 199 元套餐",
                        "price" to "199",
                        "rawText" to "抖音团购测试 - 199 元套餐 已售 500+"
                    )
                )
            )
            
            dataStore?.saveData(testData)
            Toast.makeText(this, "已生成 ${testData.size} 条抖音测试数据", Toast.LENGTH_LONG).show()
            updateStats()
        } catch (e: Exception) {
            Toast.makeText(this, "生成失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
