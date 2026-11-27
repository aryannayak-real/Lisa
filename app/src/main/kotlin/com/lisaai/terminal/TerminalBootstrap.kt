package com.lisaai.terminal

import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads and installs the Termux aarch64 bootstrap into this app's private data
 * directory.
 *
 * The bootstrap binaries have the prefix `/data/data/com.termux/files/usr` baked into
 * them. Our applicationId (`com.lisaai`) is exactly the same length, so we can repoint
 * every occurrence in-place without breaking ELF layouts. This is also why the
 * applicationId must never change.
 */
object TerminalBootstrap {
    private const val TAG = "LisaBootstrap"

    private const val PACKAGE_NAME = "com.lisaai"
    private const val OLD_PACKAGE = "com.termux"
    private const val NEW_PACKAGE = "com.lisaai"

    private const val BOOTSTRAP_URL =
        "https://github.com/termux/termux-packages/releases/download/" +
            "bootstrap-2026.02.12-r1%2Bapt.android-7/bootstrap-aarch64.zip"

    private const val CHMOD_0700 = 0x1C0

    /** Short `/data/data/...` spelling, matching the paths baked into the bootstrap. */
    val filesDir = File("/data/data/$PACKAGE_NAME/files")
    val prefixDir = File(filesDir, "usr")
    val stagingDir = File(filesDir, "usr-staging")
    val homeDir = File(filesDir, "home")
    val shellPath = File(prefixDir, "bin/bash")

    @Synchronized
    fun isInstalled(): Boolean = shellPath.isFile && shellPath.canExecute()

    /**
     * Installs the bootstrap if needed.
     *
     * @param onProgress called with a human readable status and a percentage (0..100,
     *   or -1 when unknown). Always invoked from the calling thread.
     */
    @Synchronized
    fun install(onProgress: (String, Int) -> Unit) {
        if (isInstalled()) {
            onProgress("Terminal already installed", 100)
            return
        }

        filesDir.mkdirs()
        homeDir.mkdirs()

        val archive = File(filesDir, "bootstrap-aarch64.zip")

        if (!archive.isFile || archive.length() < 1_000_000) {
            onProgress("Downloading terminal packages…", 0)
            download(archive) { pct -> onProgress("Downloading terminal packages…", pct) }
        } else {
            onProgress("Using cached terminal packages", 100)
        }

        onProgress("Extracting terminal packages…", 0)
        extract(archive)

        archive.delete()

        writeShellConfig()

        if (!isInstalled()) {
            throw IllegalStateException("Bootstrap installed but ${shellPath.path} is missing")
        }

        onProgress("Terminal ready", 100)
    }

    /** Removes a partially extracted bootstrap so the next install starts clean. */
    fun reset() {
        deleteRecursively(prefixDir)
        deleteRecursively(stagingDir)
        File(filesDir, "bootstrap-aarch64.zip").delete()
    }

    private fun download(dest: File, onProgress: (Int) -> Unit) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        tmp.delete()

        var url = URL(BOOTSTRAP_URL)
        var connection: HttpURLConnection

        var redirects = 0
        while (true) {
            connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "Lisa")

            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw IllegalStateException("HTTP $code without Location")
                if (++redirects > 10) throw IllegalStateException("Too many redirects")
                url = URL(url, location)
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                throw IllegalStateException("Bootstrap download failed: HTTP $code")
            }
            break
        }

        try {
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) ((done * 100) / total).toInt() else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    private fun extract(archive: File) {
        deleteRecursively(stagingDir)
        if (!stagingDir.mkdirs()) {
            throw IllegalStateException("Cannot create ${stagingDir.path}")
        }

        val symlinks = ArrayList<Pair<String, String>>(64)
        val oldBytes = OLD_PACKAGE.toByteArray(Charsets.US_ASCII)
        val newBytes = NEW_PACKAGE.toByteArray(Charsets.US_ASCII)
        require(oldBytes.size == newBytes.size)

        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                val target = File(stagingDir, name)
                val targetPath = target.canonicalPath
                if (!targetPath.startsWith(stagingDir.canonicalPath + File.separator)) {
                    throw IllegalStateException("Unsafe zip entry: $name")
                }

                when {
                    name == "SYMLINKS.txt" -> {
                        // Read only this entry. Do not close the reader: it would close
                        // the shared ZipInputStream.
                        val text = zip.readBytes().toString(Charsets.UTF_8)
                        text.lineSequence().forEach { line ->
                            val parts = line.split("\u2190", limit = 2)
                            if (parts.size == 2) {
                                // Absolute targets also contain the old prefix and must be
                                // repointed, e.g. /data/data/com.termux/files/usr/share/...
                                val linkTarget = parts[0].replace(OLD_PACKAGE, NEW_PACKAGE)
                                symlinks.add(linkTarget to File(stagingDir, parts[1]).path)
                            }
                        }
                    }
                    entry.isDirectory -> target.mkdirs()
                    else -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out -> zip.copyTo(out) }

                        if (repointInPlace(target, oldBytes, newBytes)) {
                            Log.d(TAG, "Repointed $name")
                        }

                        if (name.startsWith("bin/") ||
                            name.startsWith("libexec/") ||
                            name == "lib/apt/apt-helper" ||
                            name.startsWith("lib/apt/methods/")
                        ) {
                            Os.chmod(target.path, CHMOD_0700)
                        }
                    }
                }

                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        for ((linkTarget, linkPath) in symlinks) {
            val link = File(linkPath)
            link.parentFile?.mkdirs()
            try {
                Os.symlink(linkTarget, linkPath)
            } catch (e: Exception) {
                Log.w(TAG, "symlink failed: $linkTarget -> $linkPath", e)
            }
        }

        deleteRecursively(prefixDir)
        if (!stagingDir.renameTo(prefixDir)) {
            throw IllegalStateException("Cannot move staging directory to ${prefixDir.path}")
        }

        File(prefixDir, "tmp").mkdirs()
        File(prefixDir, "var/tmp").mkdirs()
    }

    /** Byte-for-byte replacement; equal-length paths keep ELF offsets stable. */
    private fun repointInPlace(file: File, old: ByteArray, new: ByteArray): Boolean {
        val bytes = file.readBytes()
        var index = 0
        var changed = false
        outer@ while (index <= bytes.size - old.size) {
            for (j in old.indices) {
                if (bytes[index + j] != old[j]) {
                    index++
                    continue@outer
                }
            }
            System.arraycopy(new, 0, bytes, index, new.size)
            changed = true
            index += old.size
        }
        if (changed) file.writeBytes(bytes)
        return changed
    }

    private fun writeShellConfig() {
        homeDir.mkdirs()
        val bashrc = File(homeDir, ".bashrc")
        if (!bashrc.isFile) {
            bashrc.writeText(
                """
                export PATH="$prefixDir/bin"
                export PS1='\[\e[32m\]\w\[\e[0m\] \$ '
                """.trimIndent() + "\n"
            )
        }
        val profile = File(homeDir, ".profile")
        if (!profile.isFile) {
            profile.writeText("[ -f ~/.bashrc ] && . ~/.bashrc\n")
        }
    }

    private fun deleteRecursively(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursively(it) }
        file.delete()
    }

    /** Environment for the shell process. Paths use the short `/data/data` spelling. */
    fun shellEnvironment(): Array<String> {
        val prefix = prefixDir.path
        val home = homeDir.path
        val env = mutableListOf(
            "HOME=$home",
            "PREFIX=$prefix",
            "TERMUX_PREFIX=$prefix",
            "PATH=$prefix/bin",
            "TMPDIR=$prefix/tmp",
            "LD_LIBRARY_PATH=$prefix/lib",
            "TERM=xterm-256color",
            "LANG=en_US.UTF-8",
            "LC_ALL=en_US.UTF-8",
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system",
            "SHELL=$prefix/bin/bash",
        )
        val preload = File(prefixDir, "lib/libtermux-exec-ld-preload.so")
        if (preload.isFile) {
            env.add("LD_PRELOAD=${preload.path}")
        }
        return env.toTypedArray()
    }
}
