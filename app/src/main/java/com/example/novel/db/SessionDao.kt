package com.example.novel.db

import android.content.ContentValues
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SessionDao(context: Context) {
    private val helper = DbHelper(context.applicationContext)
    private fun db() = helper.writableDatabase
    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun insert(bookId: String, startTime: Long, endTime: Long, durationMs: Long) {
        val cv = ContentValues().apply {
            put("book_id", bookId)
            put("start_time", startTime)
            put("end_time", endTime)
            put("duration_ms", durationMs)
            put("date_key", sdf.format(Date(startTime)))
        }
        db().insert(DbHelper.T_SESSION, null, cv)
    }

    fun getTotalReadingTime(): Long {
        db().rawQuery("SELECT SUM(duration_ms) FROM ${DbHelper.T_SESSION}", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return 0L
    }

    fun getTodayReadingTime(): Long = getDayReadingTime(sdf.format(Date()))

    fun getDayReadingTime(dateKey: String): Long {
        db().rawQuery(
            "SELECT SUM(duration_ms) FROM ${DbHelper.T_SESSION} WHERE date_key=?",
            arrayOf(dateKey)
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return 0L
    }

    fun getWeekReadingTime(): Long {
        val cal = Calendar.getInstance()
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val mondayKey = sdf.format(cal.time)
        db().rawQuery(
            "SELECT SUM(duration_ms) FROM ${DbHelper.T_SESSION} WHERE date_key >= ?",
            arrayOf(mondayKey)
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return 0L
    }

    fun getBookReadingTime(bookId: String): Long {
        db().rawQuery(
            "SELECT SUM(duration_ms) FROM ${DbHelper.T_SESSION} WHERE book_id=?",
            arrayOf(bookId)
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return 0L
    }

    fun getStreakDays(): Int {
        var streak = 0
        val cal = Calendar.getInstance()
        while (true) {
            val key = sdf.format(cal.time)
            if (getDayReadingTime(key) <= 0L) break
            streak++
            cal.add(Calendar.DAY_OF_MONTH, -1)
            if (streak > 3650) break
        }
        return streak
    }
}