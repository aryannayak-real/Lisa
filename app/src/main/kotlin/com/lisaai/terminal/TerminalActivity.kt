package com.lisaai.terminal

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.concurrent.thread

/**
 * Full screen embedded terminal backed by the Termux bootstrap.
 */
class TerminalActivity : Activity(), TerminalSessionClient, TerminalViewClient {

    private lateinit var root: LinearLayout
    private lateinit var terminalContainer: FrameLayout
    private lateinit var statusView: TextView
    private var terminalView: TerminalView? = null
    private var session: TerminalSession? = null

    private var ctrlButton: TextView? = null
    private var altButton: TextView? = null
    private var ctrlLatched = false
    private var altLatched = false
    private var fontSize = DEFAULT_FONT_SIZE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        fontSize = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getInt(PREF_FONT_SIZE, DEFAULT_FONT_SIZE)
            .coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        terminalContainer = FrameLayout(this)
        root.addView(
            terminalContainer,
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
        )

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            text = "Preparing terminal…"
        }
        terminalContainer.addView(
            statusView,
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )

        root.addView(
            buildKeysBar(),
            LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        setContentView(root)

        if (TerminalBootstrap.isInstalled()) {
            startShell()
        } else {
            installBootstrap()
        }
    }

    override fun onResume() {
        super.onResume()
        terminalView?.setTerminalCursorBlinkerState(true, true)
    }

    override fun onPause() {
        terminalView?.setTerminalCursorBlinkerState(false, false)
        super.onPause()
    }

    private fun buildKeysBar(): HorizontalScrollView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            isFocusable = false
        }

        val bar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            setBackgroundColor(BAR_COLOR)
        }
        bar.addView(
            row,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        ctrlButton = addKey(row, "CTRL") { toggleCtrl() }
        altButton = addKey(row, "ALT") { toggleAlt() }
        addKey(row, "ESC") { sendKey(KeyEvent.KEYCODE_ESCAPE) }
        addKey(row, "TAB") { sendKey(KeyEvent.KEYCODE_TAB) }
        addKey(row, "←") { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        addKey(row, "↑") { sendKey(KeyEvent.KEYCODE_DPAD_UP) }
        addKey(row, "↓") { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        addKey(row, "→") { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        addKey(row, "HOME") { sendKey(KeyEvent.KEYCODE_MOVE_HOME) }
        addKey(row, "END") { sendKey(KeyEvent.KEYCODE_MOVE_END) }
        addKey(row, "PGUP") { sendKey(KeyEvent.KEYCODE_PAGE_UP) }
        addKey(row, "PGDN") { sendKey(KeyEvent.KEYCODE_PAGE_DOWN) }
        addKey(row, "DEL") { sendKey(KeyEvent.KEYCODE_FORWARD_DEL) }
        addKey(row, "-") { sendText("-") }
        addKey(row, "/") { sendText("/") }
        addKey(row, "|") { sendText("|") }
        addKey(row, "~") { sendText("~") }
        addKey(row, "PASTE") { pasteFromClipboard() }
        addKey(row, "A-") { changeFontSize(-FONT_STEP) }
        addKey(row, "A+") { changeFontSize(FONT_STEP) }
        addKey(row, "⌨") { showKeyboard() }

        return bar
    }

    private fun addKey(row: LinearLayout, label: String, onClick: () -> Unit): TextView {
        val key = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(11), 0, dp(11), 0)
            isClickable = true
            isFocusable = false
            isFocusableInTouchMode = false
            background = keyBackground(false)
            setOnClickListener {
                onClick()
                terminalView?.requestFocus()
            }
        }
        row.addView(
            key,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(40),
            ).apply { setMargins(0, 0, dp(4), 0) },
        )
        return key
    }

    private fun keyBackground(active: Boolean): StateListDrawable {
        val normal = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(if (active) ACTIVE_KEY_COLOR else IDLE_KEY_COLOR)
        }
        val pressed = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(PRESSED_KEY_COLOR)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
    }

    private fun toggleCtrl() {
        ctrlLatched = !ctrlLatched
        ctrlButton?.background = keyBackground(ctrlLatched)
        showKeyboard()
    }

    private fun toggleAlt() {
        altLatched = !altLatched
        altButton?.background = keyBackground(altLatched)
        showKeyboard()
    }

    private fun sendKey(keyCode: Int) {
        val view = terminalView ?: return
        var modifiers = 0
        if (ctrlLatched) modifiers = modifiers or KeyHandler.KEYMOD_CTRL
        if (altLatched) modifiers = modifiers or KeyHandler.KEYMOD_ALT
        view.handleKeyCode(keyCode, modifiers)
    }

    private fun sendText(text: String) {
        val view = terminalView ?: return
        for (character in text) {
            view.inputCodePoint(
                TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD,
                character.code,
                false,
                false,
            )
        }
    }

    private fun pasteFromClipboard() {
        val current = session ?: return
        onPasteTextFromClipboard(current)
        showKeyboard()
    }

    private fun changeFontSize(delta: Int) {
        val next = (fontSize + delta).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        if (next == fontSize) return
        fontSize = next
        terminalView?.setTextSize(next)
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putInt(PREF_FONT_SIZE, next)
            .apply()
        Toast.makeText(this, "Font size $next", Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun installBootstrap() {
        thread(name = "lisa-bootstrap") {
            try {
                TerminalBootstrap.install { status, percent ->
                    runOnUiThread {
                        statusView.text = if (percent in 0..100) "$status\n$percent%" else status
                    }
                }
                runOnUiThread { startShell() }
            } catch (t: Throwable) {
                Log.e(TAG, "Bootstrap install failed", t)
                runOnUiThread {
                    statusView.text = "Terminal setup failed:\n\n${t.message}\n\nTap to retry."
                    statusView.setOnClickListener { recreate() }
                }
            }
        }
    }

    private fun startShell() {
        if (session != null) return
        if (!TerminalBootstrap.isInstalled()) {
            statusView.text = "Terminal is not installed."
            return
        }

        val view = TerminalView(this, null)
        view.setTerminalViewClient(this)
        view.setTextSize(fontSize)
        view.setBackgroundColor(Color.BLACK)

        try {
            TerminalBootstrap.ensureAptRepoint(applicationContext)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not install apt repoint hook", t)
        }

        terminalContainer.removeAllViews()
        terminalContainer.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        terminalView = view

        val newSession = TerminalSession(
            TerminalBootstrap.shellPath.path,
            TerminalBootstrap.homeDir.path,
            emptyArray(),
            TerminalBootstrap.shellEnvironment(),
            2000,
            this,
        )
        session = newSession
        view.attachSession(newSession)

        view.requestFocus()
        view.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(view, 0)
        }
    }

    override fun onDestroy() {
        session?.finishIfRunning()
        session = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        // Plain back closes the terminal; Esc and other control keys are in the key row.
        finish()
    }

    private fun showKeyboard() {
        val view = terminalView ?: return
        view.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, 0)
    }

    // ----- TerminalSessionClient -----

    override fun onTextChanged(changedSession: TerminalSession) {
        runOnUiThread { terminalView?.onScreenUpdated() }
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        val title = changedSession.title
        if (!title.isNullOrBlank()) runOnUiThread { setTitle(title) }
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        runOnUiThread { finish() }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        val bytes = text.toByteArray()
        session?.write(bytes, 0, bytes.size)
    }

    override fun onBell(session: TerminalSession) {}

    override fun onColorsChanged(session: TerminalSession) {
        runOnUiThread { terminalView?.onScreenUpdated() }
    }

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}

    override fun getTerminalCursorStyle(): Int? = null

    // ----- TerminalViewClient -----

    override fun onScale(scale: Float): Float = 1f

    override fun onSingleTapUp(e: MotionEvent) {
        showKeyboard()
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = ctrlLatched

    override fun readAltKey(): Boolean = altLatched

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() {
        terminalView?.setTerminalCursorBlinkerState(true, true)
    }

    // ----- Logging -----

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "stack trace", e)
    }

    companion object {
        private const val TAG = "LisaTerminal"
        private const val MATCH_PARENT = -1
        private const val PREFS = "lisa_terminal"
        private const val PREF_FONT_SIZE = "font_size"
        private const val DEFAULT_FONT_SIZE = 32
        private const val MIN_FONT_SIZE = 12
        private const val MAX_FONT_SIZE = 48
        private const val FONT_STEP = 2

        private val BAR_COLOR = 0xFF1B1B1B.toInt()
        private val IDLE_KEY_COLOR = 0xFF2B2B2B.toInt()
        private val ACTIVE_KEY_COLOR = 0xFF2E7D32.toInt()
        private val PRESSED_KEY_COLOR = 0xFF3D3D3D.toInt()
    }
}
