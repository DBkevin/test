package com.example.a11yframework.rule.engine

import com.example.a11yframework.rule.ExtractRule
import com.example.a11yframework.rule.ExtractType
import com.example.a11yframework.rule.MatchRule
import com.example.a11yframework.rule.MatchType
import com.example.a11yframework.rule.PageConfig
import com.example.a11yframework.rule.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleDataMapperTest {

    @Test
    fun `should map scalar extraction into single scraped record`() {
        val page = createPage()
        val rule = createRule(page)

        val result = RuleDataMapper.toScrapedData(
            rule = rule,
            page = page,
            extractedData = mapOf(
                "hospital_name" to "北京测试医院",
                "honors" to "认证机构"
            )
        )

        assertEquals(1, result.size)
        assertEquals("douyin", result.first().pluginId)
        assertEquals("hospital_detail", result.first().pageType)
        assertEquals("rule_extract", result.first().dataType)
        assertEquals("北京测试医院", result.first().content["hospital_name"])
    }

    @Test
    fun `should flatten list extraction with shared fields`() {
        val page = createPage()
        val rule = createRule(page)

        val result = RuleDataMapper.toScrapedData(
            rule = rule,
            page = page,
            extractedData = mapOf(
                "hospital_name" to "北京测试医院",
                "group_buys" to listOf(
                    mapOf("title" to "黄金微针", "price" to "¥999"),
                    mapOf("title" to "水光针", "price" to "¥699")
                )
            )
        )

        assertEquals(2, result.size)
        assertTrue(result.all { it.dataType == "group_buys" })
        assertTrue(result.all { it.content["hospital_name"] == "北京测试医院" })
        assertEquals("黄金微针", result.first().content["title"])
    }

    private fun createRule(page: PageConfig): Rule {
        return Rule(
            ruleId = "douyin_hospital_v1",
            ruleName = "抖音医院团购详情页",
            appId = "douyin",
            appPackage = "com.ss.android.ugc.aweme",
            pages = listOf(page)
        )
    }

    private fun createPage(): PageConfig {
        return PageConfig(
            pageId = "hospital_detail",
            pageName = "医院详情页",
            matchRules = listOf(
                MatchRule(
                    type = MatchType.TEXT_CONTAINS,
                    values = listOf("医院")
                )
            ),
            extractRules = mapOf(
                "hospital_name" to ExtractRule(
                    type = ExtractType.FIND_BY_KEYWORDS,
                    keywords = listOf("医院")
                )
            )
        )
    }
}
