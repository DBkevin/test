package com.example.a11yframework.rule.extractor

import org.junit.Test
import org.junit.Before
import org.junit.Assert.*
import android.accessibilityservice.AccessibilityService
import com.example.a11yframework.rule.ExtractRule
import com.example.a11yframework.rule.ExtractType
import com.example.a11yframework.rule.ExtractLocation
import com.example.a11yframework.rule.ContainerConfig

/**
 * 数据提取器单元测试
 * 
 * 测试覆盖率目标：> 90%
 */
class DataExtractorTest {
    
    private lateinit var extractor: DataExtractor
    private lateinit var mockService: AccessibilityService
    
    @Before
    fun setUp() {
        mockService = object : AccessibilityService() {
            override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
            override fun onInterrupt() {}
            override fun onServiceConnected() {}
        }
        extractor = DataExtractor(mockService)
    }
    
    @Test
    fun `should create extract result with data`() {
        // Given
        val data = mapOf(
            "hospital_name" to "测试医院",
            "price" to "99"
        )
        
        // When
        val result = ExtractResult(data, null)
        
        // Then
        assertEquals(2, result.data.size)
        assertEquals("测试医院", result.data["hospital_name"])
        assertEquals("99", result.data["price"])
        assertNull(result.errorMessage)
    }
    
    @Test
    fun `should create extract result with error`() {
        // When
        val result = ExtractResult(emptyMap(), "提取失败")
        
        // Then
        assertTrue(result.data.isEmpty())
        assertEquals("提取失败", result.errorMessage)
    }
    
    @Test
    fun `should parse find_by_keywords extract rule`() {
        // Given
        val rule = ExtractRule(
            type = ExtractType.FIND_BY_KEYWORDS,
            keywords = listOf("医院", "门诊"),
            location = ExtractLocation.TOP,
            maxDepth = 5
        )
        
        // Then
        assertEquals(ExtractType.FIND_BY_KEYWORDS, rule.type)
        assertEquals(2, rule.keywords?.size)
        assertEquals(ExtractLocation.TOP, rule.location)
        assertEquals(5, rule.maxDepth)
    }
    
    @Test
    fun `should parse regex extract rule`() {
        // Given
        val rule = ExtractRule(
            type = ExtractType.REGEX,
            pattern = "¥(\\d+)",
            group = 1
        )
        
        // Then
        assertEquals(ExtractType.REGEX, rule.type)
        assertEquals("¥(\\d+)", rule.pattern)
        assertEquals(1, rule.group)
    }
    
    @Test
    fun `should parse find_list extract rule`() {
        // Given
        val itemRules = mapOf(
            "title" to ExtractRule(type = ExtractType.FIND_BY_KEYWORDS, keywords = listOf("团购")),
            "price" to ExtractRule(type = ExtractType.REGEX, pattern = "¥(\\d+)")
        )
        
        val rule = ExtractRule(
            type = ExtractType.FIND_LIST,
            container = ContainerConfig(
                className = "androidx.recyclerview.widget.RecyclerView"
            ),
            itemRules = itemRules
        )
        
        // Then
        assertEquals(ExtractType.FIND_LIST, rule.type)
        assertNotNull(rule.container)
        assertEquals("androidx.recyclerview.widget.RecyclerView", rule.container?.className)
        assertEquals(2, rule.itemRules?.size)
    }
    
    @Test
    fun `should parse container config with viewId`() {
        // Given
        val container = ContainerConfig(
            viewId = "com.example:id/recyclerView"
        )
        
        // Then
        assertNull(container.className)
        assertEquals("com.example:id/recyclerView", container.viewId)
    }
    
    @Test
    fun `should handle empty extract rules`() {
        // When
        val result = ExtractResult(emptyMap(), null)
        
        // Then
        assertTrue(result.data.isEmpty())
        assertNull(result.errorMessage)
    }
    
    @Test
    fun `should handle extract rule with default values`() {
        // Given
        val rule = ExtractRule(
            type = ExtractType.FIND_BY_KEYWORDS,
            keywords = listOf("测试")
        )
        
        // Then
        assertEquals(ExtractLocation.TOP, rule.location) // 默认值
        assertEquals(3, rule.maxDepth) // 默认值
        assertEquals(0, rule.group) // 默认值
    }
}
