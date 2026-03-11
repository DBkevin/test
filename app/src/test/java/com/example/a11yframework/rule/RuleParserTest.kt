package com.example.a11yframework.rule

import org.junit.Test
import org.junit.Before
import org.junit.Assert.*
import org.json.JSONObject

/**
 * 规则解析器单元测试
 * 
 * 测试覆盖率目标：> 90%
 */
class RuleParserTest {
    
    private lateinit var parser: RuleParser
    
    @Before
    fun setUp() {
        parser = RuleParser(ParserConfig(enableCache = true))
    }
    
    @Test
    fun `should parse valid rule with all fields`() {
        // Given
        val json = """
        {
            "rule_id": "test_app_page_v1",
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
        val rule = parser.parse(json)
        
        // Then
        assertEquals("test_app_page_v1", rule.ruleId)
        assertEquals("测试规则", rule.ruleName)
        assertEquals("testapp", rule.appId)
        assertEquals("com.test.app", rule.appPackage)
        assertEquals("1.0.0", rule.minAppVersion)
        assertEquals("2.0.0", rule.maxAppVersion)
        assertEquals(200, rule.priority)
        assertTrue(rule.enabled)
        assertEquals(1, rule.version)
        assertEquals(1, rule.pages.size)
        assertEquals("test_page", rule.pages[0].pageId)
    }
    
    @Test
    fun `should parse rule with default values`() {
        // Given
        val json = """
        {
            "rule_id": "test_app_page_v1",
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
        val rule = parser.parse(json)
        
        // Then
        assertEquals(100, rule.priority)
        assertTrue(rule.enabled)
        assertEquals(1, rule.version)
    }
    
    @Test(expected = RuleParseException::class)
    fun `should throw exception when missing rule_id`() {
        // Given
        val json = """
        {
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
        parser.parse(json)
    }
    
    @Test(expected = RuleParseException::class)
    fun `should throw exception when missing app_package`() {
        // Given
        val json = """
        {
            "rule_id": "test_app_page_v1",
            "rule_name": "测试规则",
            "app_id": "testapp",
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
        parser.parse(json)
    }
    
    @Test(expected = RuleParseException::class)
    fun `should throw exception when pages is empty`() {
        // Given
        val json = """
        {
            "rule_id": "test_app_page_v1",
            "rule_name": "测试规则",
            "app_id": "testapp",
            "app_package": "com.test.app",
            "pages": []
        }
        """.trimIndent()
        
        // When
        parser.parse(json)
    }
    
    @Test(expected = RuleParseException::class)
    fun `should throw exception when match_rules is empty`() {
        // Given
        val json = """
        {
            "rule_id": "test_app_page_v1",
            "rule_name": "测试规则",
            "app_id": "testapp",
            "app_package": "com.test.app",
            "pages": [
                {
                    "page_id": "test_page",
                    "page_name": "测试页面",
                    "match_rules": [],
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
        parser.parse(json)
    }
    
    @Test(expected = RuleParseException::class)
    fun `should throw exception when extract_rules is empty`() {
        // Given
        val json = """
        {
            "rule_id": "test_app_page_v1",
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
                    "extract_rules": {}
                }
            ]
        }
        """.trimIndent()
        
        // When
        parser.parse(json)
    }
    
    @Test
    fun `should parse all match types`() {
        // Given
        val json = """
        {
            "rule_id": "test_app_page_v1",
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
                            "values": ["测试 1"]
                        },
                        {
                            "type": "text_equals",
                            "values": ["测试 2"]
                        },
                        {
                            "type": "class_name",
                            "pattern": "*Activity"
                        },
                        {
                            "type": "view_id",
                            "pattern": "com.test.app:id/*"
                        },
                        {
                            "type": "regex",
                            "pattern": ".*测试.*"
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
        val rule = parser.parse(json)
        
        // Then
        assertEquals(5, rule.pages[0].matchRules.size)
        assertEquals(MatchType.TEXT_CONTAINS, rule.pages[0].matchRules[0].type)
        assertEquals(MatchType.TEXT_EQUALS, rule.pages[0].matchRules[1].type)
        assertEquals(MatchType.CLASS_NAME, rule.pages[0].matchRules[2].type)
        assertEquals(MatchType.VIEW_ID, rule.pages[0].matchRules[3].type)
        assertEquals(MatchType.REGEX, rule.pages[0].matchRules[4].type)
    }
    
    @Test
    fun `should parse all extract types`() {
        // Given
        val json = """
        {
            "rule_id": "test_app_page_v1",
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
                        "keyword_field": {
                            "type": "find_by_keywords",
                            "keywords": ["关键词"],
                            "location": "top",
                            "max_depth": 5
                        },
                        "regex_field": {
                            "type": "regex",
                            "pattern": "\\d+",
                            "group": 1
                        },
                        "list_field": {
                            "type": "find_list",
                            "container": {
                                "class": "androidx.recyclerview.widget.RecyclerView"
                            },
                            "item_rules": {
                                "title": {
                                    "type": "find_by_keywords",
                                    "keywords": ["标题"]
                                },
                                "price": {
                                    "type": "regex",
                                    "pattern": "¥(\\d+)"
                                }
                            }
                        }
                    }
                }
            ]
        }
        """.trimIndent()
        
        // When
        val rule = parser.parse(json)
        
        // Then
        assertEquals(3, rule.pages[0].extractRules.size)
        
        val keywordRule = rule.pages[0].extractRules["keyword_field"]
        assertEquals(ExtractType.FIND_BY_KEYWORDS, keywordRule?.type)
        assertEquals(ExtractLocation.TOP, keywordRule?.location)
        assertEquals(5, keywordRule?.maxDepth)
        
        val regexRule = rule.pages[0].extractRules["regex_field"]
        assertEquals(ExtractType.REGEX, regexRule?.type)
        assertEquals("\\d+", regexRule?.pattern)
        assertEquals(1, regexRule?.group)
        
        val listRule = rule.pages[0].extractRules["list_field"]
        assertEquals(ExtractType.FIND_LIST, listRule?.type)
        assertNotNull(listRule?.container)
        assertEquals("androidx.recyclerview.widget.RecyclerView", listRule?.container?.className)
        assertEquals(2, listRule?.itemRules?.size)
    }
    
    @Test(expected = RuleParseException::class)
    fun `should throw exception when find_by_keywords missing keywords`() {
        // Given
        val json = """
        {
            "rule_id": "test_app_page_v1",
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
                            "type": "find_by_keywords"
                        }
                    }
                }
            ]
        }
        """.trimIndent()
        
        // When
        parser.parse(json)
    }
    
    @Test(expected = RuleParseException::class)
    fun `should throw exception when regex missing pattern`() {
        // Given
        val json = """
        {
            "rule_id": "test_app_page_v1",
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
                        "price": {
                            "type": "regex"
                        }
                    }
                }
            ]
        }
        """.trimIndent()
        
        // When
        parser.parse(json)
    }
    
    @Test(expected = RuleParseException::class)
    fun `should throw exception when find_list missing container`() {
        // Given
        val json = """
        {
            "rule_id": "test_app_page_v1",
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
                        "items": {
                            "type": "find_list",
                            "item_rules": {
                                "title": {
                                    "type": "find_by_keywords",
                                    "keywords": ["标题"]
                                }
                            }
                        }
                    }
                }
            ]
        }
        """.trimIndent()
        
        // When
        parser.parse(json)
    }
    
    @Test
    fun `should cache parsed rule`() {
        // Given
        val json = """
        {
            "rule_id": "test_app_page_v1",
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
        val rule = parser.parse(json)
        val cachedRule = parser.getFromCache(rule.cacheKey())
        
        // Then
        assertNotNull(cachedRule)
        assertEquals(rule.ruleId, cachedRule?.ruleId)
        assertEquals(1, parser.getCacheSize())
    }
    
    @Test
    fun `should clear cache`() {
        // Given
        val json = """
        {
            "rule_id": "test_app_page_v1",
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
        
        parser.parse(json)
        assertEquals(1, parser.getCacheSize())
        
        // When
        parser.clearCache()
        
        // Then
        assertEquals(0, parser.getCacheSize())
    }
    
    @Test
    fun `should parse match logic AND`() {
        // Given
        val json = """
        {
            "rule_id": "test_app_page_v1",
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
                            "values": ["测试 1", "测试 2"],
                            "logic": "AND"
                        }
                    ],
                    "match_logic": "AND",
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
        val rule = parser.parse(json)
        
        // Then
        assertEquals(MatchLogic.AND, rule.pages[0].matchLogic)
        assertEquals(MatchLogic.AND, rule.pages[0].matchRules[0].logic)
    }
    
    @Test
    fun `should parse match logic OR`() {
        // Given
        val json = """
        {
            "rule_id": "test_app_page_v1",
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
                            "values": ["测试 1", "测试 2"],
                            "logic": "OR"
                        }
                    ],
                    "match_logic": "OR",
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
        val rule = parser.parse(json)
        
        // Then
        assertEquals(MatchLogic.OR, rule.pages[0].matchLogic)
        assertEquals(MatchLogic.OR, rule.pages[0].matchRules[0].logic)
    }
}
