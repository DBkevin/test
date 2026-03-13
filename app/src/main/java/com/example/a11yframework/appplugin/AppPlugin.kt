package com.example.a11yframework.appplugin

/**
 * App 插件包定义。
 *
 * 基础框架只依赖这份描述来决定：
 * 1. 当前包名属于哪个业务插件
 * 2. 进入目标页面前要执行哪些导航步骤
 * 3. 需要安装哪些规则文件
 */
data class AppPluginBundle(
    val pluginId: String,
    val pluginName: String,
    val version: Int = 1,
    val enabled: Boolean = true,
    val appPackages: List<String>,
    val entryPackage: String? = null,
    val ruleAssets: List<String> = emptyList(),
    val captureFlow: CaptureFlowConfig? = null
)

data class CaptureFlowConfig(
    val appStartDelayMs: Long = 2_500L,
    val steps: List<NavigationStep> = emptyList(),
    val collection: CollectionConfig = CollectionConfig()
)

data class CollectionConfig(
    val captureTimeoutMs: Long = 60_000L,
    val captureRoundWaitMs: Long = 2_500L,
    val scrollSettleMs: Long = 1_800L,
    val maxScrollRounds: Int = 6,
    val maxIdleScrollRounds: Int = 2,
    val expandKeywords: List<String> = emptyList(),
    val expandSettleMs: Long = 1_000L,
    val maxExpandClicksPerRound: Int = 0
)

data class NavigationStep(
    val type: NavigationStepType,
    val targetText: String? = null,
    val targetTexts: List<String> = emptyList(),
    val targetViewId: String? = null,
    val source: StepValueSource? = null,
    val entryKeywords: List<String> = emptyList(),
    val buttonKeywords: List<String> = emptyList(),
    val waitMs: Long = 0L,
    val timeoutMs: Long = 0L,
    val maxScrollRounds: Int = 0,
    val exactMatch: Boolean = false,
    val forward: Boolean = true
)

enum class NavigationStepType {
    CLICK_TEXT,
    CLICK_TEXT_FUZZY,
    CLICK_VIEW_ID,
    SEARCH_KEYWORD,
    WAIT,
    WAIT_FOR_TEXT,
    SCROLL
}

enum class StepValueSource {
    HOSPITAL_NAME,
    TARGET_PACKAGE
}

data class PluginInstallResult(
    val success: Boolean,
    val pluginId: String = "",
    val pluginName: String = "",
    val version: Int = 0,
    val installedRuleCount: Int = 0,
    val backupCreated: Boolean = false,
    val errorMessage: String? = null
)

data class PluginRollbackResult(
    val success: Boolean,
    val pluginId: String = "",
    val pluginName: String = "",
    val version: Int = 0,
    val remainingBackupCount: Int = 0,
    val errorMessage: String? = null
)

data class PluginRuntimeStatus(
    val pluginId: String,
    val pluginName: String,
    val version: Int,
    val enabled: Boolean,
    val appPackages: List<String>,
    val ruleCount: Int,
    val backupCount: Int
)

data class PluginBatchImportResult(
    val successCount: Int,
    val failureCount: Int,
    val results: List<PluginInstallResult>
)
