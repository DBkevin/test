package com.example.a11yframework.appplugin

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 解析插件包清单。
 *
 * 这里故意使用 JSON + 简单字段，而不是引入新的脚本 DSL，
 * 先把“App 特定能力从 Kotlin 挪出去”这件事做扎实。
 */
class AppPluginParser {

    companion object {
        private const val TAG = "AppPluginParser"
    }

    fun parse(json: String): AppPluginBundle {
        try {
            val jsonObject = JSONObject(json)
            validateRequiredFields(jsonObject)

            return AppPluginBundle(
                pluginId = jsonObject.getString("plugin_id"),
                pluginName = jsonObject.getString("plugin_name"),
                version = jsonObject.optInt("version", 1),
                enabled = jsonObject.optBoolean("enabled", true),
                appPackages = parseStringArray(jsonObject.getJSONArray("app_packages")),
                entryPackage = optNullableString(jsonObject, "entry_package"),
                ruleAssets = parseStringArray(jsonObject.optJSONArray("rule_assets") ?: JSONArray()),
                captureFlow = jsonObject.optJSONObject("capture_flow")?.let { parseCaptureFlow(it) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "插件清单解析失败", e)
            throw IllegalArgumentException("插件清单格式错误: ${e.message}", e)
        }
    }

    private fun validateRequiredFields(jsonObject: JSONObject) {
        val requiredFields = listOf("plugin_id", "plugin_name", "app_packages")
        requiredFields.forEach { field ->
            if (!jsonObject.has(field)) {
                throw IllegalArgumentException("缺少必填字段: $field")
            }
        }
    }

    private fun parseCaptureFlow(jsonObject: JSONObject): CaptureFlowConfig {
        val steps = jsonObject.optJSONArray("steps")?.let { stepsArray ->
            (0 until stepsArray.length()).map { index ->
                parseNavigationStep(stepsArray.getJSONObject(index))
            }
        } ?: emptyList()

        return CaptureFlowConfig(
            appStartDelayMs = jsonObject.optLong("app_start_delay_ms", 2_500L),
            steps = steps,
            collection = jsonObject.optJSONObject("collection")?.let { parseCollectionConfig(it) }
                ?: CollectionConfig()
        )
    }

    private fun parseCollectionConfig(jsonObject: JSONObject): CollectionConfig {
        return CollectionConfig(
            captureTimeoutMs = jsonObject.optLong("capture_timeout_ms", 60_000L),
            captureRoundWaitMs = jsonObject.optLong("capture_round_wait_ms", 2_500L),
            scrollSettleMs = jsonObject.optLong("scroll_settle_ms", 1_800L),
            maxScrollRounds = jsonObject.optInt("max_scroll_rounds", 6),
            maxIdleScrollRounds = jsonObject.optInt("max_idle_scroll_rounds", 2)
        )
    }

    private fun parseNavigationStep(jsonObject: JSONObject): NavigationStep {
        val type = parseStepType(jsonObject.getString("type"))
        val waitMs = when {
            jsonObject.has("duration_ms") -> jsonObject.optLong("duration_ms", 0L)
            else -> jsonObject.optLong("wait_ms", 0L)
        }

        return NavigationStep(
            type = type,
            targetText = optNullableString(jsonObject, "target_text"),
            targetTexts = parseStringArray(jsonObject.optJSONArray("target_texts") ?: JSONArray()),
            targetViewId = optNullableString(jsonObject, "target_view_id"),
            source = optNullableString(jsonObject, "source")?.takeIf { it.isNotBlank() }?.let { parseStepSource(it) },
            entryKeywords = parseStringArray(jsonObject.optJSONArray("entry_keywords") ?: JSONArray()),
            buttonKeywords = parseStringArray(jsonObject.optJSONArray("button_keywords") ?: JSONArray()),
            waitMs = waitMs,
            timeoutMs = jsonObject.optLong("timeout_ms", 0L),
            maxScrollRounds = jsonObject.optInt("max_scroll_rounds", jsonObject.optInt("scroll_rounds", 0)),
            exactMatch = jsonObject.optBoolean("exact_match", false),
            forward = !jsonObject.has("direction") || jsonObject.optString("direction", "forward") != "backward"
        )
    }

    private fun parseStepType(rawType: String): NavigationStepType {
        return when (rawType.lowercase()) {
            "click_text" -> NavigationStepType.CLICK_TEXT
            "click_text_fuzzy" -> NavigationStepType.CLICK_TEXT_FUZZY
            "click_view_id" -> NavigationStepType.CLICK_VIEW_ID
            "search_keyword" -> NavigationStepType.SEARCH_KEYWORD
            "wait" -> NavigationStepType.WAIT
            "wait_for_text" -> NavigationStepType.WAIT_FOR_TEXT
            "scroll" -> NavigationStepType.SCROLL
            else -> throw IllegalArgumentException("未知步骤类型: $rawType")
        }
    }

    private fun parseStepSource(rawSource: String): StepValueSource {
        return when (rawSource.lowercase()) {
            "hospital_name" -> StepValueSource.HOSPITAL_NAME
            "target_package" -> StepValueSource.TARGET_PACKAGE
            else -> throw IllegalArgumentException("未知数据源: $rawSource")
        }
    }

    private fun parseStringArray(array: JSONArray): List<String> {
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf { it.isNotBlank() }
        }
    }

    private fun optNullableString(jsonObject: JSONObject, key: String): String? {
        if (!jsonObject.has(key) || jsonObject.isNull(key)) {
            return null
        }

        return jsonObject.optString(key).takeIf { it.isNotBlank() }
    }
}
