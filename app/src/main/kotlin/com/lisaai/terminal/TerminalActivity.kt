package com.lisaai.terminal

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.concurrent.thread

/**
 * Full screen embedded terminal backed by the Termux bootstrap.
 */
class TerminalActivity : Activity(), TerminalSessionClient, TerminalViewClient {

    private lateinit var root: FrameLayout
    private lateinit var statusView: TextView
    private var terminalView: TerminalView? = null
    private var session: TerminalSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            text = "Preparing terminal…"
        }
        root.addView(statusView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
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

        val view = TerminalView(this)
        view.setTerminalViewClient(this)
        view.setTextSize(32)
        view.setBackgroundColor(Color.BLACK)

        val params = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        root.removeAllViews()
        root.addView(view, params)
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
        // Plain back closes the terminal; Ctrl+Esc style escape is available in the shell.
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

    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

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
    }
}
