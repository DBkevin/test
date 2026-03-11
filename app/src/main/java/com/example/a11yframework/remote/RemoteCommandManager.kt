package com.example.a11yframework.remote

import android.util.Log
import com.example.a11yframework.capture.CaptureExecutionResult
import com.example.a11yframework.config.ConfigManager
import com.example.a11yframework.core.FrameworkAccessibilityService
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 远程命令管理器
 * 
 * 功能:
 * 1. 接收远程下发的医院列表
 * 2. 管理抓取任务队列
 * 3. 上报抓取进度
 */
class RemoteCommandManager(
    private val service: FrameworkAccessibilityService
) {
    
    companion object {
        private const val TAG = "RemoteCommand"
        
        // 默认配置（可在 APP 界面修改）
        private const val DEFAULT_SERVER_URL = "http://192.168.1.100:8080"
        private const val POLL_INTERVAL_MS = 5000L  // 轮询间隔 5 秒
        
        // 配置 Key
        private const val KEY_SERVER_URL = "remote_server_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_POLL_ENABLED = "poll_enabled"
    }
    
    private val configManager: ConfigManager
    private val httpClient: OkHttpClient
    private var pollJob: Job? = null
    private var executionJob: Job? = null
    private var isRunning = false
    
    // 任务队列
    private val taskQueue = mutableListOf<HospitalTask>()
    private var currentTask: HospitalTask? = null
    
    // 监听器
    var onTaskReceived: ((HospitalTask) -> Unit)? = null
    var onTaskCompleted: ((HospitalTask) -> Unit)? = null
    var taskExecutor: (suspend (HospitalTask) -> CaptureExecutionResult)? = null
    var onExecutionStopped: (() -> Unit)? = null
    
    init {
        configManager = service.configManager
        
        // 配置 HTTP 客户端
        httpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
    
    /**
     * 开始轮询远程指令
     */
    fun startPolling() {
        if (isRunning) {
            Log.w(TAG, "Already running")
            return
        }
        
        val enabled = configManager.getPluginConfigBool("system", KEY_POLL_ENABLED, true)
        if (!enabled) {
            Log.i(TAG, "Polling disabled")
            return
        }
        
        isRunning = true
        pollJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            while (isRunning && isActive) {
                try {
                    pollCommands()
                } catch (e: Exception) {
                    Log.e(TAG, "Poll error", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        
        Log.i(TAG, "Polling started")
    }
    
    /**
     * 停止轮询
     */
    fun stopPolling() {
        isRunning = false
        pollJob?.cancel()
        executionJob?.cancel()
        pollJob = null
        executionJob = null
        Log.i(TAG, "Polling stopped")
    }
    
    /**
     * 轮询远程指令
     */
    private suspend fun pollCommands() {
        val serverUrl = configManager.getPluginConfigString("system", KEY_SERVER_URL, DEFAULT_SERVER_URL)
        val deviceId = getDeviceId()
        
        val url = "$serverUrl/api/command/poll?device_id=$deviceId"
        
        try {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrEmpty()) {
                    val command = parseCommand(body)
                    handleCommand(command)
                }
            } else {
                Log.w(TAG, "Poll failed: ${response.code}")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error", e)
        }
    }
    
    /**
     * 解析远程指令
     */
    private fun parseCommand(json: String): RemoteCommand {
        return try {
            val gson = com.google.code.gson.Gson()
            gson.fromJson(json, RemoteCommand::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Parse command error", e)
            RemoteCommand()
        }
    }
    
    /**
     * 处理远程指令
     */
    private fun handleCommand(command: RemoteCommand) {
        when (command.type) {
            "hospital_list" -> {
                // 接收医院列表
                handleHospitalList(command)
            }
            "start_capture" -> {
                // 开始抓取
                handleStartCapture(command)
            }
            "stop_capture" -> {
                // 停止抓取
                handleStopCapture(command)
            }
            "update_config" -> {
                // 更新配置
                handleUpdateConfig(command)
            }
        }
    }
    
    /**
     * 处理医院列表指令
     */
    private fun handleHospitalList(command: RemoteCommand) {
        val hospitals = command.data?.hospitals ?: emptyList()
        val targetPackage = command.data?.appPackage
        
        if (hospitals.isEmpty()) {
            Log.w(TAG, "Empty hospital list")
            return
        }
        
        // 清空旧任务
        taskQueue.clear()
        
        // 添加新任务
        hospitals.forEachIndexed { index, hospital ->
            val task = HospitalTask(
                id = index,
                hospitalName = hospital,
                status = TaskStatus.PENDING,
                createdAt = System.currentTimeMillis(),
                targetPackage = targetPackage
            )
            taskQueue.add(task)
        }
        
        Log.i(TAG, "Received ${taskQueue.size} hospital tasks")
        
        // 通知有新任务
        if (taskQueue.isNotEmpty()) {
            currentTask = taskQueue.first()
            onTaskReceived?.invoke(currentTask!!)
        }
    }
    
    /**
     * 处理开始抓取指令
     */
    private fun handleStartCapture(command: RemoteCommand) {
        Log.i(TAG, "Start capture command received")
        // 开始执行任务队列
        startTaskExecution()
    }
    
    /**
     * 处理停止抓取指令
     */
    private fun handleStopCapture(command: RemoteCommand) {
        Log.i(TAG, "Stop capture command received")
        // 停止任务执行
        stopTaskExecution()
    }
    
    /**
     * 处理更新配置指令
     */
    private fun handleUpdateConfig(command: RemoteCommand) {
        val config = command.data?.config ?: return
        
        config.forEach { (key, value) ->
            configManager.setPluginConfigString("system", key, value.toString())
        }
        
        Log.i(TAG, "Config updated: $config")
    }
    
    /**
     * 开始执行任务队列
     */
    private fun startTaskExecution() {
        if (executionJob?.isActive == true) {
            Log.w(TAG, "Task execution already running")
            return
        }

        executionJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            while (taskQueue.isNotEmpty() && isRunning) {
                val task = taskQueue.removeAt(0)
                currentTask = task
                
                task.status = TaskStatus.RUNNING
                Log.i(TAG, "Executing task: ${task.hospitalName}")
                
                // 通知执行任务
                onTaskReceived?.invoke(task)

                val executor = taskExecutor
                if (executor == null) {
                    task.status = TaskStatus.FAILED
                    task.errorMessage = "未配置任务执行器"
                    task.completedAt = System.currentTimeMillis()
                    reportTaskCompletion(task)
                    continue
                }

                val executionResult = try {
                    executor.invoke(task)
                } catch (e: Exception) {
                    Log.e(TAG, "Task execution failed: ${task.hospitalName}", e)
                    CaptureExecutionResult(
                        success = false,
                        targetPackage = task.targetPackage ?: "",
                        errorMessage = e.message ?: "任务执行异常"
                    )
                }

                task.targetPackage = executionResult.targetPackage.ifBlank { task.targetPackage ?: "" }
                task.resultData = executionResult.data
                task.errorMessage = executionResult.errorMessage
                task.status = if (executionResult.success) {
                    TaskStatus.COMPLETED
                } else {
                    TaskStatus.FAILED
                }
                task.completedAt = System.currentTimeMillis()

                reportTaskCompletion(task)
            }

            currentTask = null
        }
    }
    
    /**
     * 停止任务执行
     */
    private fun stopTaskExecution() {
        executionJob?.cancel()
        executionJob = null
        taskQueue.clear()
        currentTask = null
        onExecutionStopped?.invoke()
        Log.i(TAG, "Task execution stopped")
    }
    
    /**
     * 上报任务完成
     */
    private fun reportTaskCompletion(task: HospitalTask) {
        if (task.status == TaskStatus.COMPLETED) {
            onTaskCompleted?.invoke(task)
        }
        
        // 发送到服务器
        CoroutineScope(Dispatchers.IO).launch {
            sendTaskResult(task)
        }
        
        Log.i(TAG, "Task completed: ${task.hospitalName}")
    }
    
    /**
     * 发送任务结果到服务器
     */
    private suspend fun sendTaskResult(task: HospitalTask) {
        val serverUrl = configManager.getPluginConfigString("system", KEY_SERVER_URL, DEFAULT_SERVER_URL)
        val deviceId = getDeviceId()
        
        val url = "$serverUrl/api/command/result"
        
        val result = TaskResult(
            deviceId = deviceId,
            taskId = task.id,
            hospitalName = task.hospitalName,
            status = task.status.name,
            data = task.resultData,
            timestamp = System.currentTimeMillis(),
            errorMessage = task.errorMessage,
            targetPackage = task.targetPackage
        )
        
        val gson = com.google.code.gson.Gson()
        val json = gson.toJson(result)
        
        try {
            val mediaType = okhttp3.MediaType.parse("application/json; charset=utf-8")
            val body = json.toRequestBody(mediaType)
            
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (response.isSuccessful) {
                Log.i(TAG, "Task result sent: ${task.hospitalName}")
            } else {
                Log.w(TAG, "Send result failed: ${response.code}")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Send result error", e)
        }
    }
    
    /**
     * 获取设备 ID
     */
    private fun getDeviceId(): String {
        val savedId = configManager.getPluginConfigString("system", KEY_DEVICE_ID, "")
        if (savedId.isNotEmpty()) {
            return savedId
        }
        
        // 生成新 ID
        val deviceId = "device_" + System.currentTimeMillis()
        configManager.setPluginConfigString("system", KEY_DEVICE_ID, deviceId)
        return deviceId
    }
    
    /**
     * 获取当前任务
     */
    fun getCurrentTask(): HospitalTask? = currentTask
    
    /**
     * 获取任务队列大小
     */
    fun getQueueSize(): Int = taskQueue.size
}

/**
 * 远程指令数据类
 */
data class RemoteCommand(
    val type: String = "",
    val data: CommandData? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class CommandData(
    val hospitals: List<String>? = null,
    val config: Map<String, String>? = null,
    @SerializedName("app_package")
    val appPackage: String? = null
)

/**
 * 医院任务数据类
 */
data class HospitalTask(
    val id: Int,
    val hospitalName: String,
    var status: TaskStatus,
    val createdAt: Long,
    var completedAt: Long? = null,
    var resultData: Map<String, Any> = emptyMap(),
    var errorMessage: String? = null,
    var targetPackage: String? = null
)

enum class TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}

/**
 * 任务结果数据类
 */
data class TaskResult(
    @SerializedName("device_id")
    val deviceId: String,
    @SerializedName("task_id")
    val taskId: Int,
    @SerializedName("hospital_name")
    val hospitalName: String,
    val status: String,
    val data: Map<String, Any>,
    val timestamp: Long,
    @SerializedName("error_message")
    val errorMessage: String? = null,
    @SerializedName("target_package")
    val targetPackage: String? = null
)
