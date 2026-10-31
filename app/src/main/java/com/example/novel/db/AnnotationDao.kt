package com.example.novel.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import java.util.UUID

data class Annotation(
    val id: String,
    val bookId: String,
    val startOffset: Int,
    val endOffset: Int,
    val quotedText: String,
    val note: String? = null,
    val color: String = "yellow",
    val createdAt: Long
)

class AnnotationDao(context: Context) {
    private val helper = DbHelper(context.applicationContext)
    private fun db() = helper.writableDatabase

    fun insert(bookId: String, start: Int, end: Int, quotedText: String, note: String? = null, color: String = "yellow"): Annotation {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("id", id)
            put("book_id", bookId)
            put("chapter_id", "main")
            put("start_offset", start)
            put("end_offset", end)
            put("color", color)
            put("style", "highlight")
            put("note", note)
            put("quoted_text", quotedText)
            put("orphan", 0)
            put("created_at", now)
            put("updated_at", now)
        }
        db().insert(DbHelper.T_ANNOTATION, null, cv)
        return Annotation(id, bookId, start, end, quotedText, note, color, now)
    }

    fun getAnnotations(bookId: String): List<Annotation> {
        val list = mutableListOf<Annotation>()
        db().query(
            DbHelper.T_ANNOTATION, null,
            "book_id=? AND orphan=0", arrayOf(bookId),
            null, null, "start_offset ASC"
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Annotation(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        bookId = c.getString(c.getColumnIndexOrThrow("book_id")),
                        startOffset = c.getInt(c.getColumnIndexOrThrow("start_offset")),
                        endOffset = c.getInt(c.getColumnIndexOrThrow("end_offset")),
                        quotedText = c.getString(c.getColumnIndexOrThrow("quoted_text")),
                        note = c.getString(c.getColumnIndexOrThrow("note")),
                        color = c.getString(c.getColumnIndexOrThrow("color")),
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                    )
                )
            }
        }
        return list
    }
    
    // === 修改过的清空方法 ===
    fun deleteAllByBookId(bookId: String) {
        db().delete(DbHelper.T_ANNOTATION, "book_id=?", arrayOf(bookId))
    }
    
    fun delete(id: String) {
        db().delete(DbHelper.T_ANNOTATION, "id=?", arrayOf(id))
    }
}