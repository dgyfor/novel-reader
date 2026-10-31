package com.example.novel

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.example.novel.db.Book
import com.example.novel.db.BookDao
import com.example.novel.db.JsonMigrator
import com.example.novel.db.SessionDao
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    // ==== 后台 DB + 主线程回投 ====
    private val dbExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var loadToken = 0L
    private var lastScrollY = 0

    private lateinit var dao: BookDao
    private lateinit var sessionDao: SessionDao
    private var currentType = "长篇"
    private val REQ_IMPORT_TXT = 1001

    private val colorBg = Color.parseColor("#F9F9F9")
    private val colorPrimary = Color.parseColor("#1A1A1A")
    private val colorAccent = Color.parseColor("#F0603A")
    private val colorTextSub = Color.parseColor("#999999")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        dao = BookDao(this)
        sessionDao = SessionDao(this)
        JsonMigrator.migrateIfNeeded(this)
        showBookshelf("长篇")
    }

    override fun onResume() {
        super.onResume()
        // 从阅读器回来：保留滚动位置
        showBookshelf(currentType, preserveScroll = true)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        dbExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundedBg(color: Int, radiusDp: Int, isRipple: Boolean = false, strokeColor: Int = 0): android.graphics.drawable.Drawable {
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != 0) setStroke(dp(1), strokeColor)
        }
        return if (isRipple) RippleDrawable(ColorStateList.valueOf(Color.parseColor("#15000000")), shape, null) else shape
    }

    // ===================== 调度层：只负责后台查数据 =====================

    private fun showBookshelf(type: String, preserveScroll: Boolean = false) {
        currentType = type
        if (!preserveScroll) lastScrollY = 0
        val isShort = type == "短篇"
        val token = ++loadToken

        dbExecutor.execute {
            val bookList = try { dao.getAllBooks(isShort) } catch (_: Exception) { emptyList<Book>() }
            val todayMs  = try { sessionDao.getTodayReadingTime() } catch (_: Exception) { 0L }
            val streak   = try { sessionDao.getStreakDays() } catch (_: Exception) { 0 }

            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (token != loadToken) return@post
                buildBookshelfUi(type, bookList, todayMs, streak, preserveScroll)
            }
        }
    }

    // ===================== UI 构建层：只渲染，不查库 =====================

    private fun buildBookshelfUi(
        type: String,
        bookList: List<Book>,
        todayMs: Long,
        streak: Int,
        preserveScroll: Boolean
    ) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBg)
            setPadding(dp(20), dp(60), dp(20), dp(24))
        }

        // 1. 顶部大标题
        root.addView(TextView(this).apply {
            text = "书架"
            textSize = 34f
            setTextColor(colorPrimary)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(24))
        })

        // 2. 毛玻璃数据卡片
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = roundedBg(Color.WHITE, 20)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { showChallengeDialog() }
        }

        val leftStat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        leftStat.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        leftStat.addView(TextView(this).apply {
            text = if (todayMs > 0) formatDuration(todayMs) else "0 分钟"
            textSize = 22f
            setTextColor(colorAccent)
            setTypeface(null, Typeface.BOLD)
        })
        leftStat.addView(TextView(this).apply {
            text = "今日阅读"
            textSize = 12f
            setTextColor(colorTextSub)
            setPadding(0, dp(4), 0, 0)
        })
        card.addView(leftStat)

        val rightStat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rightStat.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        rightStat.addView(TextView(this).apply {
            text = if (streak > 0) "$streak 天" else "0 天"
            textSize = 22f
            setTextColor(colorPrimary)
            setTypeface(null, Typeface.BOLD)
        })
        rightStat.addView(TextView(this).apply {
            text = "连续打卡"
            textSize = 12f
            setTextColor(colorTextSub)
            setPadding(0, dp(4), 0, 0)
        })
        card.addView(rightStat)

        val lpCard = LinearLayout.LayoutParams(-1, -2)
        lpCard.bottomMargin = dp(32)
        root.addView(card, lpCard)

        // 3. 分类 Tab
        val tabLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabLayout.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) }

        val tabLong = createTab("长篇", type == "长篇") { showBookshelf("长篇") }
        val tabShort = createTab("短篇", type == "短篇") { showBookshelf("短篇") }
        tabLayout.addView(tabLong, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(16) })
        tabLayout.addView(tabShort, LinearLayout.LayoutParams(-2, -2))
        root.addView(tabLayout)

        // 4. 书籍网格
        val scrollView = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        scrollView.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        val gridLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(gridLayout)
        root.addView(scrollView)

        // 记录滚动位置
        scrollView.setOnScrollChangeListener { _, _, y, _, _ -> lastScrollY = y }

        val colors = arrayOf(
            intArrayOf(Color.parseColor("#FF9A9E"), Color.parseColor("#FECFEF")),
            intArrayOf(Color.parseColor("#A18CD1"), Color.parseColor("#FBC2EB")),
            intArrayOf(Color.parseColor("#84FAB0"), Color.parseColor("#8FD3F4")),
            intArrayOf(Color.parseColor("#FCCB90"), Color.parseColor("#D57EEB"))
        )

        var rowLayout: LinearLayout? = null
        for ((index, book) in bookList.withIndex()) {
            if (index % 3 == 0) {
                rowLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                rowLayout.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) }
                gridLayout.addView(rowLayout)
            }

            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), 0, dp(6), 0)
            }
            item.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)

            val cPair = colors[Math.floorMod(book.id.hashCode(), colors.size)]

            val cover = TextView(this).apply {
                text = book.title.take(1)
                textSize = 28f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, cPair).apply {
                    cornerRadius = dp(12).toFloat()
                }
                elevation = dp(4).toFloat()

                setOnClickListener {
                    startActivity(
                        Intent(this@MainActivity, EditorActivity::class.java)
                            .putExtra("book_id", book.id)
                    )
                }

                setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("删除书籍")
                        .setMessage("确定要删除《${book.title}》吗？")
                        .setPositiveButton("删除") { _, _ ->
                            dbExecutor.execute {
                                try {
                                    dao.deleteBook(book.id)
                                    File(File(filesDir, "books"), "${book.id}.txt").delete()
                                } catch (_: Exception) {}
                                mainHandler.post {
                                    if (!isFinishing && !isDestroyed) {
                                        showBookshelf(type, preserveScroll = true)
                                    }
                                }
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    true
                }
            }
            cover.layoutParams = LinearLayout.LayoutParams(-1, dp(130))
            item.addView(cover)

            item.addView(TextView(this).apply {
                text = book.title
                textSize = 13f
                setTextColor(colorPrimary)
                setTypeface(null, Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(8), 0, 0)
            })

            rowLayout?.addView(item)
        }

        // 补齐最后一行空白
        if (bookList.isNotEmpty() && bookList.size % 3 != 0) {
            for (i in 0 until (3 - bookList.size % 3)) {
                rowLayout?.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
            }
        }

        // 5. 底部按钮
        val bottomLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, dp(16))
        }
        val btnAdd = TextView(this).apply {
            text = "＋ 新建"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            background = roundedBg(colorPrimary, 12, true)
            setOnClickListener { createNewBook(type) }
        }
        val btnImport = TextView(this).apply {
            text = "📥 导入"
            textSize = 15f
            setTextColor(colorPrimary)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            background = roundedBg(Color.WHITE, 12, true, colorPrimary)
            setOnClickListener { pickTxtFile() }
        }
        bottomLayout.addView(btnAdd, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        bottomLayout.addView(btnImport, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        root.addView(bottomLayout)

        setContentView(root)

        // 恢复滚动位置
        if (preserveScroll && lastScrollY > 0) {
            scrollView.post { scrollView.scrollTo(0, lastScrollY) }
        }
    }

    private fun createTab(text: String, isSelected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(if (isSelected) colorPrimary else colorTextSub)
            setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            setOnClickListener { onClick() }
        }
    }

    // 弹窗数据也放到后台查（避免主线程查 DB）
    private fun showChallengeDialog() {
        dbExecutor.execute {
            val todayMs = try { sessionDao.getTodayReadingTime() } catch (_: Exception) { 0L }
            val totalMs = try { sessionDao.getTotalReadingTime() } catch (_: Exception) { 0L }
            val streak  = try { sessionDao.getStreakDays() } catch (_: Exception) { 0 }

            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                val msg = "今日阅读：${formatDuration(todayMs)}\n总阅读时长：${formatDuration(totalMs)}\n连续打卡：${if (streak > 0) "$streak 天" else "暂无"}\n\n💡 继续保持！"
                AlertDialog.Builder(this)
                    .setTitle("阅读挑战")
                    .setMessage(msg)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "0 分钟"
        if (ms < 60_000L) return "不足 1 分钟"
        val totalMin = ms / 60_000L
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "$h 小时 $m 分钟" else "$m 分钟"
    }

    private fun pickTxtFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
        }
        startActivityForResult(intent, REQ_IMPORT_TXT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT_TXT && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri -> importTxt(uri) }
        }
    }

    // 把「读文件 + 写文件 + DAO」整段搬后台，UI 只留提示
    private fun importTxt(uri: Uri) {
        val typeForImport = currentType
        Toast.makeText(this, "正在导入…", Toast.LENGTH_SHORT).show()

        dbExecutor.execute {
            var errorMsg: String? = null
            var success = false
            try {
                val displayName = contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) c.getString(idx) else null
                    } else null
                } ?: "导入书籍_${System.currentTimeMillis()}"

                val title = displayName.removeSuffix(".txt").removeSuffix(".TXT").trim()
                val content = contentResolver.openInputStream(uri)?.use { input ->
                    val bos = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var n: Int
                    var total = 0L
                    while (input.read(buf).also { n = it } > 0) {
                        total += n
                        if (total > 20 * 1024 * 1024) throw RuntimeException("文件超过 20MB")
                        bos.write(buf, 0, n)
                    }
                    bos.toString("UTF-8")
                } ?: ""

                when {
                    content.isEmpty() -> errorMsg = "文件内容为空"
                    else -> {
                        val book = dao.addBook(title, typeForImport == "短篇")
                        if (book == null) {
                            errorMsg = "书名已存在"
                        } else {
                            val f = File(File(filesDir, "books"), "${book.id}.txt")
                            f.parentFile?.mkdirs()
                            f.writeText(content, Charsets.UTF_8)
                            dao.updateWordCount(book.id, content.count { !it.isWhitespace() })
                            dao.setHash(book.id, content.hashCode().toString(16))
                            success = true
                        }
                    }
                }
            } catch (e: Exception) {
                errorMsg = "导入失败: ${e.message}"
            }

            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (success) {
                    Toast.makeText(this, "导入成功", Toast.LENGTH_SHORT).show()
                    showBookshelf(typeForImport)
                } else {
                    Toast.makeText(this, errorMsg ?: "导入失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun createNewBook(type: String) {
        val input = EditText(this).apply {
            hint = "请输入书名"
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("新建$type")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val title = input.text.toString().trim()
                if (title.isEmpty()) return@setPositiveButton
                dbExecutor.execute {
                    val ok = try { dao.addBook(title, type == "短篇") != null } catch (_: Exception) { false }
                    mainHandler.post {
                        if (isFinishing || isDestroyed) return@post
                        if (ok) showBookshelf(type)
                        else Toast.makeText(this, "书名已存在", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}