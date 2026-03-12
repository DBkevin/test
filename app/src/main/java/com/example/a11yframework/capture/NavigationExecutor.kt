package com.example.a11yframework.capture

import android.util.Log
import com.example.a11yframework.appplugin.AppPluginBundle
import com.example.a11yframework.appplugin.NavigationStep
import com.example.a11yframework.appplugin.NavigationStepType
import com.example.a11yframework.appplugin.StepValueSource
import com.example.a11yframework.remote.HospitalTask
import com.example.a11yframework.search.SearchController
import kotlinx.coroutines.delay

/**
 * 解释插件包里的导航步骤。
 *
 * 它只关心“按清单执行动作”，不负责抓取结果的生命周期管理。
 */
class NavigationExecutor(
    private val searchController: SearchController
) {

    companion object {
        private const val TAG = "NavigationExecutor"
    }

    suspend fun execute(
        plugin: AppPluginBundle,
        task: HospitalTask
    ): NavigationExecutionResult {
        val flow = plugin.captureFlow ?: return NavigationExecutionResult(success = true)

        flow.steps.forEachIndexed { index, step ->
            val result = executeStep(step, task)
            if (!result.success) {
                return NavigationExecutionResult(
                    success = false,
                    errorMessage = "步骤 ${index + 1} 执行失败: ${result.errorMessage}"
                )
            }

            if (step.type != NavigationStepType.WAIT && step.waitMs > 0L) {
                delay(step.waitMs)
            }
        }

        return NavigationExecutionResult(success = true)
    }

    private suspend fun executeStep(
        step: NavigationStep,
        task: HospitalTask
    ): NavigationExecutionResult {
        return when (step.type) {
            NavigationStepType.CLICK_TEXT -> {
                val targetText = resolvePrimaryTargetText(step, task)
                if (targetText.isBlank()) {
                    NavigationExecutionResult(false, "click_text 缺少 target_text/source")
                } else {
                    val clicked = searchController.clickText(
                        targetText = targetText,
                        exactMatch = step.exactMatch,
                        maxScrollRounds = step.maxScrollRounds
                    )
                    logStep(step, "click_text", clicked, targetText)
                    asResult(clicked, "未找到可点击文本: $targetText")
                }
            }

            NavigationStepType.CLICK_TEXT_FUZZY -> {
                val targetText = resolvePrimaryTargetText(step, task)
                if (targetText.isBlank()) {
                    NavigationExecutionResult(false, "click_text_fuzzy 缺少 target_text/source")
                } else {
                    val clicked = searchController.clickTextFuzzy(
                        targetText = targetText,
                        maxScrollRounds = step.maxScrollRounds
                    )
                    logStep(step, "click_text_fuzzy", clicked, targetText)
                    asResult(clicked, "未定位到目标文本: $targetText")
                }
            }

            NavigationStepType.CLICK_VIEW_ID -> {
                val targetViewId = step.targetViewId.orEmpty()
                if (targetViewId.isBlank()) {
                    NavigationExecutionResult(false, "click_view_id 缺少 target_view_id")
                } else {
                    val clicked = searchController.clickViewId(
                        viewId = targetViewId,
                        maxScrollRounds = step.maxScrollRounds
                    )
                    logStep(step, "click_view_id", clicked, targetViewId)
                    asResult(clicked, "未定位到 ViewId: $targetViewId")
                }
            }

            NavigationStepType.SEARCH_KEYWORD -> {
                val keyword = resolvePrimaryTargetText(step, task)
                if (keyword.isBlank()) {
                    NavigationExecutionResult(false, "search_keyword 缺少关键字")
                } else {
                    val searched = searchController.search(
                        keyword = keyword,
                        entryKeywords = step.entryKeywords,
                        buttonKeywords = step.buttonKeywords
                    )
                    logStep(step, "search_keyword", searched, keyword)
                    asResult(searched, "搜索执行失败: $keyword")
                }
            }

            NavigationStepType.WAIT -> {
                val waitMs = step.waitMs.coerceAtLeast(0L)
                delay(waitMs)
                NavigationExecutionResult(success = true)
            }

            NavigationStepType.WAIT_FOR_TEXT -> {
                val targetTexts = resolveTargetTexts(step, task)
                if (targetTexts.isEmpty()) {
                    NavigationExecutionResult(false, "wait_for_text 缺少 target_text/target_texts/source")
                } else {
                    val matched = searchController.waitForAnyText(
                        targetTexts,
                        timeoutMs = step.timeoutMs.coerceAtLeast(1L)
                    )
                    logStep(step, "wait_for_text", matched, targetTexts.joinToString(","))
                    asResult(matched, "等待页面文本超时: ${targetTexts.joinToString(",")}")
                }
            }

            NavigationStepType.SCROLL -> {
                val rounds = step.maxScrollRounds.coerceAtLeast(1)
                var scrolled = false
                repeat(rounds) {
                    scrolled = searchController.scrollCurrentPage(forward = step.forward)
                    if (!scrolled) {
                        return@repeat
                    }
                }
                logStep(step, "scroll", scrolled, "rounds=$rounds")
                asResult(scrolled, "页面滚动失败")
            }
        }
    }

    private fun resolvePrimaryTargetText(step: NavigationStep, task: HospitalTask): String {
        return when (step.source) {
            StepValueSource.HOSPITAL_NAME -> task.hospitalName
            StepValueSource.TARGET_PACKAGE -> task.targetPackage.orEmpty()
            null -> step.targetText ?: step.targetTexts.firstOrNull().orEmpty()
        }
    }

    private fun resolveTargetTexts(step: NavigationStep, task: HospitalTask): List<String> {
        val explicitTargets = mutableListOf<String>()
        step.targetText?.takeIf { it.isNotBlank() }?.let { explicitTargets.add(it) }
        explicitTargets.addAll(step.targetTexts)
        val fromSource = resolvePrimaryTargetText(step, task).takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
        return (explicitTargets + fromSource).filter { it.isNotBlank() }.distinct()
    }

    private fun asResult(success: Boolean, failureMessage: String): NavigationExecutionResult {
        return if (success) {
            NavigationExecutionResult(success = true)
        } else {
            NavigationExecutionResult(success = false, errorMessage = failureMessage)
        }
    }

    private fun logStep(step: NavigationStep, label: String, success: Boolean, detail: String) {
        Log.i(
            TAG,
            "step=$label, success=$success, detail=$detail, stepConfig=$step"
        )
    }
}

data class NavigationExecutionResult(
    val success: Boolean,
    val errorMessage: String? = null
)
