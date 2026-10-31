package com.example.novel.db

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.File

object JsonMigrator {
    private const val TAG = "JsonMigrator"
    private val CANDIDATES = listOf("novel_data.json", "books.json")

    fun migrateIfNeeded(context: Context) {
        val dir = context.filesDir
        val oldFile = CANDIDATES.map { File(dir, it) }.firstOrNull { it.exists() }
        if (oldFile == null) return

        val dao = BookDao(context)
        if (!dao.isEmpty()) return

        try {
            val text = oldFile.readText(Charsets.UTF_8).trim()
            if (text.isEmpty()) return
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                val title = o.optString("title").trim()
                if (id.isEmpty() || title.isEmpty()) continue
                val isShort = (o.optString("type").trim() == "短篇")
                val content = o.optString("content")
                val wordCount = content.replace("\\s".toRegex(), "").length
                val hash = content.hashCode().toString(16)
                val updatedAt = id.toLongOrNull() ?: System.currentTimeMillis()
                dao.addBookWithId(id, title, isShort, wordCount, updatedAt, hash)
            }
        } catch (e: Exception) {
            Log.e(TAG, "迁移失败", e)
        }
    }
}