package com.example.a11yframework.appplugin

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * 解析本地插件包内容。
 *
 * 支持两种输入：
 * 1. zip 插件包：包含 plugin.json 和 rules 目录下的 json 文件
 * 2. 纯 plugin.json：仅更新清单，规则沿用本地已有文件
 */
class AppPluginPackageReader {

    fun read(displayName: String?, bytes: ByteArray): PluginPackageContents {
        if (bytes.isEmpty()) {
            throw IllegalArgumentException("插件包内容为空")
        }

        return if (looksLikeZip(bytes)) {
            readZip(bytes)
        } else {
            readManifest(displayName, bytes)
        }
    }

    private fun readManifest(displayName: String?, bytes: ByteArray): PluginPackageContents {
        val manifestJson = bytes.toString(Charsets.UTF_8).trim()
        if (!manifestJson.startsWith("{")) {
            throw IllegalArgumentException("仅支持 zip 插件包或 plugin.json 文件")
        }

        return PluginPackageContents(
            sourceName = displayName,
            manifestJson = manifestJson,
            ruleFiles = emptyMap()
        )
    }

    private fun readZip(bytes: ByteArray): PluginPackageContents {
        val entryContents = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zipInput ->
            var entry = zipInput.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val normalizedName = normalizeEntryName(entry.name)
                    if (normalizedName.isNotBlank() && !normalizedName.startsWith("__MACOSX/")) {
                        entryContents[normalizedName] = zipInput.readBytes().toString(Charsets.UTF_8)
                    }
                }
                zipInput.closeEntry()
                entry = zipInput.nextEntry
            }
        }

        if (entryContents.isEmpty()) {
            throw IllegalArgumentException("zip 插件包中没有可用文件")
        }

        val manifestEntry = entryContents.keys
            .filter { entryName -> entryName == "plugin.json" || entryName.endsWith("/plugin.json") }
            .minByOrNull { entryName -> entryName.count { it == '/' } }
            ?: throw IllegalArgumentException("zip 插件包缺少 plugin.json")

        val prefix = manifestEntry.removeSuffix("plugin.json")
        val rulePrefix = "${prefix}rules/"
        val ruleFiles = linkedMapOf<String, String>()

        entryContents.forEach { (entryName, content) ->
            if (!entryName.startsWith(rulePrefix) || !entryName.endsWith(".json")) {
                return@forEach
            }

            val relativePath = entryName.removePrefix(rulePrefix)
            if ('/' in relativePath || relativePath.isBlank()) {
                return@forEach
            }

            ruleFiles[relativePath] = content
        }

        return PluginPackageContents(
            manifestJson = entryContents.getValue(manifestEntry),
            ruleFiles = ruleFiles
        )
    }

    private fun looksLikeZip(bytes: ByteArray): Boolean {
        return bytes.size >= 4 &&
            bytes[0] == 'P'.code.toByte() &&
            bytes[1] == 'K'.code.toByte()
    }

    private fun normalizeEntryName(name: String): String {
        return name.trim().removePrefix("./").replace('\\', '/')
    }
}

data class PluginPackageContents(
    val manifestJson: String,
    val ruleFiles: Map<String, String>,
    val sourceName: String? = null
)
