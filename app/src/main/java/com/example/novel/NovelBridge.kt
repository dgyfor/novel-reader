package com.example.novel

import android.webkit.JavascriptInterface

/**
 * JS ⇄ Kotlin 桥。
 */
class NovelBridge(private val host: Host) {

    interface Host {
        fun onProgress(charOffset: Int, percent: Float)
        fun onAddAnnotation(start: Int, end: Int, quotedText: String, note: String?, color: String, blockIdx: Int)
        fun onDeleteAnnotation(annotationId: String)
        fun onClearAnnotations()
        fun onAddBookmark(charOffset: Int, chapterName: String?)
        fun onDeleteBookmark(bookmarkId: String)
        fun onSaveContent(content: String)
        fun onSaveSettings(json: String)
        fun onCloseBook()
        fun onExportTxt(content: String)

        // 新增：JS 点击输入框时，通知原生弹出软键盘
        fun showInputKeyboard()
    }

    @JavascriptInterface
    fun onExportTxt(content: String) {
        host.onExportTxt(content)
    }

    @JavascriptInterface
    fun onProgress(charOffset: Int, percent: Float) {
        host.onProgress(charOffset, percent)
    }

    @JavascriptInterface
    fun onAddAnnotation(start: Int, end: Int, quotedText: String, note: String?, color: String, blockIdx: Int) {
        host.onAddAnnotation(start, end, quotedText, note, color, blockIdx)
    }

    @JavascriptInterface
    fun onDeleteAnnotation(annotationId: String) {
        host.onDeleteAnnotation(annotationId)
    }

    @JavascriptInterface
    fun onClearAnnotations() {
        host.onClearAnnotations()
    }

    @JavascriptInterface
    fun onAddBookmark(charOffset: Int, chapterName: String?) {
        host.onAddBookmark(charOffset, chapterName)
    }

    @JavascriptInterface
    fun onDeleteBookmark(bookmarkId: String) {
        host.onDeleteBookmark(bookmarkId)
    }

    @JavascriptInterface
    fun onSaveContent(content: String) {
        host.onSaveContent(content)
    }

    @JavascriptInterface
    fun onSaveSettings(json: String) {
        host.onSaveSettings(json)
    }

    @JavascriptInterface
    fun onCloseBook() {
        host.onCloseBook()
    }

    // 新增：JS 侧 handleNativeTap 点击 textarea/input 时调用
    @JavascriptInterface
    fun onFocusInput() {
        host.showInputKeyboard()
    }
}