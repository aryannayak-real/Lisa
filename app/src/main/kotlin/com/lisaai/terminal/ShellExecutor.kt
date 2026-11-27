package com.lisaai.terminal

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ShellResult(
    val output: String,
    val exitCode: Int,
    val timedOut: Boolean,
)

/**
 * Runs a non-interactive command in the embedded Termux prefix and captures its output.
 *
 * Separate from the visible terminal session so agent mode works without opening
 * [TerminalActivity]. Each command gets a clean process, the app's private home as the
 * working directory, and the same environment the terminal uses.
 */
object ShellExecutor {

    private const val DEFAULT_TIMEOUT_MS = 60_000L
    private const val MAX_OUTPUT_CHARS = 2_000

    suspend fun run(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): ShellResult = withContext(Dispatchers.IO) {
        if (!TerminalBootstrap.isInstalled()) {
            return@withContext ShellResult(
                output = "Terminal is not installed. Open Terminal once to set it up.",
                exitCode = -1,
                timedOut = false,
            )
        }

        val process = try {
            val builder = ProcessBuilder(TerminalBootstrap.shellPath.path, "-c", command)
                .directory(TerminalBootstrap.homeDir)
                .redirectErrorStream(true)

            val environment = builder.environment()
            environment.clear()
            for (entry in TerminalBootstrap.shellEnvironment()) {
                val split = entry.indexOf('=')
                if (split > 0) {
                    environment[entry.substring(0, split)] = entry.substring(split + 1)
                }
            }
            environment["TERM"] = "dumb"

            builder.start()
        } catch (t: Throwable) {
            return@withContext ShellResult(
                output = "Failed to start shell: ${t.message}",
                exitCode = -1,
                timedOut = false,
            )
        }

        val output = StringBuilder()
        val truncated = AtomicBoolean(false)

        val reader = Thread({
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val room = MAX_OUTPUT_CHARS - output.length
                            if (room > 0) {
                                output.append(buffer, 0, minOf(room, count))
                            }
                            if (count > room) {
                                truncated.set(true)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // The process was destroyed or the stream closed; the exit status below
                // is what matters.
            }
        }, "lisa-command-output").apply {
            isDaemon = true
            start()
        }

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        }
        reader.join(2_000)

        val text = synchronized(output) { output.toString() }.trimEnd()
        val rendered = when {
            text.isEmpty() -> "(no output)"
            truncated.get() -> "$text\n… (truncated)"
            else -> text
        }

        ShellResult(
            output = rendered,
            exitCode = if (finished) process.exitValue() else -1,
            timedOut = !finished,
        )
    }
}
