package com.example.a11yframework

import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.a11yframework.core.FrameworkAccessibilityService
import com.example.a11yframework.data.DataStore

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
    private lateinit var toggleButton: Button
    private lateinit var exportButton: Button
    private lateinit var settingsButton: Button
    
    private var dataStore: DataStore? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            
            dataStore = DataStore(this)
            
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
    }
    
    private fun initViews() {
        statusText = findViewById(R.id.statusText)
        statsText = findViewById(R.id.statsText)
        toggleButton = findViewById(R.id.toggleButton)
        exportButton = findViewById(R.id.exportButton)
        settingsButton = findViewById(R.id.settingsButton)
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
        
        // 设置
        settingsButton.setOnClickListener {
            Toast.makeText(this, "设置功能开发中", Toast.LENGTH_SHORT).show()
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
            
            // 保存到文件
            val file = getFileStreamPath("exported_data.json")
            file.writeText(json)
            
            Toast.makeText(this, "数据已导出到：${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
