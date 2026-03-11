package com.example.a11yframework.rule

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 规则管理器
 * 
 * 功能:
 * 1. 规则加载（本地/网络）
 * 2. 规则缓存
 * 3. 规则版本管理
 * 4. 规则下发
 * 
 * @param context 应用上下文
 */
class RuleManager(private val context: Context) {
    
    companion object {
        private const val TAG = "RuleManager"
        private const val RULES_DIR = "rules"
        private const val RULE_INDEX_FILE = "rule_index.json"
    }
    
    private val parser = RuleParser()
    private val rulesDir = File(context.filesDir, RULES_DIR)
    private val indexFile = File(rulesDir, RULE_INDEX_FILE)
    
    // 内存缓存
    private val rulesCache = mutableMapOf<String, Rule>()
    
    // 规则索引
    private val ruleIndex = mutableMapOf<String, RuleIndexEntry>()
    
    init {
        // 确保目录存在
        if (!rulesDir.exists()) {
            rulesDir.mkdirs()
        }
        
        // 加载索引
        loadIndex()
    }
    
    /**
     * 获取规则
     * 
     * 优先级：内存缓存 > 本地文件 > 网络
     * 
     * @param ruleId 规则 ID
     * @return 规则对象，不存在则返回 null
     */
    fun getRule(ruleId: String): Rule? {
        // 1. 从内存缓存获取
        rulesCache[ruleId]?.let {
            Log.d(TAG, "从内存缓存获取规则：$ruleId")
            return it
        }
        
        // 2. 从本地文件加载
        val ruleFile = File(rulesDir, "$ruleId.json")
        if (ruleFile.exists()) {
            try {
                val json = ruleFile.readText()
                val rule = parser.parse(json)
                rulesCache[ruleId] = rule
                Log.d(TAG, "从本地文件加载规则：$ruleId")
                return rule
            } catch (e: Exception) {
                Log.e(TAG, "加载本地规则失败：$ruleId", e)
            }
        }
        
        Log.d(TAG, "规则不存在：$ruleId")
        return null
    }
    
    /**
     * 更新规则
     * 
     * @param ruleId 规则 ID
     * @param json 规则 JSON
     * @return 是否成功
     */
    fun updateRule(ruleId: String, json: String): Boolean {
        return try {
            // 1. 验证规则
            val rule = parser.parse(json)
            
            // 2. 保存到本地
            val ruleFile = File(rulesDir, "$ruleId.json")
            ruleFile.writeText(json)
            
            // 3. 更新缓存
            rulesCache[ruleId] = rule
            
            // 4. 更新索引
            ruleIndex[ruleId] = RuleIndexEntry(
                ruleId = ruleId,
                version = rule.version,
                updatedAt = System.currentTimeMillis()
            )
            saveIndex()
            
            Log.i(TAG, "规则更新成功：$ruleId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "规则更新失败：$ruleId", e)
            false
        }
    }
    
    /**
     * 删除规则
     * 
     * @param ruleId 规则 ID
     * @return 是否成功
     */
    fun deleteRule(ruleId: String): Boolean {
        return try {
            // 1. 删除文件
            val ruleFile = File(rulesDir, "$ruleId.json")
            if (ruleFile.exists()) {
                ruleFile.delete()
            }
            
            // 2. 清除缓存
            rulesCache.remove(ruleId)
            
            // 3. 删除索引
            ruleIndex.remove(ruleId)
            saveIndex()
            
            Log.i(TAG, "规则删除成功：$ruleId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "规则删除失败：$ruleId", e)
            false
        }
    }
    
    /**
     * 列出所有规则
     * 
     * @return 规则索引列表
     */
    fun listRules(): List<RuleIndexEntry> {
        return ruleIndex.values.toList()
    }
    
    /**
     * 清除缓存
     */
    fun clearCache() {
        rulesCache.clear()
        parser.clearCache()
        Log.i(TAG, "缓存已清除")
    }
    
    /**
     * 获取规则数量
     */
    fun getRuleCount(): Int {
        return ruleIndex.size
    }
    
    // ==================== 内部方法 ====================
    
    /**
     * 加载索引
     */
    private fun loadIndex() {
        if (!indexFile.exists()) {
            Log.d(TAG, "索引文件不存在，创建新索引")
            return
        }
        
        try {
            val json = indexFile.readText()
            val jsonObject = JSONObject(json)
            
            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val ruleId = keys.next()
                val entryJson = jsonObject.getJSONObject(ruleId)
                ruleIndex[ruleId] = RuleIndexEntry(
                    ruleId = entryJson.getString("rule_id"),
                    version = entryJson.getInt("version"),
                    updatedAt = entryJson.getLong("updated_at")
                )
            }
            
            Log.d(TAG, "索引加载成功：${ruleIndex.size} 个规则")
        } catch (e: Exception) {
            Log.e(TAG, "索引加载失败", e)
        }
    }
    
    /**
     * 保存索引
     */
    private fun saveIndex() {
        try {
            val jsonObject = JSONObject()
            
            for ((ruleId, entry) in ruleIndex.entries) {
                val entryJson = JSONObject()
                entryJson.put("rule_id", entry.ruleId)
                entryJson.put("version", entry.version)
                entryJson.put("updated_at", entry.updatedAt)
                jsonObject.put(ruleId, entryJson)
            }
            
            indexFile.writeText(jsonObject.toString(2))
            Log.d(TAG, "索引保存成功")
        } catch (e: Exception) {
            Log.e(TAG, "索引保存失败", e)
        }
    }
}

/**
 * 规则索引条目
 * 
 * @property ruleId 规则 ID
 * @property version 规则版本
 * @property updatedAt 更新时间戳
 */
data class RuleIndexEntry(
    val ruleId: String,
    val version: Int,
    val updatedAt: Long
)
