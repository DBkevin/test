package com.example.a11yframework.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 配置管理器
 * 
 * 管理插件配置、全局设置
 * 使用 SharedPreferences 存储
 */
class ConfigManager(context: Context) {
    
    companion object {
        private const val TAG = "ConfigManager"
        private const val PREF_NAME = "a11y_framework_config"
        
        // 全局配置 key
        private const val KEY_ENABLED_PLUGINS = "enabled_plugins"
        private const val KEY_SCRAPE_INTERVAL = "scrape_interval"
        private const val KEY_LOG_LEVEL = "log_level"
    }
    
    private val prefs: SharedPreferences
    private val gson = Gson()
    
    init {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
    
    // ==================== 全局配置 ====================
    
    /**
     * 获取启用的插件列表
     */
    fun getEnabledPlugins(): Set<String> {
        return prefs.getStringSet(KEY_ENABLED_PLUGINS, emptySet()) ?: emptySet()
    }
    
    /**
     * 设置启用的插件列表
     */
    fun setEnabledPlugins(pluginIds: Set<String>) {
        prefs.edit().putStringSet(KEY_ENABLED_PLUGINS, pluginIds).apply()
        Log.d(TAG, "Enabled plugins updated: $pluginIds")
    }
    
    /**
     * 启用/禁用单个插件
     */
    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        val current = getEnabledPlugins().toMutableSet()
        if (enabled) {
            current.add(pluginId)
        } else {
            current.remove(pluginId)
        }
        setEnabledPlugins(current)
    }
    
    /**
     * 检查插件是否启用
     */
    fun isPluginEnabled(pluginId: String): Boolean {
        return pluginId in getEnabledPlugins()
    }
    
    /**
     * 获取抓取间隔（毫秒）
     */
    fun getScrapeInterval(): Long {
        return prefs.getLong(KEY_SCRAPE_INTERVAL, 3000L)  // 默认 3 秒
    }
    
    /**
     * 设置抓取间隔
     */
    fun setScrapeInterval(intervalMs: Long) {
        prefs.edit().putLong(KEY_SCRAPE_INTERVAL, intervalMs).apply()
    }
    
    // ==================== 插件专用配置 ====================
    
    /**
     * 获取插件配置（JSON 字符串）
     */
    fun getPluginConfig(pluginId: String): String? {
        val key = "plugin_config_$pluginId"
        return prefs.getString(key, null)
    }
    
    /**
     * 设置插件配置（JSON 字符串）
     */
    fun setPluginConfig(pluginId: String, configJson: String) {
        val key = "plugin_config_$pluginId"
        prefs.edit().putString(key, configJson).apply()
        Log.d(TAG, "Plugin config saved: $pluginId")
    }
    
    /**
     * 获取插件配置（解析为 Map）
     */
    fun getPluginConfigMap(pluginId: String): Map<String, Any> {
        val json = getPluginConfig(pluginId) ?: return emptyMap()
        return try {
            gson.fromJson(json, object : TypeToken<Map<String, Any>>() {}.type)
        } catch (e: Exception) {
            emptyMap()
        }
    }
    
    /**
     * 设置插件配置（从 Map）
     */
    fun setPluginConfigMap(pluginId: String, config: Map<String, Any>) {
        val json = gson.toJson(config)
        setPluginConfig(pluginId, json)
    }
    
    /**
     * 获取插件的字符串配置项
     */
    fun getPluginConfigString(pluginId: String, key: String, default: String = ""): String {
        val config = getPluginConfigMap(pluginId)
        return config[key]?.toString() ?: default
    }
    
    /**
     * 获取插件的数字配置项
     */
    fun getPluginConfigInt(pluginId: String, key: String, default: Int = 0): Int {
        val config = getPluginConfigMap(pluginId)
        return (config[key] as? Number)?.toInt() ?: default
    }
    
    /**
     * 获取插件的布尔配置项
     */
    fun getPluginConfigBool(pluginId: String, key: String, default: Boolean = false): Boolean {
        val config = getPluginConfigMap(pluginId)
        return config[key] as? Boolean ?: default
    }
    
    /**
     * 获取插件的列表配置项
     */
    fun getPluginConfigList(pluginId: String, key: String): List<String> {
        val config = getPluginConfigMap(pluginId)
        return (config[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
    }
    
    // ==================== 工具方法 ====================
    
    /**
     * 清除所有配置
     */
    fun clearAll() {
        prefs.edit().clear().apply()
        Log.i(TAG, "All configs cleared")
    }
    
    /**
     * 导出所有配置为 JSON
     */
    fun exportAllToJson(): String {
        val allPrefs = prefs.all
        return gson.toJson(allPrefs)
    }
    
    /**
     * 从 JSON 导入配置
     */
    @Suppress("UNCHECKED_CAST")
    fun importFromJson(json: String) {
        try {
            val configMap = gson.fromJson(json, object : TypeToken<Map<String, Any>>() {}.type) as Map<String, Any>
            val editor = prefs.edit()
            
            configMap.forEach { entry ->
                val key = entry.key
                val value = entry.value
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                    else -> Log.w(TAG, "Unknown type for key $key: ${value?.javaClass}")
                }
            }
            
            editor.apply()
            Log.i(TAG, "Config imported")
        } catch (e: Exception) {
            Log.e(TAG, "Error importing config", e)
        }
    }
}
