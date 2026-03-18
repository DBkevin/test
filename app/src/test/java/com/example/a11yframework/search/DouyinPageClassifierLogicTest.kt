package com.example.a11yframework.search

import org.junit.Assert.assertEquals
import org.junit.Test

class DouyinPageClassifierLogicTest {

    @Test
    fun resolvesGroupBuyHomeBeforeHomeFeed() {
        val kind = DouyinPageClassifier.resolveKind(
            DouyinPageSignals(
                hasSelectedGroupBuyTab = true,
                hasTopSearchButton = true,
                hasLocationSignal = true,
                hasBottomHomeTab = true
            )
        )

        assertEquals(DouyinPageKind.GROUPBUY_HOME, kind)
    }

    @Test
    fun resolvesMerchantHomeBeforeTailAndRecommendation() {
        val kind = DouyinPageClassifier.resolveKind(
            DouyinPageSignals(
                hasMerchantHeaderAnchor = true,
                hasMerchantBottomActionBar = true,
                hasMerchantTailSignal = true,
                hasRecommendationSignal = true
            )
        )

        assertEquals(DouyinPageKind.MERCHANT_HOME, kind)
    }

    @Test
    fun resolvesRecommendationBeforeUnknown() {
        val kind = DouyinPageClassifier.resolveKind(
            DouyinPageSignals(
                hasRecommendationSignal = true
            )
        )

        assertEquals(DouyinPageKind.RECOMMENDATION, kind)
    }

    @Test
    fun resolvesSearchInputBeforeGroupBuyHome() {
        val kind = DouyinPageClassifier.resolveKind(
            DouyinPageSignals(
                hasSearchInput = true,
                hasSearchSubmitButton = true,
                hasSelectedGroupBuyTab = true,
                hasTopSearchButton = true
            )
        )

        assertEquals(DouyinPageKind.GROUPBUY_SEARCH_INPUT, kind)
    }
}
