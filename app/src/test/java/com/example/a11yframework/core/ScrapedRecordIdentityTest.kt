package com.example.a11yframework.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScrapedRecordIdentityTest {

    @Test
    fun businessKeyIgnoresRawTextExpansion() {
        val first = ScrapedData(
            pluginId = "douyin",
            pageType = "hospital_detail",
            dataType = "group_buys",
            content = mapOf(
                "merchant_name" to "郑州美莱医疗美容医院",
                "title" to "【长效水光】润致娃娃针2ml",
                "price" to "187.1",
                "original_price" to "199.0",
                "sales" to "已售1000+"
            ),
            rawText = "短文本"
        )

        val expanded = first.copy(
            rawText = "【长效水光】润致娃娃针2ml 周一至周日可用 至少提前1天预约 随时退 过期退 原价199.0元 现价187.1元 已售1000+ 领券抢购"
        )

        assertEquals(
            ScrapedRecordIdentity.buildBusinessKey(first),
            ScrapedRecordIdentity.buildBusinessKey(expanded)
        )
    }

    @Test
    fun mergePrefersRicherTextAndMetadata() {
        val original = ScrapedData(
            timestamp = 10L,
            pluginId = "douyin",
            pageType = "hospital_detail",
            dataType = "group_buys",
            content = mapOf(
                "merchant_name" to "郑州美莱医疗美容医院",
                "title" to "【营养水光】嗨体水光针2.5ml",
                "price" to "132.0"
            ),
            rawText = "短文本",
            metadata = mapOf("pageName" to "商家详情页")
        )

        val expanded = ScrapedData(
            timestamp = 20L,
            pluginId = "douyin",
            pageType = "hospital_detail",
            dataType = "group_buys",
            content = mapOf(
                "merchant_name" to "郑州美莱医疗美容医院",
                "title" to "【营养水光】嗨体水光针2.5ml",
                "price" to "132.0",
                "original_price" to "139.0",
                "sales" to "已售900+",
                "card_text" to "【营养水光】嗨体水光针2.5ml,周一至周日可用,至少提前1天预约,随时退·过期退,原价139.0元,现价132.0元,已售900+领券抢购"
            ),
            rawText = "【营养水光】嗨体水光针2.5ml 周一至周日可用 至少提前1天预约 随时退 过期退 原价139.0元 现价132.0元 已售900+ 领券抢购",
            metadata = mapOf(
                "pageName" to "商家详情页",
                "captureStage" to "collecting"
            )
        )

        val merged = ScrapedRecordIdentity.merge(original, expanded)

        assertEquals("139.0", merged.content["original_price"])
        assertEquals("已售900+", merged.content["sales"])
        assertEquals(expanded.rawText, merged.rawText)
        assertEquals("collecting", merged.metadata["captureStage"])
        assertEquals(20L, merged.timestamp)
        assertNotEquals(original, merged)
    }
}
