package com.example.a11yframework.appplugin

import android.content.Context
import android.util.Log
import com.example.a11yframework.rule.RuleManager
import java.io.File

/**
 * 运行时插件包管理器。
 *
 * 设计目标：
 * 1. APK 只负责首装时带上默认插件包
 * 2. 真正生效的插件包都落在 filesDir/app_plugins
 * 3. 以后无论来自远端下发、ADB 覆盖还是 GitHub 同步，最终都走同一套目录结构
 */
class AppPluginManager(
    private val context: Context
) {

    companion object {
        private const val TAG = "AppPluginManager"
        private const val ASSET_PLUGINS_DIR = "app_plugins"
        private const val FILE_PLUGINS_DIR = "app_plugins"
        private const val MANIFEST_FILE_NAME = "plugin.json"
        private const val RULES_SUB_DIR = "rules"
    }

    private val parser = AppPluginParser()
    private val ruleManager = RuleManager(context)
    private val pluginsDir = File(context.filesDir, FILE_PLUGINS_DIR)
    private val pluginsCache = linkedMapOf<String, AppPluginBundle>()

    init {
        if (!pluginsDir.exists()) {
            pluginsDir.mkdirs()
        }
    }

    fun installBundledPlugins() {
        try {
            val assetPluginDirs = context.assets.list(ASSET_PLUGINS_DIR) ?: emptyArray()
            assetPluginDirs.forEach { assetPluginId ->
                installBundledPlugin(assetPluginId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "安装内置插件包失败", e)
        }

        reloadPlugins()
    }

    fun reloadPlugins() {
        pluginsCache.clear()

        val localPluginDirs = pluginsDir.listFiles { file -> file.isDirectory } ?: emptyArray()
        localPluginDirs.sortedBy { it.name }.forEach { pluginDir ->
            val manifestFile = File(pluginDir, MANIFEST_FILE_NAME)
            if (!manifestFile.exists()) {
                return@forEach
            }

            try {
                val plugin = parser.parse(manifestFile.readText())
                pluginsCache[plugin.pluginId] = plugin
                syncLocalRules(plugin, pluginDir)
            } catch (e: Exception) {
                Log.e(TAG, "加载插件包失败: ${pluginDir.name}", e)
            }
        }

        Log.i(TAG, "插件包加载完成: ${pluginsCache.size} 个")
    }

    fun getPlugin(pluginId: String): AppPluginBundle? {
        return pluginsCache[pluginId]
    }

    fun findPluginForPackage(packageName: String): AppPluginBundle? {
        return pluginsCache.values.firstOrNull { plugin ->
            plugin.enabled && packageName in plugin.appPackages
        }
    }

    fun getAllPlugins(): List<AppPluginBundle> {
        return pluginsCache.values.toList()
    }

    fun getPluginCount(): Int {
        return pluginsCache.size
    }

    private fun installBundledPlugin(assetPluginId: String) {
        val assetManifestPath = "$ASSET_PLUGINS_DIR/$assetPluginId/$MANIFEST_FILE_NAME"
        val manifestJson = context.assets.open(assetManifestPath).bufferedReader().use { it.readText() }
        val bundledPlugin = parser.parse(manifestJson)

        val localPluginDir = File(pluginsDir, bundledPlugin.pluginId)
        if (!localPluginDir.exists()) {
            localPluginDir.mkdirs()
        }

        val localManifestFile = File(localPluginDir, MANIFEST_FILE_NAME)
        val localPlugin = localManifestFile.takeIf { it.exists() }?.runCatching {
            parser.parse(readText())
        }?.getOrNull()

        val shouldInstall = localPlugin == null || localPlugin.version < bundledPlugin.version
        if (!shouldInstall) {
            Log.d(TAG, "保留本地插件包: ${bundledPlugin.pluginId}, version=${localPlugin?.version}")
            return
        }

        localManifestFile.writeText(manifestJson)

        val localRulesDir = File(localPluginDir, RULES_SUB_DIR)
        if (!localRulesDir.exists()) {
            localRulesDir.mkdirs()
        }

        bundledPlugin.ruleAssets.forEach { ruleAsset ->
            val assetRulePath = "$ASSET_PLUGINS_DIR/$assetPluginId/$RULES_SUB_DIR/$ruleAsset"
            val ruleJson = context.assets.open(assetRulePath).bufferedReader().use { it.readText() }
            File(localRulesDir, ruleAsset).writeText(ruleJson)
        }

        Log.i(TAG, "已安装内置插件包: ${bundledPlugin.pluginId}")
    }

    private fun syncLocalRules(plugin: AppPluginBundle, pluginDir: File) {
        if (plugin.ruleAssets.isEmpty()) {
            return
        }

        val localRulesDir = File(pluginDir, RULES_SUB_DIR)
        plugin.ruleAssets.forEach { ruleAsset ->
            val ruleFile = File(localRulesDir, ruleAsset)
            if (!ruleFile.exists()) {
                Log.w(TAG, "插件规则文件不存在: ${plugin.pluginId}/$ruleAsset")
                return@forEach
            }

            runCatching {
                val json = ruleFile.readText()
                val ruleId = ruleFile.nameWithoutExtension
                ruleManager.updateRule(ruleId, json)
            }.onFailure { error ->
                Log.e(TAG, "同步插件规则失败: ${plugin.pluginId}/$ruleAsset", error)
            }
        }
    }
}
