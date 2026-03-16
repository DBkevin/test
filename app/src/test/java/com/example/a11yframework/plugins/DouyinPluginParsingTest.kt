package com.example.a11yframework.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DouyinPluginParsingTest {

    @Test
    fun `parse full group-buy card text from merchant page`() {
        val parsed = DouyinPlugin.parseGroupBuyCardText(
            "【面部提升】二代半岛黄金超声炮全模式(全面部+眼周+下颌缘),周一至周日可用,至少提前1天预约,随时退·过期退,原价3999.0元,现价3959.0元,已售50+领券抢购"
        )

        assertNotNull(parsed)
        assertEquals("【面部提升】二代半岛黄金超声炮全模式(全面部+眼周+下颌缘)", parsed?.title)
        assertEquals("3959.0", parsed?.price)
        assertEquals("3999.0", parsed?.originalPrice)
        assertEquals("已售50+", parsed?.sales)
    }

    @Test
    fun `normalize card text removes invisible separators`() {
        val normalized = DouyinPlugin.normalizeCardText("BB长效水光B润致娃娃针​2​m​l")

        assertEquals("BB长效水光B润致娃娃针2ml", normalized)
    }
}
