package com.example.a11yframework.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.example.a11yframework.core.ScrapedData
import com.example.a11yframework.core.ScrapedRecordIdentity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.LinkedHashMap

/**
 * 数据存储
 * 
 * 使用 SQLite 存储抓取的数据
 * 支持导出为 JSON
 */
class DataStore(context: Context) {
    
    companion object {
        private const val TAG = "DataStore"
        private const val DB_NAME = "a11y_scraped_data.db"
        private const val DB_VERSION = 1
        private const val RECENT_RECORD_TTL_MS = 10 * 60 * 1000L
        private const val RECENT_RECORD_CACHE_LIMIT = 4000
        
        private const val TABLE_NAME = "scraped_data"
        
        // 表结构字段
        private const val COL_ID = "id"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_PLUGIN_ID = "plugin_id"
        private const val COL_PAGE_TYPE = "page_type"
        private const val COL_DATA_TYPE = "data_type"
        private const val COL_CONTENT = "content"  // JSON 字符串
        private const val COL_RAW_TEXT = "raw_text"
        private const val COL_METADATA = "metadata"  // JSON 字符串
        private const val COL_CREATED_AT = "created_at"
    }
    
    private val dbHelper: DbHelper
    private val gson = Gson()
    private val recentRecordKeys = LinkedHashMap<String, Long>()
    
    init {
        dbHelper = DbHelper(context)
    }
    
    /**
     * 保存数据
     */
    fun saveData(dataList: List<ScrapedData>) {
        if (dataList.isEmpty()) return

        val now = System.currentTimeMillis()
        val freshCandidates = synchronized(recentRecordKeys) {
            selectFreshCandidates(dataList, now)
        }

        if (freshCandidates.isEmpty()) {
            Log.d(TAG, "Skipped ${dataList.size} duplicate records")
            return
        }

        val db = dbHelper.writableDatabase
        var committed = false
        
        try {
            db.beginTransaction()
            
            freshCandidates.forEach { candidate ->
                val data = candidate.data
                val values = ContentValues().apply {
                    put(COL_TIMESTAMP, data.timestamp)
                    put(COL_PLUGIN_ID, data.pluginId)
                    put(COL_PAGE_TYPE, data.pageType)
                    put(COL_DATA_TYPE, data.dataType)
                    put(COL_CONTENT, gson.toJson(data.content))
                    put(COL_RAW_TEXT, data.rawText)
                    put(COL_METADATA, gson.toJson(data.metadata))
                    put(COL_CREATED_AT, System.currentTimeMillis())
                }
                
                db.insert(TABLE_NAME, null, values)
            }
            
            db.setTransactionSuccessful()
            committed = true
            Log.i(TAG, "Saved ${freshCandidates.size} records")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving data", e)
        } finally {
            db.endTransaction()
            if (committed) {
                synchronized(recentRecordKeys) {
                    rememberRecentKeys(freshCandidates, now)
                }
            }
        }
    }
    
    /**
     * 查询数据（按插件 ID 过滤）
     */
    fun queryByPlugin(pluginId: String, limit: Int = 100): List<ScrapedData> {
        val db = dbHelper.readableDatabase
        val results = mutableListOf<ScrapedData>()
        
        val cursor = db.query(
            TABLE_NAME,
            null,
            "$COL_PLUGIN_ID = ?",
            arrayOf(pluginId),
            null,
            null,
            "$COL_TIMESTAMP DESC",
            limit.toString()
        )
        
        cursor.use {
            while (it.moveToNext()) {
                results.add(cursorToData(it))
            }
        }
        
        return results
    }
    
    /**
     * 查询数据（按时间范围）
     */
    fun queryByTimeRange(startTime: Long, endTime: Long, limit: Int = 100): List<ScrapedData> {
        val db = dbHelper.readableDatabase
        val results = mutableListOf<ScrapedData>()
        
        val cursor = db.query(
            TABLE_NAME,
            null,
            "$COL_TIMESTAMP BETWEEN ? AND ?",
            arrayOf(startTime.toString(), endTime.toString()),
            null,
            null,
            "$COL_TIMESTAMP DESC",
            limit.toString()
        )
        
        cursor.use {
            while (it.moveToNext()) {
                results.add(cursorToData(it))
            }
        }
        
        return results
    }
    
    /**
     * 获取所有数据
     */
    fun getAllData(limit: Int = 1000): List<ScrapedData> {
        val db = dbHelper.readableDatabase
        val results = mutableListOf<ScrapedData>()
        
        val cursor = db.query(
            TABLE_NAME,
            null,
            null,
            null,
            null,
            null,
            "$COL_TIMESTAMP DESC",
            limit.toString()
        )
        
        cursor.use {
            while (it.moveToNext()) {
                results.add(cursorToData(it))
            }
        }
        
        return results
    }
    
    /**
     * 删除旧数据（保留最近 N 天）
     */
    fun deleteOldData(daysToKeep: Int): Int {
        val db = dbHelper.writableDatabase
        val cutoffTime = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)
        
        return db.delete(
            TABLE_NAME,
            "$COL_TIMESTAMP < ?",
            arrayOf(cutoffTime.toString())
        )
    }
    
    /**
     * 导出数据为 JSON
     */
    fun exportToJson(pluginId: String? = null): String {
        val data = if (pluginId != null) {
            queryByPlugin(pluginId, limit = 10000)
        } else {
            getAllData(limit = 10000)
        }
        
        return gson.toJson(data)
    }
    
    /**
     * 获取数据统计
     */
    fun getStats(): Map<String, Any> {
        val db = dbHelper.readableDatabase
        val stats = mutableMapOf<String, Any>()
        
        // 总记录数
        val countCursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_NAME", null)
        var totalCount = 0
        if (countCursor.moveToFirst()) {
            totalCount = countCursor.getInt(0)
        }
        countCursor.close()
        
        // 按插件统计
        val pluginCursor = db.rawQuery(
            "SELECT $COL_PLUGIN_ID, COUNT(*) as count FROM $TABLE_NAME GROUP BY $COL_PLUGIN_ID",
            null
        )
        val pluginStats = mutableMapOf<String, Int>()
        while (pluginCursor.moveToNext()) {
            pluginStats[pluginCursor.getString(0)] = pluginCursor.getInt(1)
        }
        pluginCursor.close()
        
        stats["total"] = totalCount
        stats["byPlugin"] = pluginStats
        
        return stats
    }
    
    private fun cursorToData(cursor: android.database.Cursor): ScrapedData {
        val contentJson = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTENT))
        val metadataJson = cursor.getString(cursor.getColumnIndexOrThrow(COL_METADATA))
        
        val content = try {
            gson.fromJson(contentJson, object : TypeToken<Map<String, String>>() {}.type)
        } catch (e: Exception) {
            emptyMap<String, String>()
        }
        
        val metadata = try {
            gson.fromJson(metadataJson, object : TypeToken<Map<String, Any>>() {}.type)
        } catch (e: Exception) {
            emptyMap<String, Any>()
        }
        
        return ScrapedData(
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)),
            pluginId = cursor.getString(cursor.getColumnIndexOrThrow(COL_PLUGIN_ID)),
            pageType = cursor.getString(cursor.getColumnIndexOrThrow(COL_PAGE_TYPE)),
            dataType = cursor.getString(cursor.getColumnIndexOrThrow(COL_DATA_TYPE)),
            content = content,
            rawText = cursor.getString(cursor.getColumnIndexOrThrow(COL_RAW_TEXT)) ?: "",
            metadata = metadata
        )
    }

    private fun selectFreshCandidates(
        dataList: List<ScrapedData>,
        now: Long
    ): List<RecordInsertCandidate> {
        pruneRecentRecordKeys(now)

        val freshCandidates = mutableListOf<RecordInsertCandidate>()
        val seenInBatch = mutableSetOf<String>()

        dataList.forEach { data ->
            val key = buildRecordKey(data)
            if (!seenInBatch.add(key)) {
                return@forEach
            }
            if (recentRecordKeys.containsKey(key)) {
                return@forEach
            }

            freshCandidates.add(RecordInsertCandidate(key, data))
        }

        return freshCandidates
    }

    private fun rememberRecentKeys(
        candidates: List<RecordInsertCandidate>,
        now: Long
    ) {
        candidates.forEach { candidate ->
            recentRecordKeys[candidate.key] = now
        }

        trimRecentRecordKeys()
    }

    private fun pruneRecentRecordKeys(now: Long) {
        val iterator = recentRecordKeys.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > RECENT_RECORD_TTL_MS) {
                iterator.remove()
            }
        }
    }

    private fun trimRecentRecordKeys() {
        while (recentRecordKeys.size > RECENT_RECORD_CACHE_LIMIT) {
            val oldestKey = recentRecordKeys.entries.firstOrNull()?.key ?: break
            recentRecordKeys.remove(oldestKey)
        }
    }

    private fun buildRecordKey(data: ScrapedData): String {
        return ScrapedRecordIdentity.buildBusinessKey(data)
    }
    
    /**
     * 数据库帮助类
     */
    private class DbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        
        override fun onCreate(db: SQLiteDatabase) {
            val createTable = """
                CREATE TABLE $TABLE_NAME (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_TIMESTAMP INTEGER NOT NULL,
                    $COL_PLUGIN_ID TEXT NOT NULL,
                    $COL_PAGE_TYPE TEXT NOT NULL,
                    $COL_DATA_TYPE TEXT NOT NULL,
                    $COL_CONTENT TEXT,
                    $COL_RAW_TEXT TEXT,
                    $COL_METADATA TEXT,
                    $COL_CREATED_AT INTEGER NOT NULL
                )
            """.trimIndent()
            
            db.execSQL(createTable)
            
            // 创建索引
            db.execSQL("CREATE INDEX idx_plugin ON $TABLE_NAME($COL_PLUGIN_ID)")
            db.execSQL("CREATE INDEX idx_timestamp ON $TABLE_NAME($COL_TIMESTAMP)")
            
            Log.i(TAG, "Database created")
        }
        
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            Log.w(TAG, "Upgrading database from $oldVersion to $newVersion")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
            onCreate(db)
        }
    }
}

private data class RecordInsertCandidate(
    val key: String,
    val data: ScrapedData
)
