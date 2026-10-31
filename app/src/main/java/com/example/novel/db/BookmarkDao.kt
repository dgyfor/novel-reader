package com.example.novel.db

import android.content.ContentValues
import android.content.Context
import java.util.UUID

data class Bookmark(
    val id: String,
    val bookId: String,
    val charOffset: Int,
    val chapterName: String?,
    val createdAt: Long
)

class BookmarkDao(context: Context) {
    private val helper = DbHelper(context.applicationContext)
    private fun db() = helper.writableDatabase

    fun insert(bookId: String, charOffset: Int, chapterName: String? = null): Bookmark {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("id", id)
            put("book_id", bookId)
            put("char_offset", charOffset)
            put("chapter_name", chapterName)
            put("created_at", now)
        }
        db().insert(DbHelper.T_BOOKMARK, null, cv)
        return Bookmark(id, bookId, charOffset, chapterName, now)
    }

    fun getBookmarks(bookId: String): List<Bookmark> {
        val list = mutableListOf<Bookmark>()
        db().query(
            DbHelper.T_BOOKMARK, null,
            "book_id=?", arrayOf(bookId),
            null, null, "char_offset ASC"
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Bookmark(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        bookId = c.getString(c.getColumnIndexOrThrow("book_id")),
                        charOffset = c.getInt(c.getColumnIndexOrThrow("char_offset")),
                        chapterName = c.getString(c.getColumnIndexOrThrow("chapter_name")),
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                    )
                )
            }
        }
        return list
    }

    fun delete(id: String) {
        db().delete(DbHelper.T_BOOKMARK, "id=?", arrayOf(id))
    }

    fun deleteByBookId(bookId: String) {
        db().delete(DbHelper.T_BOOKMARK, "book_id=?", arrayOf(bookId))
    }
}