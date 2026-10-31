package com.example.novel

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import com.example.novel.db.AnnotationDao
import com.example.novel.db.Book
import com.example.novel.db.BookDao
import com.example.novel.db.BookmarkDao
import com.example.novel.db.SessionDao
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class EditorActivity : Activity(), NovelBridge.Host {

    private lateinit var dao: BookDao
    private lateinit var annDao: AnnotationDao
    private lateinit var bookmarkDao: BookmarkDao
    private lateinit var sessionDao: SessionDao
    private var book: Book? = null

    private var webView: WebView? = null
    private var bridge: NovelBridge? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private lateinit var prefs: android.content.SharedPreferences
    private var fontSize = 20f
    private var fontFamily = "serif"
    private var bgTheme = "paper"
    private var lineSpacing = 1.6f
    private var pageTurnMode = "tap"

    private var sessionStartTime = 0L
    private var sessionBookId: String? = null
    private var lastProgressOffset = 0
    private var exportContent = ""

    // 拦截系统原生文本选择菜单（复制/全选/粘贴），改为使用 H5 的自定义气泡
    private val NoActionModeCallback = object : android.view.ActionMode.Callback {
        override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean {
            return false // 返回 false 表示不创建系统菜单
        }
        override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean {
            return false
        }
        override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?): Boolean {
            return false
        }
        override fun onDestroyActionMode(mode: android.view.ActionMode?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)

        dao = BookDao(this)
        annDao = AnnotationDao(this)
        bookmarkDao = BookmarkDao(this)
        sessionDao = SessionDao(this)
        prefs = getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
        loadPreferences()

        val bookId = intent.getStringExtra("book_id") ?: run { finish(); return }
        book = dao.getBook(bookId) ?: run { finish(); return }

        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // ================== 完美手势拦截 WebView ==================
        val wv = object : WebView(this@EditorActivity) {
            private var downTime = 0L
            private var downX = 0f
            private var downY = 0f
            private var isLongPress = false
            private var isScrolling = false
            private var realDownEvent: MotionEvent? = null          // ← 新增
            private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

            private val longPressRunnable = Runnable {
                isLongPress = true
                // 转发真实 DOWN（保留原生 buttonState / deviceId / 真实 downTime）
                val down = realDownEvent
                if (down != null) {
                    try { super.onTouchEvent(down) } catch (_: Exception) {}
                    down.recycle()
                    realDownEvent = null
                }
                // 补一个 MOVE，让 Chromium 进入"手指仍在按压"态，选字更稳
                val moveEvent = MotionEvent.obtain(
                    downTime, System.currentTimeMillis(),
                    MotionEvent.ACTION_MOVE, downX, downY, 0
                )
                try { super.onTouchEvent(moveEvent) } catch (_: Exception) {}
                moveEvent.recycle()
            }

            // === 禁用系统原生菜单，放这里 ===
            override fun startActionMode(callback: android.view.ActionMode.Callback?): android.view.ActionMode? {
                return super.startActionMode(NoActionModeCallback)
            }

            override fun startActionMode(callback: android.view.ActionMode.Callback?, type: Int): android.view.ActionMode? {
                return super.startActionMode(NoActionModeCallback, type)
            }
            // ===============================

            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        realDownEvent?.recycle()
                        realDownEvent = MotionEvent.obtain(event)      // ← 备份真实 DOWN
                        downTime = System.currentTimeMillis()
                        downX = event.x
                        downY = event.y
                        isLongPress = false
                        isScrolling = false
                        postDelayed(longPressRunnable, 500L)           // ← 用常量 500，不依赖 ROM 的 LongPressTimeout
                        return true // 强行拦截，不给 WebView
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isLongPress || isScrolling) {
                            return super.onTouchEvent(event)
                        }
                        val dx = Math.abs(event.x - downX)
                        val dy = Math.abs(event.y - downY)
                        if (dx > touchSlop || dy > touchSlop) {
                            isScrolling = true
                            removeCallbacks(longPressRunnable)
                            realDownEvent?.let {
                                try { super.onTouchEvent(it) } catch (_: Exception) {}
                                it.recycle()
                                realDownEvent = null
                            }
                            return super.onTouchEvent(event)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        removeCallbacks(longPressRunnable)
                        realDownEvent?.recycle()
                        realDownEvent = null
                        if (isLongPress || isScrolling) {
                            return super.onTouchEvent(event)
                        }
                        if (System.currentTimeMillis() - downTime < 500L) {
                            val x = event.x
                            val y = event.y
                            val w = width
                            val h = height

                            post {
                                // 传给 JS 四个参数：x, y, 宽, 高
                                val js = "if(window.handleNativeTap){ window.handleNativeTap($x, $y, $w, $h); }"
                                try { evaluateJavascript(js, null) } catch (_: Exception) {}
                            }
                            return true
                        }
                        return super.onTouchEvent(event)
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        removeCallbacks(longPressRunnable)
                        realDownEvent?.recycle()
                        realDownEvent = null
                        if (isLongPress || isScrolling) {
                            return super.onTouchEvent(event)
                        }
                        return true
                    }
                }
                return super.onTouchEvent(event)
            }
        }
        // =========================================================

        webView = wv
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#F6EEDC"))
        }
        container.addView(wv, ViewGroup.LayoutParams(-1, -1))
        setContentView(container)

        val settings: WebSettings = wv.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadWithOverviewMode = false
        settings.useWideViewPort = false
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.textZoom = 100

        val newBridge = NovelBridge(this)
        bridge = newBridge
        wv.addJavascriptInterface(newBridge, "NovelBridge")

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                msg?.let {
                    android.util.Log.e("WebViewConsole", "网页日志: ${it.message()} (行号: ${it.lineNumber()})")
                }
                return true
            }
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pushBookToJs()
            }
        }

        wv.loadUrl("file:///android_asset/reader.html")
    }

    private fun pushBookToJs() {
        val b = book ?: return
        ioExecutor.execute {
            try {
                val content = loadContent(b)
                val anns = annDao.getAnnotations(b.id)
                val bookmarks = bookmarkDao.getBookmarks(b.id)
                val progressOffset = dao.getProgress(b.id)

                val annArr = JSONArray().apply {
                    for (a in anns) {
                        put(JSONObject().apply {
                            put("id", a.id)
                            put("startOffset", a.startOffset)
                            put("endOffset", a.endOffset)
                            put("quotedText", a.quotedText)
                            put("note", a.note ?: "")
                            put("color", a.color)
                        })
                    }
                }
                val bmArr = JSONArray().apply {
                    for (bm in bookmarks) {
                        put(JSONObject().apply {
                            put("id", bm.id)
                            put("charOffset", bm.charOffset)
                            put("chapterName", bm.chapterName ?: "")
                            put("createdAt", bm.createdAt)
                        })
                    }
                }
                val settingsJson = JSONObject().apply {
                    put("fontSize", fontSize.toDouble())
                    put("fontFamily", fontFamily)
                    put("bgTheme", bgTheme)
                    put("lineSpacing", lineSpacing.toDouble())
                    put("pageTurnMode", pageTurnMode)
                    put("night", bgTheme == "dark")
                }
                val payload = JSONObject().apply {
                    put("bookId", b.id)
                    put("title", b.title)
                    put("content", content)
                    put("annotations", annArr)
                    put("bookmarks", bmArr)
                    put("progressOffset", progressOffset)
                    put("settings", settingsJson)
                }
                val js = "if(window.loadBook){window.loadBook(${JSONObject.quote(payload.toString())});}"

                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    try { webView?.evaluateJavascript(js, null) } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    override fun onProgress(charOffset: Int, percent: Float) {
        lastProgressOffset = charOffset
        val b = book ?: return
        if (ioExecutor.isShutdown) return
        try {
            ioExecutor.execute { try { dao.updateProgress(b.id, charOffset) } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }

    override fun onAddAnnotation(
        start: Int, end: Int, quotedText: String, note: String?, color: String, blockIdx: Int
    ) {
        val b = book ?: return
        val safeColor = if (color == "green") "green" else "yellow"
        if (ioExecutor.isShutdown) return
        try {
            ioExecutor.execute {
                try {
                    val ann = annDao.insert(b.id, start, end, quotedText, note ?: "", safeColor)
                    mainHandler.post {
                        if (isFinishing || isDestroyed) return@post
                        try {
                            webView?.evaluateJavascript(
                                "if(window.setAnnotationId){window.setAnnotationId($blockIdx, ${JSONObject.quote(ann.id)});}",
                                null
                            )
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    override fun onDeleteAnnotation(annotationId: String) {
        if (ioExecutor.isShutdown) return
        try {
            ioExecutor.execute { try { annDao.delete(annotationId) } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }

    override fun onClearAnnotations() {
        val b = book ?: return
        if (ioExecutor.isShutdown) return
        try {
            ioExecutor.execute { try { annDao.deleteAllByBookId(b.id) } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }

    override fun onAddBookmark(charOffset: Int, chapterName: String?) {
        val b = book ?: return
        if (ioExecutor.isShutdown) return
        try {
            ioExecutor.execute {
                try {
                    bookmarkDao.insert(b.id, charOffset, chapterName)
                    pushBookmarksToJs()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    override fun onDeleteBookmark(bookmarkId: String) {
        val b = book ?: return
        if (ioExecutor.isShutdown) return
        try {
            ioExecutor.execute {
                try {
                    bookmarkDao.delete(bookmarkId)
                    pushBookmarksToJs()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun pushBookmarksToJs() {
        val b = book ?: return
        val bookmarks = bookmarkDao.getBookmarks(b.id)
        val arr = JSONArray().apply {
            for (bm in bookmarks) {
                put(JSONObject().apply {
                    put("id", bm.id)
                    put("charOffset", bm.charOffset)
                    put("chapterName", bm.chapterName ?: "")
                    put("createdAt", bm.createdAt)
                })
            }
        }
        mainHandler.post {
            if (isFinishing || isDestroyed) return@post
            try {
                webView?.evaluateJavascript(
                    "if(window.updateBookmarks){window.updateBookmarks(${JSONObject.quote(arr.toString())});}",
                    null
                )
            } catch (_: Exception) {}
        }
    }

    override fun onSaveContent(content: String) {
        val b = book ?: return
        if (ioExecutor.isShutdown) return
        try {
            ioExecutor.execute {
                try {
                    writeContent(b.id, content)
                    mainHandler.post {
                        if (isFinishing || isDestroyed) return@post
                        Toast.makeText(this@EditorActivity, "正文已修改，批注位置可能会发生变化", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        if (isFinishing || isDestroyed) return@post
                        Toast.makeText(this@EditorActivity, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    override fun onSaveSettings(json: String) {
        try {
            val o = JSONObject(json)
            fontSize = o.optDouble("fontSize", 20.0).toFloat().coerceIn(14f, 40f)
            fontFamily = o.optString("fontFamily", fontFamily)
            bgTheme = o.optString("bgTheme", bgTheme)
            lineSpacing = o.optDouble("lineSpacing", 1.6).toFloat().coerceIn(1.0f, 3.0f)
            pageTurnMode = o.optString("pageTurnMode", pageTurnMode)
            savePreferences()
        } catch (_: Exception) {}
    }

    override fun showInputKeyboard() {
        webView?.post {
            try {
                webView?.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(webView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            } catch (_: Exception) {}
        }
    }

    override fun onCloseBook() {
        mainHandler.post { finish() }
    }

    override fun onExportTxt(content: String) {
        exportContent = content
        try {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "${book?.title ?: "阅读笔记"}_读后感.txt")
            }
            startActivityForResult(intent, 2001)
        } catch (e: Exception) {
            Toast.makeText(this, "无法调起保存界面", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001 && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(exportContent.toByteArray(Charsets.UTF_8))
                        Toast.makeText(this, "导出成功！去文件管理器看看吧", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sessionStartTime = System.currentTimeMillis()
        sessionBookId = book?.id
    }

    override fun onPause() {
        try { webView?.evaluateJavascript("if(window.saveProgress){window.saveProgress();}", null) } catch (_: Exception) {}
        book?.let { b ->
            if (lastProgressOffset > 0 && !ioExecutor.isShutdown) {
                try { ioExecutor.execute { try { dao.updateProgress(b.id, lastProgressOffset) } catch (_: Exception) {} } } catch (_: Exception) {}
            }
        }
        recordSession()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        try { webView?.evaluateJavascript("if(window.saveProgress){window.saveProgress();}", null) } catch (_: Exception) {}
        mainHandler.postDelayed({ finish() }, 300)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        webView?.let { wv ->
            try {
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.loadUrl("about:blank")
                wv.clearHistory()
                wv.removeJavascriptInterface("NovelBridge")
                wv.destroy()
            } catch (_: Exception) {}
        }
        webView = null
        bridge = null
        try { ioExecutor.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun recordSession() {
        val bookId = sessionBookId ?: return
        val start = sessionStartTime
        if (start <= 0L) return
        val end = System.currentTimeMillis()
        val dur = end - start
        sessionStartTime = 0L
        if (dur < 3000L) return
        if (ioExecutor.isShutdown) return
        try { ioExecutor.execute { try { sessionDao.insert(bookId, start, end, dur) } catch (_: Exception) {} } } catch (_: Exception) {}
    }

    private fun textFile(bookId: String) = File(File(filesDir, "books"), "$bookId.txt")

    private fun loadContent(book: Book): String {
        val f = textFile(book.id)
        if (f.exists()) {
            return try { f.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
        }
        f.parentFile?.mkdirs()
        return ""
    }

    private fun writeContent(bookId: String, content: String) {
        val f = textFile(bookId)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "${f.name}.${System.nanoTime()}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) throw java.io.IOException("无法写入文件，请重试")
        }
        dao.updateWordCount(bookId, content.count { !it.isWhitespace() })
    }

    private fun loadPreferences() {
        fontSize = prefs.getFloat("fontSize", 20f)
        fontFamily = prefs.getString("fontFamily", "serif") ?: "serif"
        bgTheme = prefs.getString("bgTheme", "paper") ?: "paper"
        lineSpacing = prefs.getFloat("lineSpacing", 1.6f)
        pageTurnMode = prefs.getString("pageTurnMode", "tap") ?: "tap"
    }

    private fun savePreferences() {
        prefs.edit()
            .putFloat("fontSize", fontSize)
            .putString("fontFamily", fontFamily)
            .putString("bgTheme", bgTheme)
            .putFloat("lineSpacing", lineSpacing)
            .putString("pageTurnMode", pageTurnMode)
            .apply()
    }
}