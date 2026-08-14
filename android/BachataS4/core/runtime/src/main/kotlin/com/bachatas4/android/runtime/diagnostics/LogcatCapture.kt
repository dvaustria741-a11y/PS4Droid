package com.bachatas4.android.runtime.diagnostics

import android.os.Process
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Captures this process's own buffered logcat output.
 *
 * Native code (e.g. libwinlator.so's XConnectorEpoll, which runs inside this
 * app's own process via System.loadLibrary) logs failures like abstract
 * socket bind()/listen() errno via __android_log_print. That output only
 * goes to logcat - it is invisible to application.log/shadps4.log, which
 * only capture the Kotlin app logger and the separately-exec'd shadps4
 * subprocess's stdout/stderr respectively. This fills that gap.
 *
 * Reading logcat filtered to the calling process's own PID does not require
 * the READ_LOGS permission on any supported Android version - apps have
 * always been allowed to read their own log output.
 */
object LogcatCapture {
    private const val TIMEOUT_SECONDS = 5L
    private const val MAX_LINES = 4000

    /** Returns buffered logcat lines for this process, or null if capture failed. */
    fun captureOwnProcess(): String? {
        val pid = Process.myPid()
        return try {
            val process = ProcessBuilder(
                "logcat", "-d", "-v", "threadtime", "--pid=$pid",
            ).redirectErrorStream(true).start()

            val lines = ArrayDeque<String>(MAX_LINES)
            BufferedReader(InputStreamReader(process.inputStream)).useLines { seq ->
                for (line in seq) {
                    if (lines.size == MAX_LINES) lines.removeFirst()
                    lines.addLast(line)
                }
            }

            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
            }

            lines.joinToString("\n").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
