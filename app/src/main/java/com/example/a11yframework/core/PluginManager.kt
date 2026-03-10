package com.example.a11yframework.core

import android.util.Log

/**
 * 插件管理器
 * 
 * 负责：
 * - 注册/注销插件
 * - 根据包名查找匹配的插件
 * - 管理插件状态
 */
class PluginManager {
    
    companion object {
        private const val TAG = "PluginManager"
    }
    
    // 已注册的插件列表
    private val plugins = mutableMapOf<String, IAccessibilityPlugin>()
    
    // 包名到插件的映射（缓存，提高查找速度）
    private val packageToPluginMap = mutableMapOf<String, String>()
    
    /**
     * 注册插件
     */
    fun registerPlugin(plugin: IAccessibilityPlugin) {
        if (plugin.pluginId in plugins) {
            Log.w(TAG, "Plugin ${plugin.pluginId} already registered, skipping")
            return
        }
        
        plugins[plugin.pluginId] = plugin
        Log.i(TAG, "Plugin registered: ${plugin.pluginName} (${plugin.pluginId})")
        
        // 建立包名映射
        plugin.targetPackages.forEach { packageName ->
            packageToPluginMap[packageName] = plugin.pluginId
        }
    }
    
    /**
     * 注销插件
     */
    fun unregisterPlugin(pluginId: String) {
        val plugin = plugins.remove(pluginId)
        if (plugin != null) {
            plugin.cleanup()
            plugin.targetPackages.forEach { packageName ->
                packageToPluginMap.remove(packageName)
            }
            Log.i(TAG, "Plugin unregistered: $pluginId")
        }
    }
    
    /**
     * 根据包名查找插件
     */
    fun findPluginForPackage(packageName: String): IAccessibilityPlugin? {
        val pluginId = packageToPluginMap[packageName]
        return pluginId?.let { plugins[it] }
    }
    
    /**
     * 获取所有插件
     */
    fun getAllPlugins(): List<IAccessibilityPlugin> {
        return plugins.values.toList()
    }
    
    /**
     * 获取指定插件
     */
    fun getPlugin(pluginId: String): IAccessibilityPlugin? {
        return plugins[pluginId]
    }
    
    /**
     * 检查插件是否已注册
     */
    fun isPluginRegistered(pluginId: String): Boolean {
        return pluginId in plugins
    }
    
    /**
     * 获取已注册插件数量
     */
    fun getPluginCount(): Int {
        return plugins.size
    }
    
    /**
     * 重新构建包名映射（当插件配置变化时调用）
     */
    fun rebuildPackageMap() {
        packageToPluginMap.clear()
        plugins.values.forEach { plugin ->
            plugin.targetPackages.forEach { packageName ->
                packageToPluginMap[packageName] = plugin.pluginId
            }
        }
        Log.d(TAG, "Package map rebuilt, ${packageToPluginMap.size} entries")
    }
}
