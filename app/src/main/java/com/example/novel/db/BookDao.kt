package com.example.novel.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

data class Book(
    val id: String,
    val title: String,
    val isShort: Boolean,
    val wordCount: Int,
    val updatedAt: Long,
    val contentHash: String?
)

class BookDao(context: Context) {
    private val helper = DbHelper(context.applicationContext)
    private fun db() = helper.writableDatabase

    fun addBook(title: String, isShort: Boolean): Book? {
        val t = title.trim()
        if (t.isEmpty()) return null
        val id = System.currentTimeMillis().toString()
        val now = System.currentTimeMillis()
        val ok = addBookWithId(id, t, isShort, 0, now, "")
        return if (ok) Book(id, t, isShort, 0, now, "") else null
    }

    fun addBookWithId(id: String, title: String, isShort: Boolean, wordCount: Int, updatedAt: Long, hash: String?): Boolean {
        if (id.isBlank() || title.isBlank()) return false
        val d = db()
        if (exists(d, id)) return false
        val cv = ContentValues().apply {
            put("book_id", id)
            put("title", title)
            put("is_short", if (isShort) 1 else 0)
            put("content_hash", hash ?: "")
            put("word_count", wordCount)
            put("updated_at", updatedAt)
        }
        return d.insert(DbHelper.T_BOOK_META, null, cv) != -1L
    }

    fun getAllBooks(isShort: Boolean): List<Book> {
        val list = mutableListOf<Book>()
        db().query(DbHelper.T_BOOK_META, null, "is_short=?", arrayOf(if (isShort) "1" else "0"), null, null, "updated_at DESC").use { c ->
            while (c.moveToNext()) list.add(readBook(c))
        }
        return list
    }

    fun getBook(id: String): Book? {
        db().query(DbHelper.T_BOOK_META, null, "book_id=?", arrayOf(id), null, null, null).use { c ->
            if (c.moveToFirst()) return readBook(c)
        }
        return null
    }

    fun deleteBook(id: String) {
        val d = db()
        d.delete(DbHelper.T_BOOK_META, "book_id=?", arrayOf(id))
        d.delete(DbHelper.T_ANNOTATION, "book_id=?", arrayOf(id))
        d.delete(DbHelper.T_SESSION, "book_id=?", arrayOf(id))
        d.delete(DbHelper.T_BOOKMARK, "book_id=?", arrayOf(id))
    }

    fun updateWordCount(id: String, wordCount: Int) {
        val cv = ContentValues().apply {
            put("word_count", wordCount)
            put("updated_at", System.currentTimeMillis())
        }
        db().update(DbHelper.T_BOOK_META, cv, "book_id=?", arrayOf(id))
    }

    fun setHash(id: String, hash: String) {
        val cv = ContentValues().apply {
            put("content_hash", hash)
            put("updated_at", System.currentTimeMillis())
        }
        db().update(DbHelper.T_BOOK_META, cv, "book_id=?", arrayOf(id))
    }

    // ============ 阅读进度 ============
    fun updateProgress(bookId: String, offset: Int) {
        val cv = ContentValues().apply {
            put("progress_offset", offset)
        }
        db().update(DbHelper.T_BOOK_META, cv, "book_id=?", arrayOf(bookId))
    }

    fun getProgress(bookId: String): Int {
        db().query(DbHelper.T_BOOK_META, arrayOf("progress_offset"), "book_id=?", arrayOf(bookId), null, null, null).use { c ->
            if (c.moveToFirst()) return c.getInt(0)
        }
        return 0
    }

    fun isEmpty(): Boolean {
        db().query(DbHelper.T_BOOK_META, arrayOf("book_id"), null, null, null, null, null).use { c -> return !c.moveToFirst() }
    }

    private fun exists(d: SQLiteDatabase, id: String): Boolean {
        d.query(DbHelper.T_BOOK_META, arrayOf("book_id"), "book_id=?", arrayOf(id), null, null, null).use { c -> return c.moveToFirst() }
    }

    private fun readBook(c: Cursor): Book = Book(
        id = c.getString(c.getColumnIndexOrThrow("book_id")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        isShort = c.getInt(c.getColumnIndexOrThrow("is_short")) == 1,
        wordCount = c.getInt(c.getColumnIndexOrThrow("word_count")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
        contentHash = c.getString(c.getColumnIndexOrThrow("content_hash"))
    )
}