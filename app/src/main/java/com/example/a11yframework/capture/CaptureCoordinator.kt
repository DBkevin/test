package com.example.a11yframework.capture

import android.util.Log
import com.example.a11yframework.core.FrameworkAccessibilityService
import com.example.a11yframework.core.ScrapedData
import com.example.a11yframework.remote.HospitalTask
import com.example.a11yframework.search.SearchController
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min

/**
 * 串联 V2 手机端执行链路：
 * 打开目标 App -> 搜索医院 -> 进入商家详情 -> 滚动汇总抓取结果 -> 组装返回
 */
class CaptureCoordinator(
    private val service: FrameworkAccessibilityService
) {

    companion object {
        private const val TAG = "CaptureCoordinator"
        private const val DOUYIN_APP_PACKAGE = "com.ss.android.ugc.aweme"
        private const val KEY_TARGET_APP_PACKAGE = "target_app_package"
        private const val DEFAULT_TARGET_APP_PACKAGE = DOUYIN_APP_PACKAGE
        private const val KEY_CAPTURE_TIMEOUT_MS = "capture_timeout_ms"
        private const val DEFAULT_CAPTURE_TIMEOUT_MS = 60_000L
        private const val KEY_APP_START_DELAY_MS = "app_start_delay_ms"
        private const val DEFAULT_APP_START_DELAY_MS = 2_500L
        private const val KEY_RESULT_LIST_WAIT_MS = "result_list_wait_ms"
        private const val DEFAULT_RESULT_LIST_WAIT_MS = 2_500L
        private const val KEY_DETAIL_OPEN_DELAY_MS = "detail_open_delay_ms"
        private const val DEFAULT_DETAIL_OPEN_DELAY_MS = 2_200L
        private const val KEY_SCROLL_SETTLE_MS = "scroll_settle_ms"
        private const val DEFAULT_SCROLL_SETTLE_MS = 1_800L
        private const val KEY_CAPTURE_ROUND_WAIT_MS = "capture_round_wait_ms"
        private const val DEFAULT_CAPTURE_ROUND_WAIT_MS = 2_500L
        private const val KEY_MAX_SCROLL_ROUNDS = "max_scroll_rounds"
        private const val DEFAULT_MAX_SCROLL_ROUNDS = 6
        private const val KEY_MAX_IDLE_SCROLL_ROUNDS = "max_idle_scroll_rounds"
        private const val DEFAULT_MAX_IDLE_SCROLL_ROUNDS = 2
        private const val COLLECTING_SCRAPE_COOLDOWN_MS = 1_200L
    }

    private val executionMutex = Mutex()
    private val searchController by lazy { SearchController(service) }

    @Volatile
    private var activeCapture: ActiveCapture? = null

    suspend fun executeTask(task: HospitalTask): CaptureExecutionResult = executionMutex.withLock {
        val targetPackage = resolveTargetPackage(task)
        task.targetPackage = targetPackage

        val activeExecution = ActiveCapture(
            task = task,
            targetPackage = targetPackage
        )
        activeCapture = activeExecution

        return try {
            if (!service.launchTargetApp(targetPackage)) {
                failure(task, targetPackage, "无法打开目标 App: $targetPackage")
            } else if (!waitForPackage(targetPackage, 15_000L)) {
                failure(task, targetPackage, "等待目标 App 超时: $targetPackage")
            } else {
                delay(resolveAppStartDelayMs())
                val attemptResult = if (isDouyinPackage(targetPackage)) {
                    executeDouyinCapture(activeExecution)
                } else {
                    executeGenericCapture(activeExecution)
                }

                if (attemptResult.records.isEmpty()) {
                    failure(
                        task,
                        targetPackage,
                        attemptResult.errorMessage ?: "等待抓取结果超时或未产出数据"
                    )
                } else {
                    success(task, targetPackage, attemptResult.records)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "任务执行异常: ${task.hospitalName}", e)
            failure(task, targetPackage, e.message ?: "未知异常")
        } finally {
            activeCapture = null
        }
    }

    fun onWindowChanged(packageName: String) {
        activeCapture?.lastSeenPackage = packageName
    }

    fun onRecordsCaptured(packageName: String, records: List<ScrapedData>) {
        val activeExecution = activeCapture ?: return
        if (records.isEmpty()) return
        if (packageName != activeExecution.targetPackage) return
        if (activeExecution.stage != CaptureStage.COLLECTING) return

        val addedCount = activeExecution.appendRecords(records)
        if (addedCount > 0) {
            Log.i(
                TAG,
                "已接收抓取结果: hospital=${activeExecution.task.hospitalName}, added=$addedCount, total=${activeExecution.recordCount()}"
            )
        }
    }

    fun cancelActiveCapture(reason: String) {
        val activeExecution = activeCapture ?: return
        activeExecution.stage = CaptureStage.CANCELLED
        Log.i(TAG, "取消当前抓取: $reason")
    }

    fun shouldScrapePage(packageName: String): Boolean {
        val activeExecution = activeCapture ?: return true
        if (packageName != activeExecution.targetPackage) {
            return true
        }
        return activeExecution.stage == CaptureStage.COLLECTING
    }

    fun isCollectingForPackage(packageName: String): Boolean {
        val activeExecution = activeCapture ?: return false
        return packageName == activeExecution.targetPackage &&
            activeExecution.stage == CaptureStage.COLLECTING
    }

    fun getScrapeCooldownMs(packageName: String, defaultMs: Long): Long {
        return if (isCollectingForPackage(packageName)) {
            COLLECTING_SCRAPE_COOLDOWN_MS
        } else {
            defaultMs
        }
    }

    fun prepareCapturedRecords(packageName: String, records: List<ScrapedData>): List<ScrapedData> {
        if (records.isEmpty()) {
            return emptyList()
        }

        val activeExecution = activeCapture
        if (activeExecution == null || packageName != activeExecution.targetPackage) {
            return records
        }

        val merchantName = activeExecution.task.hospitalName.trim()
        if (merchantName.isBlank()) {
            return records
        }

        return records.map { record ->
            val content = record.content.toMutableMap()

            if (content["merchant_name"].isNullOrBlank()) {
                content["merchant_name"] = merchantName
            }
            if (content["hospital_name"].isNullOrBlank()) {
                content["hospital_name"] = merchantName
            }
            if (content["hospitalName"].isNullOrBlank()) {
                content["hospitalName"] = merchantName
            }
            if (content["search_keyword"].isNullOrBlank()) {
                content["search_keyword"] = merchantName
            }
            if (content["searchKeyword"].isNullOrBlank()) {
                content["searchKeyword"] = merchantName
            }

            val rawText = record.rawText.ifBlank {
                content.values.filter { it.isNotBlank() }.joinToString(" ")
            }

            record.copy(
                content = content.toMap(),
                rawText = rawText,
                metadata = record.metadata + mapOf("captureStage" to activeExecution.stage.name.lowercase())
            )
        }
    }

    private suspend fun waitForPackage(packageName: String, timeoutMs: Long): Boolean {
        val startAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startAt < timeoutMs) {
            if (service.getCurrentActivePackage() == packageName) {
                return true
            }
            delay(500)
        }
        return false
    }

    private fun resolveTargetPackage(task: HospitalTask): String {
        return (task.targetPackage
            ?: service.configManager.getPluginConfigString(
                "system",
                KEY_TARGET_APP_PACKAGE,
                DEFAULT_TARGET_APP_PACKAGE
            )).ifBlank { DEFAULT_TARGET_APP_PACKAGE }
    }

    private fun resolveCaptureTimeoutMs(): Long {
        return service.configManager.getPluginConfigInt(
            "system",
            KEY_CAPTURE_TIMEOUT_MS,
            DEFAULT_CAPTURE_TIMEOUT_MS.toInt()
        ).toLong()
    }

    private fun resolveAppStartDelayMs(): Long {
        return service.configManager.getPluginConfigInt(
            "system",
            KEY_APP_START_DELAY_MS,
            DEFAULT_APP_START_DELAY_MS.toInt()
        ).toLong()
    }

    private suspend fun executeGenericCapture(activeExecution: ActiveCapture): CaptureAttemptResult {
        activeExecution.stage = CaptureStage.SEARCHING

        val searchSuccess = searchController.search(activeExecution.task.hospitalName)
        if (!searchSuccess) {
            return CaptureAttemptResult(
                errorMessage = "搜索失败，未找到可操作的搜索框或搜索按钮"
            )
        }

        activeExecution.stage = CaptureStage.COLLECTING
        val records = waitForCollectedRecords(activeExecution, resolveCaptureTimeoutMs())
        return if (records.isEmpty()) {
            CaptureAttemptResult(errorMessage = "等待抓取结果超时或未产出数据")
        } else {
            CaptureAttemptResult(records = records)
        }
    }

    private suspend fun executeDouyinCapture(activeExecution: ActiveCapture): CaptureAttemptResult {
        activeExecution.stage = CaptureStage.NAVIGATING_GROUPBUY

        val searchSuccess = searchController.searchDouyinGroupBuy(activeExecution.task.hospitalName)
        if (!searchSuccess) {
            return CaptureAttemptResult(
                errorMessage = "未能进入抖音团购搜索并提交关键词"
            )
        }

        activeExecution.stage = CaptureStage.WAITING_RESULT_LIST
        delay(resolveResultListWaitMs())

        val merchantOpened = searchController.openMerchantResult(activeExecution.task.hospitalName)
        if (!merchantOpened) {
            return CaptureAttemptResult(
                errorMessage = "未在搜索结果页定位到目标商家: ${activeExecution.task.hospitalName}"
            )
        }

        activeExecution.stage = CaptureStage.OPENING_DETAIL
        delay(resolveDetailOpenDelayMs())

        activeExecution.stage = CaptureStage.COLLECTING
        val records = collectDetailRecords(activeExecution, resolveCaptureTimeoutMs())
        return if (records.isEmpty()) {
            CaptureAttemptResult(errorMessage = "进入商家详情后未采集到团购数据")
        } else {
            CaptureAttemptResult(records = records)
        }
    }

    private suspend fun waitForCollectedRecords(
        activeExecution: ActiveCapture,
        timeoutMs: Long
    ): List<ScrapedData> {
        val baseline = activeExecution.recordCount()
        waitForRecordGrowth(activeExecution, baseline, timeoutMs)
        return activeExecution.snapshotRecords()
    }

    private suspend fun collectDetailRecords(
        activeExecution: ActiveCapture,
        timeoutMs: Long
    ): List<ScrapedData> {
        val deadline = System.currentTimeMillis() + timeoutMs
        val maxScrollRounds = resolveMaxScrollRounds()
        val maxIdleScrollRounds = resolveMaxIdleScrollRounds()

        var idleRounds = 0
        var scrollRounds = 0

        waitForRecordGrowth(
            activeExecution,
            baselineCount = activeExecution.recordCount(),
            timeoutMs = min(resolveCaptureRoundWaitMs(), remainingTime(deadline))
        )

        while (scrollRounds < maxScrollRounds && remainingTime(deadline) > 0) {
            val beforeCount = activeExecution.recordCount()
            val scrolled = searchController.scrollCurrentPage()
            if (!scrolled) {
                break
            }

            scrollRounds++
            delay(resolveScrollSettleMs())
            waitForRecordGrowth(
                activeExecution,
                baselineCount = beforeCount,
                timeoutMs = min(resolveCaptureRoundWaitMs(), remainingTime(deadline))
            )

            val afterCount = activeExecution.recordCount()
            if (afterCount <= beforeCount) {
                idleRounds++
                if (idleRounds >= maxIdleScrollRounds) {
                    break
                }
            } else {
                idleRounds = 0
            }
        }

        return activeExecution.snapshotRecords()
    }

    private suspend fun waitForRecordGrowth(
        activeExecution: ActiveCapture,
        baselineCount: Int,
        timeoutMs: Long
    ): Boolean {
        if (timeoutMs <= 0L) {
            return activeExecution.recordCount() > baselineCount
        }

        val startAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startAt < timeoutMs) {
            if (activeExecution.recordCount() > baselineCount) {
                return true
            }
            delay(400)
        }

        return activeExecution.recordCount() > baselineCount
    }

    private fun remainingTime(deadline: Long): Long {
        return (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private fun isDouyinPackage(packageName: String): Boolean {
        return packageName == DOUYIN_APP_PACKAGE
    }

    private fun success(
        task: HospitalTask,
        targetPackage: String,
        records: List<ScrapedData>
    ): CaptureExecutionResult {
        val payload = CapturePayloadBuilder.build(task, records)
        return CaptureExecutionResult(
            success = true,
            targetPackage = targetPackage,
            recordCount = records.size,
            data = payload
        )
    }

    private fun failure(
        task: HospitalTask,
        targetPackage: String,
        errorMessage: String
    ): CaptureExecutionResult {
        return CaptureExecutionResult(
            success = false,
            targetPackage = targetPackage,
            errorMessage = errorMessage,
            data = CapturePayloadBuilder.build(task, emptyList())
        )
    }

    private fun resolveResultListWaitMs(): Long {
        return service.configManager.getPluginConfigInt(
            "system",
            KEY_RESULT_LIST_WAIT_MS,
            DEFAULT_RESULT_LIST_WAIT_MS.toInt()
        ).toLong()
    }

    private fun resolveDetailOpenDelayMs(): Long {
        return service.configManager.getPluginConfigInt(
            "system",
            KEY_DETAIL_OPEN_DELAY_MS,
            DEFAULT_DETAIL_OPEN_DELAY_MS.toInt()
        ).toLong()
    }

    private fun resolveScrollSettleMs(): Long {
        return service.configManager.getPluginConfigInt(
            "system",
            KEY_SCROLL_SETTLE_MS,
            DEFAULT_SCROLL_SETTLE_MS.toInt()
        ).toLong()
    }

    private fun resolveCaptureRoundWaitMs(): Long {
        return service.configManager.getPluginConfigInt(
            "system",
            KEY_CAPTURE_ROUND_WAIT_MS,
            DEFAULT_CAPTURE_ROUND_WAIT_MS.toInt()
        ).toLong()
    }

    private fun resolveMaxScrollRounds(): Int {
        return service.configManager.getPluginConfigInt(
            "system",
            KEY_MAX_SCROLL_ROUNDS,
            DEFAULT_MAX_SCROLL_ROUNDS
        )
    }

    private fun resolveMaxIdleScrollRounds(): Int {
        return service.configManager.getPluginConfigInt(
            "system",
            KEY_MAX_IDLE_SCROLL_ROUNDS,
            DEFAULT_MAX_IDLE_SCROLL_ROUNDS
        )
    }
}

data class CaptureExecutionResult(
    val success: Boolean,
    val targetPackage: String,
    val recordCount: Int = 0,
    val data: Map<String, Any> = emptyMap(),
    val errorMessage: String? = null
)

private data class ActiveCapture(
    val task: HospitalTask,
    val targetPackage: String,
    var stage: CaptureStage = CaptureStage.OPENING_APP,
    var lastSeenPackage: String = "",
    val recordsByKey: LinkedHashMap<String, ScrapedData> = linkedMapOf()
)

private enum class CaptureStage {
    OPENING_APP,
    NAVIGATING_GROUPBUY,
    SEARCHING,
    WAITING_RESULT_LIST,
    OPENING_DETAIL,
    COLLECTING,
    CANCELLED
}

private data class CaptureAttemptResult(
    val records: List<ScrapedData> = emptyList(),
    val errorMessage: String? = null
)

private fun ActiveCapture.appendRecords(records: List<ScrapedData>): Int {
    synchronized(this) {
        var addedCount = 0
        records.forEach { record ->
            val key = buildRecordKey(record)
            if (!recordsByKey.containsKey(key)) {
                recordsByKey[key] = record
                addedCount++
            }
        }
        return addedCount
    }
}

private fun ActiveCapture.recordCount(): Int {
    synchronized(this) {
        return recordsByKey.size
    }
}

private fun ActiveCapture.snapshotRecords(): List<ScrapedData> {
    synchronized(this) {
        return recordsByKey.values.toList()
    }
}

private fun buildRecordKey(record: ScrapedData): String {
    val merchantName = record.content["merchant_name"]
        ?: record.content["hospital_name"]
        ?: record.content["hospitalName"]
        ?: ""
    val title = record.content["groupBuyTitle"]
        ?: record.content["title"]
        ?: ""
    val price = record.content["price"].orEmpty()
    val sales = record.content["sales"].orEmpty()
    val rawText = record.rawText.ifBlank {
        record.content.entries
            .sortedBy { it.key }
            .joinToString("|") { (_, value) -> value }
    }

    return listOf(
        record.pluginId,
        record.pageType,
        record.dataType,
        normalizeKeyPart(merchantName),
        normalizeKeyPart(title),
        normalizeKeyPart(price),
        normalizeKeyPart(sales),
        normalizeKeyPart(rawText)
    ).joinToString("|")
}

private fun normalizeKeyPart(value: String): String {
    return value.lowercase()
        .replace("\\s+".toRegex(), "")
        .trim()
}
