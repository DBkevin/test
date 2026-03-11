package com.example.a11yframework.rule.engine

import com.example.a11yframework.core.ScrapedData
import com.example.a11yframework.rule.PageConfig
import com.example.a11yframework.rule.Rule

/**
 * 把规则提取结果转换为统一的抓取数据结构
 */
object RuleDataMapper {

    fun toScrapedData(
        rule: Rule,
        page: PageConfig,
        extractedData: Map<String, Any>
    ): List<ScrapedData> {
        if (extractedData.isEmpty()) {
            return emptyList()
        }

        val baseContent = extractedData.entries
            .filterNot { (_, value) -> value is List<*> }
            .associate { (key, value) -> key to stringify(value) }
            .filterValues { it.isNotBlank() }

        val listFields = extractedData.entries.mapNotNull fieldLoop@{ (key, value) ->
            val items = value as? List<*> ?: return@fieldLoop null
            val normalizedItems = items.mapNotNull itemLoop@{ item ->
                val itemMap = item as? Map<*, *> ?: return@itemLoop null
                itemMap.entries
                    .mapNotNull entryLoop@{ entry ->
                        val entryKey = entry.key?.toString() ?: return@entryLoop null
                        entryKey to stringify(entry.value)
                    }
                    .toMap()
                    .filterValues { it.isNotBlank() }
            }
            key to normalizedItems
        }.filter { (_, items) -> items.isNotEmpty() }

        if (listFields.isEmpty()) {
            return listOf(buildRecord(rule, page, "rule_extract", baseContent))
        }

        return listFields.flatMap { (fieldName, items) ->
            items.map { itemContent ->
                buildRecord(rule, page, fieldName, baseContent + itemContent)
            }
        }
    }

    private fun buildRecord(
        rule: Rule,
        page: PageConfig,
        dataType: String,
        content: Map<String, String>
    ): ScrapedData {
        return ScrapedData(
            pluginId = rule.appId,
            pageType = page.pageId,
            dataType = dataType,
            content = content,
            rawText = content.values.filter { it.isNotBlank() }.joinToString(" "),
            metadata = mapOf(
                "ruleId" to rule.ruleId,
                "ruleVersion" to rule.version,
                "pageName" to page.pageName
            )
        )
    }

    private fun stringify(value: Any?): String {
        return when (value) {
            null -> ""
            is String -> value
            is Number, is Boolean -> value.toString()
            is List<*> -> value.joinToString(" | ") { stringify(it) }.trim()
            is Map<*, *> -> value.entries.joinToString("; ") { entry ->
                "${entry.key}=${stringify(entry.value)}"
            }.trim()
            else -> value.toString()
        }
    }
}
