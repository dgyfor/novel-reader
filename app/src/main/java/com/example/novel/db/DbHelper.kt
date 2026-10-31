package com.example.novel.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class DbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "novel.db"
        const val DB_VERSION = 2 // 升级到 v2，加入了书签表
        private const val TAG = "DbHelper"

        const val T_ANNOTATION = "annotation"
        const val T_SESSION = "reading_session"
        const val T_BOOK_META = "book_meta"
        const val T_BOOKMARK = "bookmark" // 新增：书签表
    }

    override fun onCreate(db: SQLiteDatabase) {
        // 1) 批注表
        db.execSQL(
            """
            CREATE TABLE $T_ANNOTATION (
                id            TEXT PRIMARY KEY,
                book_id       TEXT NOT NULL,
                chapter_id    TEXT NOT NULL DEFAULT 'main',
                start_offset  INTEGER NOT NULL,
                end_offset    INTEGER NOT NULL,
                color         TEXT NOT NULL DEFAULT 'yellow',
                style         TEXT NOT NULL DEFAULT 'highlight',
                note          TEXT,
                quoted_text   TEXT NOT NULL,
                orphan        INTEGER NOT NULL DEFAULT 0,
                created_at    INTEGER NOT NULL,
                updated_at    INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ann_book ON $T_ANNOTATION(book_id, start_offset)")

        // 2) 阅读时长表
        db.execSQL(
            """
            CREATE TABLE $T_SESSION (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id     TEXT NOT NULL,
                start_time  INTEGER NOT NULL,
                end_time    INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                date_key    TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sess_book ON $T_SESSION(book_id, date_key)")

        // 3) 书籍元信息表
        db.execSQL(
            """
            CREATE TABLE $T_BOOK_META (
                book_id       TEXT PRIMARY KEY,
                title         TEXT NOT NULL,
                is_short      INTEGER NOT NULL DEFAULT 0,
                content_hash  TEXT,
                word_count    INTEGER NOT NULL DEFAULT 0,
                updated_at    INTEGER NOT NULL,
                progress_offset INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        
        // 4) 书签表
        db.execSQL(
            """
            CREATE TABLE $T_BOOKMARK (
                id            TEXT PRIMARY KEY,
                book_id       TEXT NOT NULL,
                char_offset   INTEGER NOT NULL,
                chapter_name  TEXT,
                created_at    INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mark_book ON $T_BOOKMARK(book_id, char_offset)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 如果你已经装过 v1 版本，这里会自动给你补上缺少的表和列
        if (oldVersion < 2) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $T_BOOKMARK (
                    id            TEXT PRIMARY KEY,
                    book_id       TEXT NOT NULL,
                    char_offset   INTEGER NOT NULL,
                    chapter_name  TEXT,
                    created_at    INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_mark_book ON $T_BOOKMARK(book_id, char_offset)")
            
            // 给书元信息表加一个记录阅读进度的列
            db.execSQL("ALTER TABLE $T_BOOK_META ADD COLUMN progress_offset INTEGER NOT NULL DEFAULT 0")
        }
    }
}