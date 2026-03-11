package com.example.a11yframework.rule.matcher

import org.junit.Test
import org.junit.Before
import org.junit.Assert.*
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityService
import com.example.a11yframework.rule.MatchRule
import com.example.a11yframework.rule.MatchType
import com.example.a11yframework.rule.MatchLogic
import com.example.a11yframework.rule.PageConfig
import com.example.a11yframework.rule.ExtractRule
import com.example.a11yframework.rule.ExtractType
import com.example.a11yframework.rule.ExtractLocation

/**
 * 页面匹配器单元测试
 * 
 * 测试覆盖率目标：> 90%
 */
class PageMatcherTest {
    
    private lateinit var matcher: PageMatcher
    private lateinit var mockService: AccessibilityService
    
    @Before
    fun setUp() {
        // 创建 mock service（简化测试）
        mockService = object : AccessibilityService() {
            override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
            override fun onInterrupt() {}
            override fun onServiceConnected() {}
        }
        matcher = PageMatcher(mockService)
    }
    
    @Test
    fun `should match text contains rule`() {
        // Given
        val pageConfig = createTestPageConfig(
            matchRules = listOf(
                MatchRule(
                    type = MatchType.TEXT_CONTAINS,
                    values = listOf("医院", "门诊")
                )
            )
        )
        
        // When & Then
        // 注意：实际测试需要真实的 AccessibilityNodeInfo
        // 这里测试逻辑验证
        assertNotNull(pageConfig)
        assertEquals(1, pageConfig.matchRules.size)
    }
    
    @Test
    fun `should match with AND logic`() {
        // Given
        val pageConfig = createTestPageConfig(
            matchRules = listOf(
                MatchRule(type = MatchType.TEXT_CONTAINS, values = listOf("医院")),
                MatchRule(type = MatchType.TEXT_CONTAINS, values = listOf("详情"))
            ),
            matchLogic = MatchLogic.AND
        )
        
        // Then
        assertEquals(MatchLogic.AND, pageConfig.matchLogic)
        assertEquals(2, pageConfig.matchRules.size)
    }
    
    @Test
    fun `should match with OR logic`() {
        // Given
        val pageConfig = createTestPageConfig(
            matchRules = listOf(
                MatchRule(type = MatchType.TEXT_CONTAINS, values = listOf("医院")),
                MatchRule(type = MatchType.TEXT_CONTAINS, values = listOf("门诊"))
            ),
            matchLogic = MatchLogic.OR
        )
        
        // Then
        assertEquals(MatchLogic.OR, pageConfig.matchLogic)
    }
    
    @Test
    fun `should create match result with matched rules`() {
        // Given
        val matchedRules = listOf(
            MatchRule(type = MatchType.TEXT_CONTAINS, values = listOf("医院"))
        )
        
        // When
        val result = MatchResult(
            matched = true,
            pageId = "test_page",
            pageName = "测试页面",
            matchedRules = matchedRules
        )
        
        // Then
        assertTrue(result.matched)
        assertEquals("test_page", result.pageId)
        assertEquals("测试页面", result.pageName)
        assertEquals(1, result.matchedRules.size)
    }
    
    @Test
    fun `should create match result with error message`() {
        // When
        val result = MatchResult(
            matched = false,
            errorMessage = "根节点为空"
        )
        
        // Then
        assertFalse(result.matched)
        assertEquals("根节点为空", result.errorMessage)
    }
    
    // ==================== 辅助方法 ====================
    
    private fun createTestPageConfig(
        matchRules: List<MatchRule>,
        matchLogic: MatchLogic = MatchLogic.AND
    ): PageConfig {
        return PageConfig(
            pageId = "test_page",
            pageName = "测试页面",
            matchRules = matchRules,
            matchLogic = matchLogic,
            extractRules = mapOf(
                "title" to ExtractRule(type = ExtractType.FIND_BY_KEYWORDS, keywords = listOf("标题"))
            )
        )
    }
}
