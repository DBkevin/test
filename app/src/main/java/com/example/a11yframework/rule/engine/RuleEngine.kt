package com.example.a11yframework.rule.engine

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.a11yframework.core.FrameworkAccessibilityService
import com.example.a11yframework.core.ScrapedData
import com.example.a11yframework.rule.RuleManager
import com.example.a11yframework.rule.extractor.DataExtractor
import com.example.a11yframework.rule.matcher.PageMatcher

/**
 * V2 规则执行入口：
 * 规则加载 -> 页面匹配 -> 数据提取 -> 数据映射
 */
class RuleEngine(
    service: FrameworkAccessibilityService
) {

    companion object {
        private const val TAG = "RuleEngine"
    }

    private val ruleManager = RuleManager(service)
    private val pageMatcher = PageMatcher(service)
    private val dataExtractor = DataExtractor(service)

    fun hasRulesForPackage(packageName: String): Boolean {
        return ruleManager.findRulesByPackage(packageName).isNotEmpty()
    }

    fun getRuleCount(): Int {
        return ruleManager.getRuleCount()
    }

    fun reload() {
        ruleManager.refresh()
        pageMatcher.clearCache()
        Log.i(TAG, "Rule engine reloaded")
    }

    fun execute(packageName: String, rootNode: AccessibilityNodeInfo?): RuleExecutionResult {
        if (rootNode == null) {
            return RuleExecutionResult()
        }

        val candidateRules = ruleManager.findRulesByPackage(packageName)
        if (candidateRules.isEmpty()) {
            return RuleExecutionResult()
        }

        candidateRules.forEach { rule ->
            rule.pages.forEach pageLoop@{ page ->
                val matchResult = pageMatcher.match(page, rootNode)
                if (!matchResult.matched) {
                    return@pageLoop
                }

                val extractResult = dataExtractor.extract(page.extractRules, rootNode)
                val records = RuleDataMapper.toScrapedData(rule, page, extractResult.data)

                Log.i(
                    TAG,
                    "规则命中: rule=${rule.ruleId}, page=${page.pageId}, records=${records.size}"
                )

                return RuleExecutionResult(
                    matched = true,
                    ruleId = rule.ruleId,
                    pageId = page.pageId,
                    data = records,
                    errorMessage = extractResult.errorMessage
                )
            }
        }

        return RuleExecutionResult()
    }
}

data class RuleExecutionResult(
    val matched: Boolean = false,
    val ruleId: String? = null,
    val pageId: String? = null,
    val data: List<ScrapedData> = emptyList(),
    val errorMessage: String? = null
)
