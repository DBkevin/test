package com.example.a11yframework.capture

import com.example.a11yframework.core.ScrapedData
import com.example.a11yframework.remote.HospitalTask
import com.example.a11yframework.remote.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePayloadBuilderTest {

    @Test
    fun `should build empty payload when no records captured`() {
        val task = HospitalTask(
            id = 1,
            hospitalName = "北京测试医院",
            status = TaskStatus.PENDING,
            createdAt = 1L,
            targetPackage = "com.ss.android.ugc.aweme"
        )

        val payload = CapturePayloadBuilder.build(task, emptyList())

        assertEquals("北京测试医院", payload["hospital_name"])
        assertEquals(0, payload["record_count"])
    }

    @Test
    fun `should include flattened records in payload`() {
        val task = HospitalTask(
            id = 2,
            hospitalName = "上海测试医院",
            status = TaskStatus.RUNNING,
            createdAt = 2L,
            targetPackage = "com.ss.android.ugc.aweme"
        )
        val records = listOf(
            ScrapedData(
                timestamp = 100L,
                pluginId = "douyin",
                pageType = "hospital_detail",
                dataType = "group_buys",
                content = mapOf("title" to "黄金微针", "price" to "¥999"),
                rawText = "黄金微针 ¥999"
            )
        )

        val payload = CapturePayloadBuilder.build(task, records)
        val capturedRecords = payload["records"] as List<*>

        assertEquals(1, payload["record_count"])
        assertTrue(capturedRecords.isNotEmpty())
    }
}
