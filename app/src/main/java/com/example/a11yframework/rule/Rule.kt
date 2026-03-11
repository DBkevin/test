package com.example.a11yframework.rule

import org.json.JSONObject

/**
 * 规则数据类
 * 
 * 表示一条完整的规则，包含元数据和页面配置
 * 
 * @property ruleId 规则唯一标识
 * @property ruleName 规则中文名称
 * @property appId 应用 ID
 * @property appPackage 应用包名
 * @property minAppVersion 最小支持的应用版本
 * @property maxAppVersion 最大支持的应用版本
 * @property priority 规则优先级
 * @property enabled 是否启用
 * @property version 规则版本号
 * @property pages 页面配置列表
 */
data class Rule(
    val ruleId: String,
    val ruleName: String,
    val appId: String,
    val appPackage: String,
    val minAppVersion: String? = null,
    val maxAppVersion: String? = null,
    val priority: Int = 100,
    val enabled: Boolean = true,
    val version: Int = 1,
    val pages: List<PageConfig>
) {
    companion object {
        /**
         * 从 JSON 字符串解析规则
         * 
         * @param json 规则 JSON 字符串
         * @return 解析后的规则对象
         * @throws RuleParseException 规则格式错误时抛出
         */
        fun fromJson(json: String): Rule {
            return RuleParser().parse(json)
        }
    }
    
    /**
     * 规则唯一标识（用于缓存 key）
     */
    fun cacheKey(): String {
        return "${appId}:${ruleId}:v${version}"
    }
}

/**
 * 页面配置
 * 
 * @property pageId 页面唯一标识
 * @property pageName 页面中文名称
 * @property matchRules 页面匹配规则列表
 * @property matchLogic 匹配逻辑（AND/OR）
 * @property extractRules 数据提取规则
 */
data class PageConfig(
    val pageId: String,
    val pageName: String,
    val matchRules: List<MatchRule>,
    val matchLogic: MatchLogic = MatchLogic.AND,
    val extractRules: Map<String, ExtractRule>
)

/**
 * 匹配逻辑枚举
 */
enum class MatchLogic {
    AND,
    OR
}

/**
 * 匹配规则
 * 
 * @property type 匹配类型
 * @property field 匹配字段
 * @property values 匹配值列表
 * @property pattern 匹配模式
 * @property logic 多值匹配逻辑
 */
data class MatchRule(
    val type: MatchType,
    val field: String = "page_text",
    val values: List<String>? = null,
    val pattern: String? = null,
    val logic: MatchLogic = MatchLogic.OR
)

/**
 * 匹配类型枚举
 */
enum class MatchType {
    TEXT_CONTAINS,
    TEXT_EQUALS,
    CLASS_NAME,
    VIEW_ID,
    REGEX
}

/**
 * 提取规则
 * 
 * @property type 提取类型
 * @property keywords 关键词列表
 * @property location 搜索位置
 * @property maxDepth 最大搜索深度
 * @property pattern 正则表达式
 * @property group 正则分组
 * @property container 列表容器配置
 * @property itemRules 列表项提取规则
 */
data class ExtractRule(
    val type: ExtractType,
    val keywords: List<String>? = null,
    val location: ExtractLocation = ExtractLocation.TOP,
    val maxDepth: Int = 3,
    val pattern: String? = null,
    val group: Int = 0,
    val container: ContainerConfig? = null,
    val itemRules: Map<String, ExtractRule>? = null
)

/**
 * 提取类型枚举
 */
enum class ExtractType {
    FIND_BY_KEYWORDS,
    REGEX,
    FIND_LIST,
    XPATH
}

/**
 * 搜索位置枚举
 */
enum class ExtractLocation {
    TOP,
    MIDDLE,
    BOTTOM
}

/**
 * 列表容器配置
 * 
 * @property className 容器类名
 * @property viewId 容器 viewId
 */
data class ContainerConfig(
    val className: String? = null,
    val viewId: String? = null
)

/**
 * 规则解析异常
 * 
 * @property message 错误消息
 * @property cause 原因异常
 */
class RuleParseException(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause)
