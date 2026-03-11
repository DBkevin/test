package com.example.a11yframework.capture

import com.example.a11yframework.core.ScrapedData
import com.example.a11yframework.remote.HospitalTask

/**
 * 组装任务执行结果，便于统一上报到远程服务
 */
object CapturePayloadBuilder {

    fun build(task: HospitalTask, records: List<ScrapedData>): Map<String, Any> {
        if (records.isEmpty()) {
            return mapOf(
                "hospital_name" to task.hospitalName,
                "target_package" to (task.targetPackage ?: ""),
                "record_count" to 0,
                "records" to emptyList<Map<String, Any>>()
            )
        }

        val recordsPayload = records.map { data ->
            mapOf(
                "timestamp" to data.timestamp,
                "plugin_id" to data.pluginId,
                "page_type" to data.pageType,
                "data_type" to data.dataType,
                "content" to data.content,
                "raw_text" to data.rawText,
                "metadata" to data.metadata
            )
        }

        return mapOf(
            "hospital_name" to task.hospitalName,
            "target_package" to (task.targetPackage ?: ""),
            "record_count" to records.size,
            "records" to recordsPayload
        )
    }
}
