package com.example.a11yframework.capture

import android.util.Log
import com.example.a11yframework.core.FrameworkAccessibilityService
import com.example.a11yframework.core.ScrapedData
import com.example.a11yframework.remote.HospitalTask
import com.example.a11yframework.search.SearchController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 串联 V2 手机端执行链路：
 * 打开目标 App -> 搜索医院 -> 等待抓取结果 -> 组装返回
 */
class CaptureCoordinator(
    private val service: FrameworkAccessibilityService
) {

    companion object {
        private const val TAG = "CaptureCoordinator"
        private const val KEY_TARGET_APP_PACKAGE = "target_app_package"
        private const val DEFAULT_TARGET_APP_PACKAGE = "com.ss.android.ugc.aweme"
        private const val KEY_CAPTURE_TIMEOUT_MS = "capture_timeout_ms"
        private const val DEFAULT_CAPTURE_TIMEOUT_MS = 60_000L
        private const val KEY_APP_START_DELAY_MS = "app_start_delay_ms"
        private const val DEFAULT_APP_START_DELAY_MS = 2_500L
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
            targetPackage = targetPackage,
            resultDeferred = CompletableDeferred()
        )
        activeCapture = activeExecution

        return try {
            if (!service.launchTargetApp(targetPackage)) {
                failure(task, targetPackage, "无法打开目标 App: $targetPackage")
            } else if (!waitForPackage(targetPackage, 15_000L)) {
                failure(task, targetPackage, "等待目标 App 超时: $targetPackage")
            } else {
                delay(resolveAppStartDelayMs())
                activeExecution.stage = CaptureStage.SEARCHING

                val searchSuccess = searchController.search(task.hospitalName)
                if (!searchSuccess) {
                    failure(task, targetPackage, "搜索失败，未找到可操作的搜索框或搜索按钮")
                } else {
                    activeExecution.stage = CaptureStage.WAITING_RESULT
                    val records = withTimeoutOrNull(resolveCaptureTimeoutMs()) {
                        activeExecution.resultDeferred.await()
                    }

                    if (records.isNullOrEmpty()) {
                        failure(task, targetPackage, "等待抓取结果超时或未产出数据")
                    } else {
                        success(task, targetPackage, records)
                    }
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
        if (activeExecution.stage != CaptureStage.WAITING_RESULT) return
        if (activeExecution.resultDeferred.isCompleted) return

        activeExecution.stage = CaptureStage.CAPTURING
        activeExecution.resultDeferred.complete(records)
        Log.i(
            TAG,
            "已接收抓取结果: hospital=${activeExecution.task.hospitalName}, records=${records.size}"
        )
    }

    fun cancelActiveCapture(reason: String) {
        val activeExecution = activeCapture ?: return
        if (!activeExecution.resultDeferred.isCompleted) {
            activeExecution.resultDeferred.complete(emptyList())
        }
        Log.i(TAG, "取消当前抓取: $reason")
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
    val resultDeferred: CompletableDeferred<List<ScrapedData>>,
    var stage: CaptureStage = CaptureStage.OPENING_APP,
    var lastSeenPackage: String = ""
)

private enum class CaptureStage {
    OPENING_APP,
    SEARCHING,
    WAITING_RESULT,
    CAPTURING
}
