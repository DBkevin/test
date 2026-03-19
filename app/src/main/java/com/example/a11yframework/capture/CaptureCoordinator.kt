package com.example.a11yframework.capture

import android.util.Log
import com.example.a11yframework.appplugin.AppPluginBundle
import com.example.a11yframework.appplugin.CollectionConfig
import com.example.a11yframework.core.FrameworkAccessibilityService
import com.example.a11yframework.core.ScrapedData
import com.example.a11yframework.core.ScrapedRecordIdentity
import com.example.a11yframework.remote.HospitalTask
import com.example.a11yframework.search.SearchController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min

/**
 * 手机端采集编排器。
 *
 * 框架职责只剩下：
 * 1. 启动目标 App
 * 2. 选择对应插件包
 * 3. 执行插件导航步骤
 * 4. 收敛采集窗口、滚动聚合结果、组装回传
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
        private const val KEY_MANUAL_APP_WAIT_TIMEOUT_MS = "manual_app_wait_timeout_ms"
        private const val DEFAULT_MANUAL_APP_WAIT_TIMEOUT_MS = 60_000L
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
        private const val KEY_EXPAND_SETTLE_MS = "expand_settle_ms"
        private const val DEFAULT_EXPAND_SETTLE_MS = 1_000L
        private const val KEY_MAX_EXPAND_CLICKS_PER_ROUND = "max_expand_clicks_per_round"
        private const val DEFAULT_MAX_EXPAND_CLICKS_PER_ROUND = 4
        private const val INITIAL_VIEWPORT_MAX_ATTEMPTS = 3
        private const val INITIAL_VIEWPORT_RETRY_DELAY_MS = 900L
        private const val COLLECTING_SCRAPE_COOLDOWN_MS = 1_200L
        private const val MERCHANT_CONTEXT_RECOVER_SCROLL_ATTEMPTS = 2
        private const val MERCHANT_CONTEXT_RECOVER_SCROLL_SETTLE_MS = 850L
        private val DEFAULT_EXPAND_KEYWORDS = listOf(
            "展开更多",
            "查看全部",
            "更多团购",
            "更多套餐",
            "全部团购",
            "全部套餐"
        )
        private val EXPAND_KEYWORD_EXCLUDES = setOf("展开")
    }

    private val executionMutex = Mutex()
    private val searchController by lazy { SearchController(service) }
    private val navigationExecutor by lazy { NavigationExecutor(searchController) }

    @Volatile
    private var activeCapture: ActiveCapture? = null

    suspend fun executeTask(
        task: HospitalTask,
        launchTargetApp: Boolean = true
    ): CaptureExecutionResult = executionMutex.withLock {
        val targetPackage = resolveTargetPackage(task)
        val appPlugin = service.appPluginManager.findPluginForPackage(targetPackage)

        task.targetPackage = targetPackage

        val activeExecution = ActiveCapture(
            task = task,
            targetPackage = targetPackage,
            navigationPluginId = appPlugin?.pluginId,
            stage = if (launchTargetApp) CaptureStage.OPENING_APP else CaptureStage.WAITING_TARGET_APP
        )
        activeCapture = activeExecution

        return try {
            if (!launchTargetApp) {
                Log.i(TAG, "Waiting for user to open target app manually: $targetPackage")
            }

            if (launchTargetApp && !service.launchTargetApp(targetPackage)) {
                failure(task, targetPackage, "无法打开目标 App: $targetPackage")
            } else if (!waitForPackage(
                    targetPackage,
                    if (launchTargetApp) 15_000L else resolveManualAppWaitTimeoutMs()
                )
            ) {
                failure(task, targetPackage, "等待目标 App 超时: $targetPackage")
            } else {
                if (!launchTargetApp) {
                    Log.i(TAG, "Target app opened manually, continue capture: $targetPackage")
                }
                delay(resolveAppStartDelayMs(appPlugin))

                val attemptResult = when {
                    appPlugin?.captureFlow != null -> executePluginCapture(activeExecution, appPlugin)
                    isDouyinPackage(targetPackage) -> executeDouyinCapture(activeExecution)
                    else -> executeGenericCapture(activeExecution)
                }

                if (attemptResult.records.isEmpty()) {
                    failure(
                        task,
                        targetPackage,
                        attemptResult.errorMessage ?: "等待抓取结果超时或未产出数据"
                    )
                } else {
                    service.dataStore.saveData(attemptResult.records)
                    success(task, targetPackage, attemptResult.records)
                }
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "任务被取消，等待外层决定是否恢复: ${task.hospitalName}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "任务执行异常: ${task.hospitalName}", e)
            failure(task, targetPackage, e.message ?: "未知异常")
        } finally {
            activeCapture = null
        }
    }

    fun hasActiveCapture(): Boolean = activeCapture != null

    fun onWindowChanged(packageName: String) {
        activeCapture?.lastSeenPackage = packageName
    }

    fun onRecordsCaptured(packageName: String, records: List<ScrapedData>): CaptureAppendResult {
        val activeExecution = activeCapture ?: return CaptureAppendResult()
        if (records.isEmpty()) return CaptureAppendResult()
        if (packageName != activeExecution.targetPackage) return CaptureAppendResult()
        if (activeExecution.stage != CaptureStage.COLLECTING) return CaptureAppendResult()

        val appendResult = activeExecution.appendRecords(records)
        if (appendResult.changed) {
            Log.i(
                TAG,
                "已接收抓取结果: hospital=${activeExecution.task.hospitalName}, added=${appendResult.addedCount}, updated=${appendResult.updatedCount}, total=${activeExecution.recordCount()}"
            )
        }

        return appendResult
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

            val metadata = record.metadata.toMutableMap()
            metadata["captureStage"] = activeExecution.stage.name.lowercase()
            activeExecution.navigationPluginId?.let { metadata["navigationPluginId"] = it }

            record.copy(
                content = content.toMap(),
                rawText = rawText,
                metadata = metadata.toMap()
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

    private suspend fun executePluginCapture(
        activeExecution: ActiveCapture,
        plugin: AppPluginBundle
    ): CaptureAttemptResult {
        activeExecution.stage = CaptureStage.EXECUTING_PLUGIN_FLOW

        val navigationResult = navigationExecutor.execute(plugin, activeExecution.task)
        if (!navigationResult.success) {
            return CaptureAttemptResult(
                errorMessage = navigationResult.errorMessage ?: "插件导航流程执行失败"
            )
        }

        activeExecution.stage = CaptureStage.COLLECTING
        val records = collectDetailRecords(
            activeExecution,
            plugin.captureFlow?.collection
        )

        return if (records.isEmpty()) {
            CaptureAttemptResult(errorMessage = "插件流程执行完成，但未采集到数据")
        } else {
            CaptureAttemptResult(records = records)
        }
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

        if (
            searchController.isOnMerchantDetailPage(activeExecution.task.hospitalName) ||
            service.isCurrentTargetPage(activeExecution.targetPackage)
        ) {
            Log.i(
                TAG,
                "Resume Douyin capture from current merchant detail page: ${activeExecution.task.hospitalName}"
            )
            activeExecution.stage = CaptureStage.COLLECTING
            val resumedRecords = collectDetailRecords(activeExecution, null)
            return if (resumedRecords.isEmpty()) {
                CaptureAttemptResult(errorMessage = "恢复到商家详情页后未采集到团购数据")
            } else {
                CaptureAttemptResult(records = resumedRecords)
            }
        }

        val alreadyOnResultPage =
            searchController.isOnDouyinMerchantResultPage(activeExecution.task.hospitalName)
        if (alreadyOnResultPage) {
            Log.i(
                TAG,
                "Resume Douyin capture from merchant result page: ${activeExecution.task.hospitalName}"
            )
        } else {
            val searchSuccess = searchController.searchDouyinGroupBuy(activeExecution.task.hospitalName)
            if (!searchSuccess) {
                return CaptureAttemptResult(
                    errorMessage = "未能进入抖音团购搜索并提交关键词"
                )
            }
        }

        activeExecution.stage = CaptureStage.WAITING_RESULT_LIST
        if (!alreadyOnResultPage) {
            delay(resolveResultListWaitMs())
        }

        val merchantOpened = searchController.openMerchantResult(activeExecution.task.hospitalName)
        if (!merchantOpened) {
            return CaptureAttemptResult(
                errorMessage = "未在搜索结果页定位到目标商家: ${activeExecution.task.hospitalName}"
            )
        }

        activeExecution.stage = CaptureStage.OPENING_DETAIL
        delay(resolveDetailOpenDelayMs())
        val collectableViewportReady = prepareDouyinCollectableViewport(activeExecution)
        if (!collectableViewportReady) {
            return CaptureAttemptResult(errorMessage = "进入商家详情后未定位到可采集团购视口")
        }

        activeExecution.stage = CaptureStage.COLLECTING
        val records = collectDetailRecords(activeExecution, null)
        return if (records.isEmpty()) {
            CaptureAttemptResult(errorMessage = "进入商家详情后未采集到团购数据")
        } else {
            CaptureAttemptResult(records = records)
        }
    }

    private suspend fun prepareDouyinCollectableViewport(activeExecution: ActiveCapture): Boolean {
        val merchantName = activeExecution.task.hospitalName
        val detailWaitMs = resolveDetailOpenDelayMs() + 1_500L

        if (searchController.waitForMerchantHomepageAnchors(merchantName, detailWaitMs)) {
            return true
        }

        if (service.isCurrentTargetPage(activeExecution.targetPackage)) {
            return true
        }

        repeat(3) { attempt ->
            if (!searchController.isOnMerchantDetailPage(merchantName)) {
                return false
            }

            val movedUp = searchController.scrollCurrentPage(forward = false)
            if (!movedUp) {
                return false
            }

            Log.i(
                TAG,
                "Adjust Douyin merchant viewport upward before collection: step=${attempt + 1}"
            )
            delay(resolveScrollSettleMs(null))

            if (searchController.waitForMerchantHomepageAnchors(merchantName, 900L)) {
                return true
            }
            if (service.isCurrentTargetPage(activeExecution.targetPackage)) {
                return true
            }
        }

        return false
    }

    private suspend fun waitForCollectedRecords(
        activeExecution: ActiveCapture,
        timeoutMs: Long
    ): List<ScrapedData> {
        val baselineRevision = activeExecution.revision()
        waitForCaptureProgress(activeExecution, baselineRevision, timeoutMs)
        return activeExecution.snapshotRecords()
    }

    private suspend fun collectDetailRecords(
        activeExecution: ActiveCapture,
        collectionConfig: CollectionConfig?
    ): List<ScrapedData> {
        val deadline = System.currentTimeMillis() + resolveCaptureTimeoutMs(collectionConfig)
        val maxScrollRounds = resolveMaxScrollRounds(collectionConfig)
        val maxIdleScrollRounds = resolveMaxIdleScrollRounds(collectionConfig)

        var idleRounds = 0
        var scrollRounds = 0

        val initialViewportReady = stabilizeInitialViewport(
            activeExecution = activeExecution,
            collectionConfig = collectionConfig,
            deadline = deadline
        )
        if (!initialViewportReady) {
            Log.w(TAG, "Initial Douyin viewport not stable enough for scrolling, stop current capture")
            return activeExecution.snapshotRecords()
        }

        var groupBuyTabReady = searchController.hasMerchantGroupBuyContent()
        if (!groupBuyTabReady) {
            groupBuyTabReady = searchController.ensureMerchantGroupBuyTab(maxAttempts = 2)
        }
        if (!groupBuyTabReady && !searchController.hasMerchantGroupBuyContent()) {
            Log.w(TAG, "Merchant group-buy tab not confirmed before collection, stop current capture")
            return activeExecution.snapshotRecords()
        }

        if (shouldStopCollection(collectionConfig)) {
            Log.i(TAG, "Stop collection after initial viewport capture")
            return activeExecution.snapshotRecords()
        }

        while (scrollRounds < maxScrollRounds && remainingTime(deadline) > 0) {
            if (!ensureMerchantDetailContext(activeExecution, "before_scroll:$scrollRounds")) {
                Log.i(TAG, "Stop collection after losing merchant detail context before scroll")
                break
            }

            if (!searchController.hasMerchantGroupBuyContent()) {
                val ensured = searchController.ensureMerchantGroupBuyTab(maxAttempts = 1)
                if (!ensured && !searchController.hasMerchantGroupBuyContent()) {
                    Log.w(TAG, "Merchant group-buy tab not confirmed before scroll, stop current capture")
                    break
                }
            }

            if (shouldStopCollection(collectionConfig)) {
                Log.i(TAG, "Stop collection before next scroll: round=$scrollRounds")
                break
            }

            val beforeCount = activeExecution.recordCount()
            val beforeRevision = activeExecution.revision()
            val scrolled = searchController.scrollCurrentPage()
            if (!scrolled) {
                break
            }

            scrollRounds++
            delay(resolveScrollSettleMs(collectionConfig))
            collectCurrentViewport(activeExecution, collectionConfig, deadline)

            if (!ensureMerchantDetailContext(activeExecution, "after_scroll:$scrollRounds")) {
                Log.i(TAG, "Stop collection after losing merchant detail context after scroll")
                break
            }

            if (shouldStopCollection(collectionConfig)) {
                Log.i(TAG, "Stop collection after scroll round=$scrollRounds")
                break
            }

            val afterCount = activeExecution.recordCount()
            val afterRevision = activeExecution.revision()
            if (afterCount <= beforeCount && afterRevision <= beforeRevision) {
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

    private suspend fun stabilizeInitialViewport(
        activeExecution: ActiveCapture,
        collectionConfig: CollectionConfig?,
        deadline: Long
    ): Boolean {
        repeat(INITIAL_VIEWPORT_MAX_ATTEMPTS) { attempt ->
            collectCurrentViewport(activeExecution, collectionConfig, deadline)

            if (activeExecution.recordCount() > 0) {
                Log.i(TAG, "Initial viewport captured records before first scroll: count=${activeExecution.recordCount()}")
                return true
            }

            val stillOnTargetPage = service.isCurrentTargetPage(activeExecution.targetPackage)
            if (stillOnTargetPage) {
                Log.i(TAG, "Initial viewport confirmed as target page before first scroll: attempt=${attempt + 1}")
                return true
            }

            val stillOnMerchantDetail = searchController.isOnMerchantDetailPage(
                activeExecution.task.hospitalName
            )
            if (!stillOnMerchantDetail) {
                val recovered = searchController.recoverMerchantDetailPage(
                    merchantName = activeExecution.task.hospitalName,
                    maxBackAttempts = 2
                )
                if (recovered) {
                    Log.i(
                        TAG,
                        "Recovered merchant detail context during initial viewport stabilization: attempt=${attempt + 1}"
                    )
                    delay(min(INITIAL_VIEWPORT_RETRY_DELAY_MS, remainingTime(deadline)))
                    return@repeat
                }
                Log.w(
                    TAG,
                    "Initial viewport lost merchant detail context before first scroll: attempt=${attempt + 1}"
                )
                return false
            }

            if (attempt < INITIAL_VIEWPORT_MAX_ATTEMPTS - 1 && remainingTime(deadline) > 0L) {
                Log.i(
                    TAG,
                    "Retry initial viewport capture before first scroll: attempt=${attempt + 1}"
                )
                delay(min(INITIAL_VIEWPORT_RETRY_DELAY_MS, remainingTime(deadline)))
            }
        }

        return activeExecution.recordCount() > 0 || service.isCurrentTargetPage(activeExecution.targetPackage)
    }

    private fun shouldStopCollection(collectionConfig: CollectionConfig?): Boolean {
        val stopTextsAll = resolveStopTextsAll(collectionConfig)
        val stopTextsAny = resolveStopTextsAny(collectionConfig)
        val stopTextsNone = resolveStopTextsNone(collectionConfig)
        if (stopTextsAll.isEmpty() && stopTextsAny.isEmpty() && stopTextsNone.isEmpty()) {
            return false
        }

        val matchResult = searchController.matchCurrentPageTexts(
            requiredAllTexts = stopTextsAll,
            requiredAnyTexts = stopTextsAny,
            absentTexts = stopTextsNone
        )
        if (!matchResult.matched) {
            return false
        }

        Log.i(
            TAG,
            "Detected end-of-list markers: all=${matchResult.matchedAllTexts.joinToString(",")}, any=${matchResult.matchedAnyTexts.joinToString(",")}, none=${stopTextsNone.joinToString(",")}"
        )
        return true
    }

    private suspend fun collectCurrentViewport(
        activeExecution: ActiveCapture,
        collectionConfig: CollectionConfig?,
        deadline: Long
    ) {
        val baselineRevision = activeExecution.revision()
        val expandedCount = expandVisibleSections(collectionConfig)
        if (expandedCount > 0) {
            delay(resolveExpandSettleMs(collectionConfig))
        }

        service.scrapeCurrentPageNow(activeExecution.targetPackage)

        val progressed = waitForCaptureProgress(
            activeExecution,
            baselineRevision = baselineRevision,
            timeoutMs = min(resolveCaptureRoundWaitMs(collectionConfig), remainingTime(deadline))
        )

        if (!progressed && remainingTime(deadline) > 0L) {
            delay(400)
            service.scrapeCurrentPageNow(activeExecution.targetPackage)
            waitForCaptureProgress(
                activeExecution,
                baselineRevision = baselineRevision,
                timeoutMs = min(900L, remainingTime(deadline))
            )
        }
    }

    private suspend fun expandVisibleSections(collectionConfig: CollectionConfig?): Int {
        val hasGroupBuyContent = searchController.hasMerchantGroupBuyContent()
        val groupBuyReady = if (hasGroupBuyContent) {
            true
        } else {
            searchController.ensureMerchantGroupBuyTab(maxAttempts = 1)
        }
        if (!groupBuyReady && !searchController.hasMerchantGroupBuyContent()) {
            Log.w(TAG, "Merchant group-buy tab not confirmed before expand, skip expand for current viewport")
            return 0
        }

        if (searchController.hasMerchantCollapseMarker()) {
            Log.i(TAG, "Merchant group-buy section already fully expanded (collapse marker found)")
            return 0
        }

        val expandKeywords = resolveExpandKeywords(collectionConfig)
        val maxClicks = resolveMaxExpandClicksPerRound(collectionConfig)
        if (expandKeywords.isEmpty() || maxClicks <= 0) {
            return 0
        }

        val expandedCount = searchController.clickMerchantExpandTexts(
            targetTexts = expandKeywords,
            maxClicks = maxClicks
        )

        if (expandedCount > 0) {
            Log.i(
                TAG,
                "Expanded detail sections: clicks=$expandedCount, keywords=${expandKeywords.joinToString(",")}"
            )
        }

        return expandedCount
    }

    private suspend fun ensureMerchantDetailContext(
        activeExecution: ActiveCapture,
        phase: String
    ): Boolean {
        if (searchController.hasMerchantGroupBuyContent()) {
            return true
        }

        if (searchController.isOnMerchantDetailPage(activeExecution.task.hospitalName)) {
            return true
        }

        if (service.isCurrentTargetPage(activeExecution.targetPackage)) {
            return true
        }

        if (tryRecoverMerchantGroupBuyContext(activeExecution, phase)) {
            return true
        }

        val recovered = searchController.recoverMerchantDetailPage(
            merchantName = activeExecution.task.hospitalName,
            maxBackAttempts = 2
        )
        if (!recovered) {
            Log.w(TAG, "Lost merchant detail context and failed to recover: phase=$phase")
            return false
        }

        if (tryRecoverMerchantGroupBuyContext(activeExecution, "$phase:after_back")) {
            return true
        }

        delay(700)
        return searchController.isOnMerchantDetailPage(activeExecution.task.hospitalName) ||
            service.isCurrentTargetPage(activeExecution.targetPackage) ||
            searchController.hasMerchantGroupBuyContent()
    }

    private suspend fun tryRecoverMerchantGroupBuyContext(
        activeExecution: ActiveCapture,
        phase: String
    ): Boolean {
        if (searchController.hasMerchantGroupBuyContent()) {
            return true
        }

        val directEnsured = searchController.ensureMerchantGroupBuyTab(maxAttempts = 2)
        if (directEnsured && searchController.hasMerchantGroupBuyContent()) {
            Log.i(TAG, "Recovered merchant group-buy context by direct tab ensure: phase=$phase")
            return true
        }

        repeat(MERCHANT_CONTEXT_RECOVER_SCROLL_ATTEMPTS) { attempt ->
            val movedUp = searchController.scrollCurrentPage(forward = false)
            if (!movedUp) {
                return@repeat
            }

            delay(min(MERCHANT_CONTEXT_RECOVER_SCROLL_SETTLE_MS, resolveScrollSettleMs()))
            if (searchController.hasMerchantGroupBuyContent()) {
                Log.i(
                    TAG,
                    "Recovered merchant group-buy context by upward scroll: phase=$phase, attempt=${attempt + 1}"
                )
                return true
            }

            val ensured = searchController.ensureMerchantGroupBuyTab(maxAttempts = 1)
            if (ensured && searchController.hasMerchantGroupBuyContent()) {
                Log.i(
                    TAG,
                    "Recovered merchant group-buy context by upward scroll + tab ensure: phase=$phase, attempt=${attempt + 1}"
                )
                return true
            }
        }

        return false
    }

    private suspend fun waitForCaptureProgress(
        activeExecution: ActiveCapture,
        baselineRevision: Int,
        timeoutMs: Long
    ): Boolean {
        if (timeoutMs <= 0L) {
            return activeExecution.revision() > baselineRevision
        }

        val startAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startAt < timeoutMs) {
            if (activeExecution.revision() > baselineRevision) {
                return true
            }
            delay(400)
        }

        return activeExecution.revision() > baselineRevision
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

    private fun resolveCaptureTimeoutMs(collectionConfig: CollectionConfig? = null): Long {
        return collectionConfig?.captureTimeoutMs?.takeIf { it > 0L }
            ?: service.configManager.getPluginConfigInt(
                "system",
                KEY_CAPTURE_TIMEOUT_MS,
                DEFAULT_CAPTURE_TIMEOUT_MS.toInt()
            ).toLong()
    }

    private fun resolveAppStartDelayMs(plugin: AppPluginBundle? = null): Long {
        return plugin?.captureFlow?.appStartDelayMs?.takeIf { it > 0L }
            ?: service.configManager.getPluginConfigInt(
                "system",
                KEY_APP_START_DELAY_MS,
                DEFAULT_APP_START_DELAY_MS.toInt()
            ).toLong()
    }

    private fun resolveManualAppWaitTimeoutMs(): Long {
        return service.configManager.getPluginConfigInt(
            "system",
            KEY_MANUAL_APP_WAIT_TIMEOUT_MS,
            DEFAULT_MANUAL_APP_WAIT_TIMEOUT_MS.toInt()
        ).toLong()
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

    private fun resolveScrollSettleMs(collectionConfig: CollectionConfig? = null): Long {
        return collectionConfig?.scrollSettleMs?.takeIf { it > 0L }
            ?: service.configManager.getPluginConfigInt(
                "system",
                KEY_SCROLL_SETTLE_MS,
                DEFAULT_SCROLL_SETTLE_MS.toInt()
            ).toLong()
    }

    private fun resolveCaptureRoundWaitMs(collectionConfig: CollectionConfig? = null): Long {
        return collectionConfig?.captureRoundWaitMs?.takeIf { it > 0L }
            ?: service.configManager.getPluginConfigInt(
                "system",
                KEY_CAPTURE_ROUND_WAIT_MS,
                DEFAULT_CAPTURE_ROUND_WAIT_MS.toInt()
            ).toLong()
    }

    private fun resolveMaxScrollRounds(collectionConfig: CollectionConfig? = null): Int {
        return collectionConfig?.maxScrollRounds?.takeIf { it > 0 }
            ?: service.configManager.getPluginConfigInt(
                "system",
                KEY_MAX_SCROLL_ROUNDS,
                DEFAULT_MAX_SCROLL_ROUNDS
            )
    }

    private fun resolveMaxIdleScrollRounds(collectionConfig: CollectionConfig? = null): Int {
        return collectionConfig?.maxIdleScrollRounds?.takeIf { it > 0 }
            ?: service.configManager.getPluginConfigInt(
                "system",
                KEY_MAX_IDLE_SCROLL_ROUNDS,
                DEFAULT_MAX_IDLE_SCROLL_ROUNDS
            )
    }

    private fun resolveExpandKeywords(collectionConfig: CollectionConfig? = null): List<String> {
        return collectionConfig?.expandKeywords
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.filterNot { EXPAND_KEYWORD_EXCLUDES.contains(it) }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_EXPAND_KEYWORDS
    }

    private fun resolveExpandSettleMs(collectionConfig: CollectionConfig? = null): Long {
        return collectionConfig?.expandSettleMs?.takeIf { it > 0L }
            ?: service.configManager.getPluginConfigInt(
                "system",
                KEY_EXPAND_SETTLE_MS,
                DEFAULT_EXPAND_SETTLE_MS.toInt()
            ).toLong()
    }

    private fun resolveMaxExpandClicksPerRound(collectionConfig: CollectionConfig? = null): Int {
        return collectionConfig?.maxExpandClicksPerRound?.takeIf { it > 0 }
            ?: service.configManager.getPluginConfigInt(
                "system",
                KEY_MAX_EXPAND_CLICKS_PER_ROUND,
                DEFAULT_MAX_EXPAND_CLICKS_PER_ROUND
            )
    }

    private fun resolveStopTextsAll(collectionConfig: CollectionConfig? = null): List<String> {
        return collectionConfig?.stopTextsAll
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()
    }

    private fun resolveStopTextsAny(collectionConfig: CollectionConfig? = null): List<String> {
        return collectionConfig?.stopTextsAny
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()
    }

    private fun resolveStopTextsNone(collectionConfig: CollectionConfig? = null): List<String> {
        return collectionConfig?.stopTextsNone
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()
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
    val navigationPluginId: String? = null,
    var stage: CaptureStage = CaptureStage.OPENING_APP,
    var lastSeenPackage: String = "",
    val recordsByKey: LinkedHashMap<String, ScrapedData> = linkedMapOf(),
    var progressRevision: Int = 0
)

private enum class CaptureStage {
    OPENING_APP,
    WAITING_TARGET_APP,
    EXECUTING_PLUGIN_FLOW,
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

data class CaptureAppendResult(
    val addedCount: Int = 0,
    val updatedCount: Int = 0
) {
    val changed: Boolean
        get() = addedCount > 0 || updatedCount > 0
}

private fun ActiveCapture.appendRecords(records: List<ScrapedData>): CaptureAppendResult {
    synchronized(this) {
        var addedCount = 0
        var updatedCount = 0
        records.forEach { record ->
            val key = ScrapedRecordIdentity.buildBusinessKey(record)
            val existing = recordsByKey[key]
            if (existing == null) {
                recordsByKey[key] = record
                addedCount++
            } else {
                val merged = ScrapedRecordIdentity.merge(existing, record)
                if (merged != existing) {
                    recordsByKey[key] = merged
                    updatedCount++
                }
            }
        }
        if (addedCount > 0 || updatedCount > 0) {
            progressRevision++
        }
        return CaptureAppendResult(addedCount = addedCount, updatedCount = updatedCount)
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

private fun ActiveCapture.revision(): Int {
    synchronized(this) {
        return progressRevision
    }
}
