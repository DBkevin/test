package com.example.a11yframework.data

import com.example.a11yframework.core.ScrapedData
import org.junit.Assert.assertEquals
import org.junit.Test

class DataStoreDedupKeyTest {

    @Test
    fun `dedup key stays stable for same item across repeated captures`() {
        val first = ScrapedData(
            pluginId = "douyin",
            pageType = "hospital_detail",
            dataType = "hospital_group_buy",
            content = mapOf(
                "merchantName" to "郑州美莱医疗美容医院",
                "groupBuyTitle" to "【长效水光】润致娃娃针2ml",
                "price" to "185.1",
                "originalPrice" to "199.0",
                "sales" to "已售1000+"
            ),
            rawText = "A"
        )

        val second = first.copy(
            content = first.content + mapOf("sales" to "已售1000+"),
            rawText = "B"
        )

        assertEquals(DataStore.buildDedupKey(first), DataStore.buildDedupKey(second))
    }
}
