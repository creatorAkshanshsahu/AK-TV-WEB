package com.aktv.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.widget.FrameLayout
import org.mozilla.geckoview.*

class CursorView(c: Context) : View(c) {
    var px = 0f; var py = 0f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 4f }
    override fun onDraw(cv: Canvas) { cv.drawCircle(px, py, 14f, fill); cv.drawCircle(px, py, 14f, ring) }
}

/** Opens a website in a bundled Firefox engine (GeckoView), with a D-pad pointer. */
class BrowserActivity : Activity() {
    companion object { var runtime: GeckoRuntime? = null }
    private lateinit var geckoView: GeckoView
    private lateinit var cursor: CursorView
    private lateinit var session: GeckoSession
    private var canGoBack = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); hideUi()
        val root = FrameLayout(this); root.setBackgroundColor(Color.BLACK)
        geckoView = GeckoView(this)
        cursor = CursorView(this).apply { isFocusable = false; isClickable = false }
        root.addView(geckoView, FrameLayout.LayoutParams(-1, -1)); root.addView(cursor, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        if (runtime == null) {
            runtime = GeckoRuntime.create(applicationContext)
            // block ads & trackers (also cuts most pop-ups)
            runtime!!.settings.contentBlocking.setAntiTracking(ContentBlocking.AntiTracking.AD or ContentBlocking.AntiTracking.ANALYTIC)
        }
        session = GeckoSession(GeckoSessionSettings.Builder()
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP).build())
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { this@BrowserActivity.canGoBack = canGoBack }
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? = null  // block pop-up windows
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) { hideUi() }
        }
        session.open(runtime!!); geckoView.setSession(session)
        session.loadUri(intent.getStringExtra("url") ?: "https://livetgtv.lovable.app/")
        root.post { cursor.px = root.width / 2f; cursor.py = root.height / 2f; cursor.invalidate() }
    }

    private fun hideUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
    private fun move(dx: Float, dy: Float) {
        val w = geckoView.width.toFloat(); val h = geckoView.height.toFloat(); val edge = 40f
        var ny = cursor.py + dy
        if (dy < 0 && ny < edge) { scroll(dy); ny = edge }
        if (dy > 0 && ny > h - edge) { scroll(dy); ny = h - edge }
        cursor.px = (cursor.px + dx).coerceIn(0f, w - 1); cursor.py = ny.coerceIn(0f, h - 1); cursor.invalidate()
    }
    private fun scroll(dy: Float) { session.panZoomController.scrollBy(ScreenLength.zero(), ScreenLength.fromPixels(dy.toDouble() * 2)) }
    private fun tap() {
        val t = SystemClock.uptimeMillis(); val x = cursor.px; val y = cursor.py
        val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0); d.source = InputDevice.SOURCE_TOUCHSCREEN
        geckoView.dispatchTouchEvent(d); d.recycle()
        geckoView.postDelayed({
            val u = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0); u.source = InputDevice.SOURCE_TOUCHSCREEN
            geckoView.dispatchTouchEvent(u); u.recycle()
        }, 60)
    }
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val step = 30f + minOf(e.repeatCount, 15) * 6f
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (down) move(0f, -step); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) move(0f, step); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) move(-step, 0f); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) move(step, 0f); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { if (down && e.repeatCount == 0) tap(); return true }
            KeyEvent.KEYCODE_BACK -> { if (down) { if (canGoBack) session.goBack() else finish() }; return true }
        }
        return super.dispatchKeyEvent(e)
    }
    override fun onWindowFocusChanged(f: Boolean) { super.onWindowFocusChanged(f); if (f) hideUi() }
    override fun onDestroy() { super.onDestroy(); session.close() }
}
