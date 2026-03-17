package com.example.a11yframework.appplugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPluginParserTest {

    private val parser = AppPluginParser()

    @Test
    fun `parse plugin bundle with capture flow`() {
        val json = """
            {
              "plugin_id": "douyin",
              "plugin_name": "抖音插件",
              "version": 2,
              "enabled": true,
              "app_packages": ["com.ss.android.ugc.aweme"],
              "rule_assets": ["douyin_hospital_v1.json"],
              "capture_flow": {
                "app_start_delay_ms": 1800,
                "steps": [
                  {
                    "type": "click_text",
                    "target_text": "团购",
                    "wait_ms": 1200
                  },
                  {
                    "type": "search_keyword",
                    "source": "hospital_name"
                  }
                ],
                "collection": {
                  "capture_timeout_ms": 30000,
                  "max_scroll_rounds": 5,
                  "stop_texts_all": ["团购", "收起"],
                  "stop_texts_any": ["预约到店送好礼", "预约到店专属礼"],
                  "stop_texts_none": ["展开更多", "查看全部"]
                }
              }
            }
        """.trimIndent()

        val plugin = parser.parse(json)

        assertEquals("douyin", plugin.pluginId)
        assertEquals("抖音插件", plugin.pluginName)
        assertEquals(2, plugin.version)
        assertEquals(listOf("com.ss.android.ugc.aweme"), plugin.appPackages)
        assertEquals(listOf("douyin_hospital_v1.json"), plugin.ruleAssets)
        assertEquals(2, plugin.captureFlow?.steps?.size)
        assertEquals(1800L, plugin.captureFlow?.appStartDelayMs)
        assertEquals(30000L, plugin.captureFlow?.collection?.captureTimeoutMs)
        assertEquals(5, plugin.captureFlow?.collection?.maxScrollRounds)
        assertEquals(listOf("团购", "收起"), plugin.captureFlow?.collection?.stopTextsAll)
        assertEquals(listOf("预约到店送好礼", "预约到店专属礼"), plugin.captureFlow?.collection?.stopTextsAny)
        assertEquals(listOf("展开更多", "查看全部"), plugin.captureFlow?.collection?.stopTextsNone)
        assertEquals(NavigationStepType.CLICK_TEXT, plugin.captureFlow?.steps?.first()?.type)
    }

    @Test
    fun `parse plugin bundle without capture flow`() {
        val json = """
            {
              "plugin_id": "meituan",
              "plugin_name": "美团插件",
              "app_packages": ["com.sankuai.meituan"],
              "rule_assets": ["meituan_hospital_v1.json"]
            }
        """.trimIndent()

        val plugin = parser.parse(json)

        assertEquals("meituan", plugin.pluginId)
        assertTrue(plugin.ruleAssets.contains("meituan_hospital_v1.json"))
        assertNull(plugin.captureFlow)
    }
}
