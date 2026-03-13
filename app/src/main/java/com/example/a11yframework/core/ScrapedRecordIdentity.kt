package com.example.a11yframework.core

object ScrapedRecordIdentity {

    fun buildBusinessKey(record: ScrapedData): String {
        val merchantName = record.content["merchant_name"]
            ?: record.content["hospital_name"]
            ?: record.content["hospitalName"]
            ?: ""
        val title = record.content["groupBuyTitle"]
            ?: record.content["title"]
            ?: ""
        val price = record.content["price"].orEmpty()
        val originalPrice = record.content["original_price"]
            ?: record.content["originalPrice"]
            ?: ""
        val rawFallback = record.rawText.ifBlank {
            record.content.entries
                .sortedBy { it.key }
                .joinToString("|") { (_, value) -> value }
        }
        val fallbackPart = if (title.isBlank() && price.isBlank()) rawFallback else ""

        return listOf(
            record.pluginId,
            record.pageType,
            record.dataType,
            normalizeKeyPart(merchantName),
            normalizeKeyPart(title),
            normalizeKeyPart(price),
            normalizeKeyPart(originalPrice),
            normalizeKeyPart(fallbackPart)
        ).joinToString("|")
    }

    fun merge(existing: ScrapedData, incoming: ScrapedData): ScrapedData {
        val mergedContent = mergeContent(existing.content, incoming.content)
        val mergedRawText = chooseRicherText(
            existing.rawText,
            incoming.rawText,
            fallback = mergedContent.values.filter { it.isNotBlank() }.joinToString(" ")
        )
        val mergedMetadata = mergeMetadata(existing.metadata, incoming.metadata)

        return existing.copy(
            timestamp = maxOf(existing.timestamp, incoming.timestamp),
            content = mergedContent,
            rawText = mergedRawText,
            metadata = mergedMetadata
        )
    }

    private fun mergeContent(
        existing: Map<String, String>,
        incoming: Map<String, String>
    ): Map<String, String> {
        val merged = linkedMapOf<String, String>()
        val keys = (existing.keys + incoming.keys).distinct()

        keys.forEach { key ->
            merged[key] = chooseRicherText(existing[key].orEmpty(), incoming[key].orEmpty())
        }

        return merged.filterValues { it.isNotBlank() }
    }

    private fun mergeMetadata(
        existing: Map<String, Any>,
        incoming: Map<String, Any>
    ): Map<String, Any> {
        val merged = linkedMapOf<String, Any>()
        val keys = (existing.keys + incoming.keys).distinct()

        keys.forEach { key ->
            val existingValue = existing[key]
            val incomingValue = incoming[key]

            merged[key] = when {
                existingValue == null && incomingValue != null -> incomingValue
                incomingValue == null && existingValue != null -> existingValue
                existingValue == null -> ""
                incomingValue == null -> existingValue
                else -> chooseRicherMetadata(existingValue, incomingValue)
            }
        }

        return merged.filterValues {
            when (it) {
                is String -> it.isNotBlank()
                else -> true
            }
        }
    }

    private fun chooseRicherMetadata(existing: Any, incoming: Any): Any {
        if (existing == incoming) {
            return existing
        }

        if (existing is Number && incoming is Number) {
            return if (incoming.toDouble() >= existing.toDouble()) incoming else existing
        }

        return if (scoreText(incoming.toString()) >= scoreText(existing.toString())) {
            incoming
        } else {
            existing
        }
    }

    private fun chooseRicherText(
        existing: String,
        incoming: String,
        fallback: String = ""
    ): String {
        return when {
            incoming.isBlank() && existing.isBlank() -> fallback
            incoming.isBlank() -> existing
            existing.isBlank() -> incoming
            incoming == existing -> incoming
            scoreText(incoming) >= scoreText(existing) -> incoming
            else -> existing
        }
    }

    private fun scoreText(value: String): Int {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            return 0
        }

        val normalizedLength = trimmed
            .replace(Regex("\\s+"), "")
            .length
        val digitCount = trimmed.count { it.isDigit() }
        return normalizedLength + digitCount
    }

    private fun normalizeKeyPart(value: String): String {
        return value.lowercase()
            .replace("\\s+".toRegex(), "")
            .trim()
    }
}
