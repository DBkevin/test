package com.example.a11yframework.rule

import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * 规则解析器
 * 
 * 功能:
 * 1. 加载规则 JSON
 * 2. 验证规则格式
 * 3. 缓存已加载规则
 * 
 * @param config 解析器配置
 */
class RuleParser(private val config: ParserConfig = ParserConfig()) {
    
    companion object {
        private const val TAG = "RuleParser"
    }
    
    /**
     * 规则缓存（线程安全）
     */
    private val cache = ConcurrentHashMap<String, Rule>()
    
    /**
     * 解析规则
     * 
     * @param json 规则 JSON 字符串
     * @return 解析后的规则对象
     * @throws RuleParseException 规则格式错误时抛出
     */
    fun parse(json: String): Rule {
        try {
            Log.d(TAG, "开始解析规则")
            
            val jsonObject = JSONObject(json)
            
            // 验证必填字段
            validateRequiredFields(jsonObject)
            
            // 解析元数据
            val rule = parseRuleMetadata(jsonObject)
            
            // 解析页面配置
            val pages = parsePages(jsonObject.getJSONArray("pages"))
            
            // 构建规则对象
            val result = rule.copy(pages = pages)
            
            // 缓存规则
            if (config.enableCache) {
                cache[result.cacheKey()] = result
                Log.d(TAG, "规则已缓存：${result.cacheKey()}")
            }
            
            Log.i(TAG, "规则解析成功：${result.ruleId}")
            return result
            
        } catch (e: RuleParseException) {
            Log.e(TAG, "规则解析失败：${e.message}", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "规则解析失败：${e.message}", e)
            throw RuleParseException("规则格式错误：${e.message}", e)
        }
    }
    
    /**
     * 从缓存加载规则
     * 
     * @param cacheKey 缓存 key
     * @return 规则对象，不存在则返回 null
     */
    fun getFromCache(cacheKey: String): Rule? {
        return cache[cacheKey]
    }
    
    /**
     * 清除缓存
     */
    fun clearCache() {
        cache.clear()
        Log.i(TAG, "规则缓存已清除")
    }
    
    /**
     * 获取缓存大小
     */
    fun getCacheSize(): Int {
        return cache.size
    }
    
    /**
     * 验证必填字段
     */
    private fun validateRequiredFields(jsonObject: JSONObject) {
        val requiredFields = listOf("rule_id", "rule_name", "app_id", "app_package", "pages")
        
        for (field in requiredFields) {
            if (!jsonObject.has(field)) {
                throw RuleParseException("缺少必填字段：$field")
            }
        }
        
        // 验证 pages 非空
        val pages = jsonObject.getJSONArray("pages")
        if (pages.length() == 0) {
            throw RuleParseException("pages 不能为空")
        }
    }
    
    /**
     * 解析规则元数据
     */
    private fun parseRuleMetadata(jsonObject: JSONObject): Rule {
        return Rule(
            ruleId = jsonObject.getString("rule_id"),
            ruleName = jsonObject.getString("rule_name"),
            appId = jsonObject.getString("app_id"),
            appPackage = jsonObject.getString("app_package"),
            minAppVersion = jsonObject.optString("min_app_version", null),
            maxAppVersion = jsonObject.optString("max_app_version", null),
            priority = jsonObject.optInt("priority", 100),
            enabled = jsonObject.optBoolean("enabled", true),
            version = jsonObject.optInt("version", 1),
            pages = emptyList() // 稍后填充
        )
    }
    
    /**
     * 解析页面配置列表
     */
    private fun parsePages(pagesArray: JSONArray): List<PageConfig> {
        val pages = mutableListOf<PageConfig>()
        
        for (i in 0 until pagesArray.length()) {
            val pageJson = pagesArray.getJSONObject(i)
            pages.add(parsePage(pageJson))
        }
        
        Log.d(TAG, "解析完成 ${pages.size} 个页面配置")
        return pages
    }
    
    /**
     * 解析单个页面配置
     */
    private fun parsePage(pageJson: JSONObject): PageConfig {
        // 验证必填字段
        val requiredFields = listOf("page_id", "page_name", "match_rules", "extract_rules")
        for (field in requiredFields) {
            if (!pageJson.has(field)) {
                throw RuleParseException("页面配置缺少必填字段：$field")
            }
        }
        
        val pageId = pageJson.getString("page_id")
        val pageName = pageJson.getString("page_name")
        val matchRules = parseMatchRules(pageJson.getJSONArray("match_rules"))
        val matchLogic = parseMatchLogic(pageJson.optString("match_logic", "AND"))
        val extractRules = parseExtractRules(pageJson.getJSONObject("extract_rules"))
        
        // 验证 page_id 唯一性（在规则内）
        // 注意：这里只能验证当前页面，完整验证需要在 RuleParser 外部进行
        
        return PageConfig(
            pageId = pageId,
            pageName = pageName,
            matchRules = matchRules,
            matchLogic = matchLogic,
            extractRules = extractRules
        )
    }
    
    /**
     * 解析匹配规则列表
     */
    private fun parseMatchRules(rulesArray: JSONArray): List<MatchRule> {
        val rules = mutableListOf<MatchRule>()
        
        if (rulesArray.length() == 0) {
            throw RuleParseException("match_rules 不能为空")
        }
        
        for (i in 0 until rulesArray.length()) {
            val ruleJson = rulesArray.getJSONObject(i)
            rules.add(parseMatchRule(ruleJson))
        }
        
        return rules
    }
    
    /**
     * 解析单个匹配规则
     */
    private fun parseMatchRule(ruleJson: JSONObject): MatchRule {
        if (!ruleJson.has("type")) {
            throw RuleParseException("匹配规则缺少必填字段：type")
        }
        
        val typeStr = ruleJson.getString("type")
        val type = parseMatchType(typeStr)
        val field = ruleJson.optString("field", "page_text")
        val logic = parseMatchLogic(ruleJson.optString("logic", "OR"))
        
        // 根据类型解析不同字段
        val values = ruleJson.optJSONArray("values")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }
        
        val pattern = ruleJson.optString("pattern", null)
        
        // 验证类型依赖字段
        when (type) {
            MatchType.TEXT_CONTAINS, MatchType.TEXT_EQUALS -> {
                if (values.isNullOrEmpty()) {
                    throw RuleParseException("匹配类型 $typeStr 需要 values 字段")
                }
            }
            MatchType.CLASS_NAME, MatchType.VIEW_ID, MatchType.REGEX -> {
                if (pattern.isNullOrEmpty()) {
                    throw RuleParseException("匹配类型 $typeStr 需要 pattern 字段")
                }
            }
        }
        
        return MatchRule(
            type = type,
            field = field,
            values = values,
            pattern = pattern,
            logic = logic
        )
    }
    
    /**
     * 解析匹配类型
     */
    private fun parseMatchType(typeStr: String): MatchType {
        return when (typeStr.lowercase()) {
            "text_contains" -> MatchType.TEXT_CONTAINS
            "text_equals" -> MatchType.TEXT_EQUALS
            "class_name" -> MatchType.CLASS_NAME
            "view_id" -> MatchType.VIEW_ID
            "regex" -> MatchType.REGEX
            else -> throw RuleParseException("未知的匹配类型：$typeStr")
        }
    }
    
    /**
     * 解析匹配逻辑
     */
    private fun parseMatchLogic(logicStr: String): MatchLogic {
        return when (logicStr.uppercase()) {
            "AND" -> MatchLogic.AND
            "OR" -> MatchLogic.OR
            else -> throw RuleParseException("未知的匹配逻辑：$logicStr")
        }
    }
    
    /**
     * 解析提取规则
     */
    private fun parseExtractRules(extractJson: JSONObject): Map<String, ExtractRule> {
        val rules = mutableMapOf<String, ExtractRule>()
        
        val keys = extractJson.keys()
        while (keys.hasNext()) {
            val fieldName = keys.next()
            val ruleJson = extractJson.getJSONObject(fieldName)
            rules[fieldName] = parseExtractRule(ruleJson)
        }
        
        if (rules.isEmpty()) {
            throw RuleParseException("extract_rules 不能为空")
        }
        
        return rules
    }
    
    /**
     * 解析单个提取规则
     */
    private fun parseExtractRule(ruleJson: JSONObject): ExtractRule {
        if (!ruleJson.has("type")) {
            throw RuleParseException("提取规则缺少必填字段：type")
        }
        
        val typeStr = ruleJson.getString("type")
        val type = parseExtractType(typeStr)
        
        val keywords = ruleJson.optJSONArray("keywords")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }
        
        val locationStr = ruleJson.optString("location", "top")
        val location = parseExtractLocation(locationStr)
        
        val maxDepth = ruleJson.optInt("max_depth", 3)
        val pattern = ruleJson.optString("pattern", null)
        val group = ruleJson.optInt("group", 0)
        
        val container = ruleJson.optJSONObject("container")?.let {
            ContainerConfig(
                className = it.optString("class", null),
                viewId = it.optString("viewId", null)
            )
        }
        
        val itemRules = ruleJson.optJSONObject("item_rules")?.let {
            parseExtractRules(it)
        }
        
        // 验证类型依赖字段
        when (type) {
            ExtractType.FIND_BY_KEYWORDS -> {
                if (keywords.isNullOrEmpty()) {
                    throw RuleParseException("提取类型 $typeStr 需要 keywords 字段")
                }
            }
            ExtractType.REGEX -> {
                if (pattern.isNullOrEmpty()) {
                    throw RuleParseException("提取类型 $typeStr 需要 pattern 字段")
                }
            }
            ExtractType.FIND_LIST -> {
                if (container == null) {
                    throw RuleParseException("提取类型 $typeStr 需要 container 字段")
                }
                if (itemRules.isNullOrEmpty()) {
                    throw RuleParseException("提取类型 $typeStr 需要 item_rules 字段")
                }
            }
            ExtractType.XPATH -> {
                // XPath 待实现
                throw RuleParseException("XPath 提取类型暂未实现")
            }
        }
        
        return ExtractRule(
            type = type,
            keywords = keywords,
            location = location,
            maxDepth = maxDepth,
            pattern = pattern,
            group = group,
            container = container,
            itemRules = itemRules
        )
    }
    
    /**
     * 解析提取类型
     */
    private fun parseExtractType(typeStr: String): ExtractType {
        return when (typeStr.lowercase()) {
            "find_by_keywords" -> ExtractType.FIND_BY_KEYWORDS
            "regex" -> ExtractType.REGEX
            "find_list" -> ExtractType.FIND_LIST
            "xpath" -> ExtractType.XPATH
            else -> throw RuleParseException("未知的提取类型：$typeStr")
        }
    }
    
    /**
     * 解析搜索位置
     */
    private fun parseExtractLocation(locationStr: String): ExtractLocation {
        return when (locationStr.uppercase()) {
            "TOP" -> ExtractLocation.TOP
            "MIDDLE" -> ExtractLocation.MIDDLE
            "BOTTOM" -> ExtractLocation.BOTTOM
            else -> throw RuleParseException("未知的搜索位置：$locationStr")
        }
    }
}

/**
 * 解析器配置
 * 
 * @property enableCache 是否启用缓存
 * @property cacheSize 缓存大小限制
 */
data class ParserConfig(
    val enableCache: Boolean = true,
    val cacheSize: Int = 100
)
