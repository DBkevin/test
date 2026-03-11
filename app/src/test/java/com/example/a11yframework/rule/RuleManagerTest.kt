package com.example.a11yframework.rule

import org.junit.Test
import org.junit.Before
import org.junit.Assert.*
import android.content.Context
import java.io.File

/**
 * 规则管理器单元测试
 * 
 * 测试覆盖率目标：> 90%
 */
class RuleManagerTest {
    
    private lateinit var ruleManager: RuleManager
    private lateinit var mockContext: Context
    private lateinit var rulesDir: File
    
    @Before
    fun setUp() {
        // 创建测试用的临时目录
        val tempDir = File(System.getProperty("java.io.tmpdir"), "a11y_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        
        rulesDir = File(tempDir, "rules")
        rulesDir.mkdirs()
        
        // 创建 mock context（简化测试）
        mockContext = object : Context by createMockContext(tempDir) {
            override fun getFilesDir(): File = tempDir
        }
        
        ruleManager = RuleManager(mockContext)
    }
    
    @Test
    fun `should create rule index entry`() {
        // Given
        val entry = RuleIndexEntry(
            ruleId = "test_rule_v1",
            version = 1,
            updatedAt = System.currentTimeMillis()
        )
        
        // Then
        assertEquals("test_rule_v1", entry.ruleId)
        assertEquals(1, entry.version)
        assertTrue(entry.updatedAt > 0)
    }
    
    @Test
    fun `should create rule manager with empty cache`() {
        // Then
        assertEquals(0, ruleManager.getRuleCount())
    }
    
    @Test
    fun `should parse valid rule json`() {
        // Given
        val json = """
        {
            "rule_id": "test_rule_v1",
            "rule_name": "测试规则",
            "app_id": "testapp",
            "app_package": "com.test.app",
            "pages": [
                {
                    "page_id": "test_page",
                    "page_name": "测试页面",
                    "match_rules": [
                        {
                            "type": "text_contains",
                            "values": ["测试"]
                        }
                    ],
                    "extract_rules": {
                        "title": {
                            "type": "find_by_keywords",
                            "keywords": ["标题"]
                        }
                    }
                }
            ]
        }
        """.trimIndent()
        
        // When
        val rule = RuleParser().parse(json)
        
        // Then
        assertEquals("test_rule_v1", rule.ruleId)
        assertEquals(1, rule.pages.size)
        assertEquals("test_page", rule.pages[0].pageId)
    }
    
    @Test
    fun `should handle rule parse exception`() {
        // Given
        val invalidJson = """
        {
            "rule_id": "test_rule_v1"
        }
        """.trimIndent()
        
        // When & Then
        try {
            RuleParser().parse(invalidJson)
            fail("应该抛出 RuleParseException")
        } catch (e: RuleParseException) {
            assertTrue(e.message?.contains("缺少必填字段") == true)
        }
    }
    
    @Test
    fun `should cache parsed rule`() {
        // Given
        val json = """
        {
            "rule_id": "test_rule_v1",
            "rule_name": "测试规则",
            "app_id": "testapp",
            "app_package": "com.test.app",
            "pages": [
                {
                    "page_id": "test_page",
                    "page_name": "测试页面",
                    "match_rules": [
                        {
                            "type": "text_contains",
                            "values": ["测试"]
                        }
                    ],
                    "extract_rules": {
                        "title": {
                            "type": "find_by_keywords",
                            "keywords": ["标题"]
                        }
                    }
                }
            ]
        }
        """.trimIndent()
        
        val parser = RuleParser(ParserConfig(enableCache = true))
        
        // When
        val rule = parser.parse(json)
        val cachedRule = parser.getFromCache(rule.cacheKey())
        
        // Then
        assertNotNull(cachedRule)
        assertEquals(rule.ruleId, cachedRule?.ruleId)
    }
    
    @Test
    fun `should clear cache`() {
        // Given
        val json = """
        {
            "rule_id": "test_rule_v1",
            "rule_name": "测试规则",
            "app_id": "testapp",
            "app_package": "com.test.app",
            "pages": [
                {
                    "page_id": "test_page",
                    "page_name": "测试页面",
                    "match_rules": [
                        {
                            "type": "text_contains",
                            "values": ["测试"]
                        }
                    ],
                    "extract_rules": {
                        "title": {
                            "type": "find_by_keywords",
                            "keywords": ["标题"]
                        }
                    }
                }
            ]
        }
        """.trimIndent()
        
        val parser = RuleParser(ParserConfig(enableCache = true))
        parser.parse(json)
        assertEquals(1, parser.getCacheSize())
        
        // When
        parser.clearCache()
        
        // Then
        assertEquals(0, parser.getCacheSize())
    }
    
    @Test
    fun `should match rule types correctly`() {
        // Then
        assertEquals(MatchType.TEXT_CONTAINS, MatchType.TEXT_CONTAINS)
        assertEquals(MatchType.TEXT_EQUALS, MatchType.TEXT_EQUALS)
        assertEquals(MatchType.CLASS_NAME, MatchType.CLASS_NAME)
        assertEquals(MatchType.VIEW_ID, MatchType.VIEW_ID)
        assertEquals(MatchType.REGEX, MatchType.REGEX)
    }
    
    @Test
    fun `should match extract types correctly`() {
        // Then
        assertEquals(ExtractType.FIND_BY_KEYWORDS, ExtractType.FIND_BY_KEYWORDS)
        assertEquals(ExtractType.REGEX, ExtractType.REGEX)
        assertEquals(ExtractType.FIND_LIST, ExtractType.FIND_LIST)
        assertEquals(ExtractType.XPATH, ExtractType.XPATH)
    }
    
    @Test
    fun `should match location types correctly`() {
        // Then
        assertEquals(ExtractLocation.TOP, ExtractLocation.TOP)
        assertEquals(ExtractLocation.MIDDLE, ExtractLocation.MIDDLE)
        assertEquals(ExtractLocation.BOTTOM, ExtractLocation.BOTTOM)
    }
    
    @Test
    fun `should create rule with all optional fields`() {
        // Given
        val json = """
        {
            "rule_id": "test_rule_v1",
            "rule_name": "测试规则",
            "app_id": "testapp",
            "app_package": "com.test.app",
            "min_app_version": "1.0.0",
            "max_app_version": "2.0.0",
            "priority": 200,
            "enabled": true,
            "version": 1,
            "pages": [
                {
                    "page_id": "test_page",
                    "page_name": "测试页面",
                    "match_rules": [
                        {
                            "type": "text_contains",
                            "values": ["测试"]
                        }
                    ],
                    "extract_rules": {
                        "title": {
                            "type": "find_by_keywords",
                            "keywords": ["标题"]
                        }
                    }
                }
            ]
        }
        """.trimIndent()
        
        // When
        val rule = RuleParser().parse(json)
        
        // Then
        assertEquals("1.0.0", rule.minAppVersion)
        assertEquals("2.0.0", rule.maxAppVersion)
        assertEquals(200, rule.priority)
        assertTrue(rule.enabled)
        assertEquals(1, rule.version)
    }
    
    // ==================== 辅助方法 ====================
    
    private fun createMockContext(filesDir: File): Context {
        return object : Context by filesDir.applicationContext {
            override fun getFilesDir(): File = filesDir
        }
    }
}

/**
 * 辅助扩展：获取 File 的 ApplicationContext
 */
val File.applicationContext: Context
    get() {
        throw UnsupportedOperationException("Mock only")
    }
