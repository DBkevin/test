package com.example.a11yframework.search

import org.junit.Assert.assertEquals
import org.junit.Test

class DouyinPageClassifierTest {

    @Test
    fun `resolve kind keeps merchant home when expand signal is visible`() {
        val signals = DouyinPageSignals(
            currentWindowClassName = "com.bytedance.locallife.page.poi.LifePoiActivity",
            hasMerchantBottomActionBar = true,
            hasMerchantCommerceSignal = true,
            hasMerchantExpandSignal = true,
            hasMerchantTailSignal = false
        )

        assertEquals(DouyinPageKind.MERCHANT_HOME, DouyinPageClassifier.resolveKind(signals))
    }

    @Test
    fun `resolve kind marks merchant tail only for true tail markers`() {
        val signals = DouyinPageSignals(
            currentWindowClassName = "com.bytedance.locallife.page.poi.LifePoiActivity",
            hasMerchantBottomActionBar = true,
            hasMerchantCommerceSignal = true,
            hasMerchantTailSignal = true
        )

        assertEquals(DouyinPageKind.MERCHANT_TAIL, DouyinPageClassifier.resolveKind(signals))
    }
}
