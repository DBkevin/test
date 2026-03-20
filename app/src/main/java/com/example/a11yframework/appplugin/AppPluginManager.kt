package com.example.a11yframework.appplugin

import android.content.Context
import android.util.Log
import com.example.a11yframework.rule.RuleManager
import com.example.a11yframework.rule.RuleParser
import java.io.File
import java.io.InputStream

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
        private const val BACKUP_PLUGINS_DIR = "app_plugin_backups"
        private const val EXTERNAL_PLUGINS_DIR = "plugins"
        private const val IMPORT_INBOX_DIR = "inbox"
        private const val MANIFEST_FILE_NAME = "plugin.json"
        private const val RULES_SUB_DIR = "rules"
        private const val MAX_BACKUP_COUNT = 5
    }

    private val parser = AppPluginParser()
    private val packageReader = AppPluginPackageReader()
    private val ruleParser = RuleParser()
    private val ruleManager = RuleManager(context)
    private val pluginsDir = File(context.filesDir, FILE_PLUGINS_DIR)
    private val backupRootDir = File(context.filesDir, BACKUP_PLUGINS_DIR)
    private val pluginsCache = linkedMapOf<String, AppPluginBundle>()

    init {
        if (!pluginsDir.exists()) {
            pluginsDir.mkdirs()
        }
        if (!backupRootDir.exists()) {
            backupRootDir.mkdirs()
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

    fun getImportInboxDir(): File {
        val parent = context.getExternalFilesDir(EXTERNAL_PLUGINS_DIR)
            ?: File(context.filesDir, EXTERNAL_PLUGINS_DIR)
        val inboxDir = File(parent, IMPORT_INBOX_DIR)
        if (!inboxDir.exists()) {
            inboxDir.mkdirs()
        }
        return inboxDir
    }

    fun getRuntimePluginStatuses(): List<PluginRuntimeStatus> {
        return pluginsCache.values.map { plugin ->
            PluginRuntimeStatus(
                pluginId = plugin.pluginId,
                pluginName = plugin.pluginName,
                version = plugin.version,
                enabled = plugin.enabled,
                appPackages = plugin.appPackages,
                ruleCount = plugin.ruleAssets.size,
                backupCount = getBackupDirs(plugin.pluginId).size
            )
        }
    }

    fun importPluginPackage(
        displayName: String?,
        inputStream: InputStream
    ): PluginInstallResult {
        val bytes = inputStream.readBytes()
        val packageContents = packageReader.read(displayName, bytes)
        return installOrUpdatePlugin(
            manifestJson = packageContents.manifestJson,
            ruleFiles = packageContents.ruleFiles
        )
    }

    fun installPendingPluginPackages(): PluginBatchImportResult {
        val inboxDir = getImportInboxDir()
        val packageFiles = inboxDir.listFiles { file ->
            file.isFile && (file.extension.equals("zip", true) || file.extension.equals("json", true))
        }?.sortedBy { it.name.lowercase() } ?: emptyList()

        if (packageFiles.isEmpty()) {
            return PluginBatchImportResult(
                successCount = 0,
                failureCount = 0,
                results = emptyList()
            )
        }

        val results = mutableListOf<PluginInstallResult>()
        packageFiles.forEach { packageFile ->
            val result = runCatching {
                packageFile.inputStream().use { input ->
                    importPluginPackage(packageFile.name, input)
                }
            }.getOrElse { error ->
                Log.e(TAG, "安装收件目录插件失败: ${packageFile.name}", error)
                PluginInstallResult(
                    success = false,
                    errorMessage = error.message ?: "未知错误"
                )
            }

            if (result.success) {
                if (!packageFile.delete()) {
                    Log.w(TAG, "未删除已安装插件包: ${packageFile.absolutePath}")
                }
            }

            results += result
        }

        return PluginBatchImportResult(
            successCount = results.count { it.success },
            failureCount = results.count { !it.success },
            results = results
        )
    }

    fun installOrUpdatePlugin(
        manifestJson: String,
        ruleFiles: Map<String, String>
    ): PluginInstallResult {
        return try {
            val plugin = parser.parse(manifestJson)
            val pluginDir = File(pluginsDir, plugin.pluginId)
            val rulesDir = File(pluginDir, RULES_SUB_DIR)
            val existingRuleFiles = rulesDir.listFiles { file ->
                file.isFile && file.extension == "json"
            } ?: emptyArray()
            val obsoleteRuleFiles = existingRuleFiles.filter { file ->
                file.name !in plugin.ruleAssets
            }
            plugin.ruleAssets.forEach { ruleFileName ->
                val ruleJson = ruleFiles[ruleFileName] ?: return@forEach
                ruleParser.parse(ruleJson)
            }

            val missingRules = plugin.ruleAssets.filter { ruleFileName ->
                ruleFileName !in ruleFiles && !File(rulesDir, ruleFileName).exists()
            }
            if (missingRules.isNotEmpty()) {
                return PluginInstallResult(
                    success = false,
                    pluginId = plugin.pluginId,
                    pluginName = plugin.pluginName,
                    version = plugin.version,
                    errorMessage = "缺少规则文件: ${missingRules.joinToString(", ")}"
                )
            }

            val backupCreated = if (pluginDir.exists()) {
                createBackup(plugin.pluginId, pluginDir) != null
            } else {
                false
            }

            val stagedPluginDir = buildStagedPluginDir(plugin, manifestJson, ruleFiles)
            replacePluginDirectory(stagedPluginDir, pluginDir)

            obsoleteRuleFiles.forEach { obsoleteFile ->
                val obsoleteRuleId = obsoleteFile.nameWithoutExtension
                if (!ruleManager.deleteRule(obsoleteRuleId)) {
                    Log.w(TAG, "未删除旧运行时规则: $obsoleteRuleId")
                }
            }

            reloadPlugins()

            PluginInstallResult(
                success = true,
                pluginId = plugin.pluginId,
                pluginName = plugin.pluginName,
                version = plugin.version,
                installedRuleCount = plugin.ruleAssets.size,
                backupCreated = backupCreated
            )
        } catch (e: Exception) {
            Log.e(TAG, "安装运行时插件失败", e)
            PluginInstallResult(
                success = false,
                errorMessage = e.message ?: "未知错误"
            )
        }
    }

    fun rollbackPlugin(pluginId: String): PluginRollbackResult {
        return try {
            val backupDir = getBackupDirs(pluginId).firstOrNull()
                ?: return PluginRollbackResult(
                    success = false,
                    pluginId = pluginId,
                    errorMessage = "没有可回滚的备份"
                )

            val currentPluginDir = File(pluginsDir, pluginId)
            if (currentPluginDir.exists()) {
                createBackup(pluginId, currentPluginDir)
            }

            replacePluginDirectory(backupDir, currentPluginDir)
            reloadPlugins()

            val restoredPlugin = getPlugin(pluginId)
            PluginRollbackResult(
                success = restoredPlugin != null,
                pluginId = pluginId,
                pluginName = restoredPlugin?.pluginName.orEmpty(),
                version = restoredPlugin?.version ?: 0,
                remainingBackupCount = getBackupDirs(pluginId).size
            )
        } catch (e: Exception) {
            Log.e(TAG, "插件回滚失败: $pluginId", e)
            PluginRollbackResult(
                success = false,
                pluginId = pluginId,
                errorMessage = e.message ?: "未知错误"
            )
        }
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

    private fun buildStagedPluginDir(
        plugin: AppPluginBundle,
        manifestJson: String,
        ruleFiles: Map<String, String>
    ): File {
        val stagedDir = File(
            backupRootDir,
            "staging/${plugin.pluginId}_${System.currentTimeMillis()}"
        )
        val stagedRulesDir = File(stagedDir, RULES_SUB_DIR)
        stagedRulesDir.mkdirs()

        File(stagedDir, MANIFEST_FILE_NAME).writeText(manifestJson)
        plugin.ruleAssets.forEach { ruleFileName ->
            val inlineRule = ruleFiles[ruleFileName] ?: return@forEach
            File(stagedRulesDir, ruleFileName).writeText(inlineRule)
        }

        return stagedDir
    }

    private fun replacePluginDirectory(sourceDir: File, targetDir: File) {
        val sourcePath = sourceDir.toPath()
        val targetPath = targetDir.toPath()

        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        targetDir.parentFile?.mkdirs()

        runCatching {
            java.nio.file.Files.move(
                sourcePath,
                targetPath,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        }.onFailure {
            sourceDir.copyRecursively(targetDir, overwrite = true)
            sourceDir.deleteRecursively()
        }
    }

    private fun createBackup(pluginId: String, sourcePluginDir: File): File? {
        if (!sourcePluginDir.exists()) {
            return null
        }

        val pluginBackupRoot = File(backupRootDir, pluginId)
        if (!pluginBackupRoot.exists()) {
            pluginBackupRoot.mkdirs()
        }

        val backupDir = File(pluginBackupRoot, System.currentTimeMillis().toString())
        sourcePluginDir.copyRecursively(backupDir, overwrite = true)
        trimOldBackups(pluginId)
        return backupDir
    }

    private fun getBackupDirs(pluginId: String): List<File> {
        val pluginBackupRoot = File(backupRootDir, pluginId)
        return pluginBackupRoot.listFiles { file -> file.isDirectory }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }

    private fun trimOldBackups(pluginId: String) {
        getBackupDirs(pluginId)
            .drop(MAX_BACKUP_COUNT)
            .forEach { staleBackup ->
                if (!staleBackup.deleteRecursively()) {
                    Log.w(TAG, "未删除旧插件备份: ${staleBackup.absolutePath}")
                }
            }
    }
}
