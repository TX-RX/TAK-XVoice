package com.atakmap.android.xv.util

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * File-backed diagnostic logger for post-mortem field-session analysis.
 *
 * ## Why this exists
 *
 * Android's logcat main ring is bounded (8 MiB per buffer on a Pixel 9
 * Pro). On a device running a normal mix of system + user apps, high-
 * volume tags (Instagram, GMS, mem-pressure churn) can evict a full XV
 * field session's worth of events within a couple of hours. Field-
 * observed 2026-07-12: an operator hit an XV reliability issue for
 * hours during a live event, and by the time the log was pulled at
 * `adb logcat -d` the entire XV-tagged trace had been rolled out —
 * only Telecom framework references to `XvConnectionService` survived.
 * Post-mortem with that little context is essentially "diagnose by
 * inference." Never again.
 *
 * This class writes the same events XV already emits to logcat into a
 * per-day file under `<app-external-files>/xv-logs/xv-<date>.log`.
 * The location is chosen so:
 *
 *  - No `WRITE_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE` permission
 *    is required (`Context.getExternalFilesDir(...)` is app-scoped).
 *  - The path is readable over `adb pull` without root even on locked-
 *    down field devices — the operator can grab it after an incident.
 *  - The path is app-owned so uninstall clears it (nothing lingers).
 *  - The path is opaque to Android's normal file scanners, so operator
 *    photos etc. don't get indexed alongside it.
 *
 * ## What gets written
 *
 * Every [event] call produces one line:
 * ```
 * [HH:mm:ss.SSS] TAG severity : message
 * ```
 * plus a one-shot session-start header on [init] with device fingerprint,
 * OS build, XV version, and PID. A periodic heartbeat (every
 * [HEARTBEAT_INTERVAL_MS]) writes process-state summary so a long silent
 * gap in the file is a plausible signal that the plugin was frozen /
 * killed even if we never got to log the death event itself.
 *
 * ## Redaction
 *
 * All output is subject to the sensitive-content rules in `CLAUDE.md`:
 * MAC addresses, TAK server URLs, and operator callsigns MUST be
 * redacted before they reach this class. Callers pass already-redacted
 * strings. This class does not attempt a second-pass redaction — that
 * would give a false sense of safety. Discipline is at the caller site.
 *
 * ## Rotation policy
 *
 * - One file per calendar day (`xv-YYYY-MM-DD.log`).
 * - Soft size cap [MAX_FILE_BYTES]; on cross, the current file is
 *   renamed with a `.1`, `.2`, ... suffix and a fresh file is opened.
 * - Files older than [RETENTION_DAYS] are deleted at [init] time.
 *
 * ## Thread safety
 *
 * All I/O happens on a single background [HandlerThread] so callers can
 * fire-and-forget from any thread (main, Telecom binder, GATT callback,
 * SPP read loop). If [init] has not been called the [event] path is a
 * cheap no-op — no NPE hazard from misordered lifecycle.
 */
object DiagnosticLogger {
    private const val TAG = "XvDiag"
    private const val DIR_NAME = "xv-logs"
    private const val FILE_PREFIX = "xv-"
    private const val FILE_SUFFIX = ".log"

    /**
     * Soft cap per file before rotation. Sized so a busy field session
     * with heartbeats + PTT bursts + Telecom transitions doesn't hit
     * the cap in a single day. When it does, `.1`, `.2`, ... files
     * appear alongside for that day.
     */
    private const val MAX_FILE_BYTES: Long = 5 * 1024 * 1024

    /**
     * Delete daily files older than this many days on [init]. Field
     * captures rarely need more than a week of history; anything older
     * is either already pulled off the device or forgotten.
     */
    private const val RETENTION_DAYS = 7

    /**
     * How often the heartbeat runnable fires. Long enough that the log
     * stays readable (about 1 line per minute at idle), short enough
     * that a service kill / freeze shows up as a gap in the file that
     * a post-mortem grep will notice.
     */
    private const val HEARTBEAT_INTERVAL_MS: Long = 60_000L

    /**
     * Minimum wall-clock between BufferedWriter flushes in [writeLine].
     * Coalesces burst-PTT write volume into ~one flush/second while
     * bounding data loss on a process kill to ~1 s. See [writeLine].
     */
    private const val FLUSH_INTERVAL_MS: Long = 1_000L

    private val started = AtomicBoolean(false)
    private var appContext: Context? = null
    private var writer: BufferedWriter? = null
    private var currentFile: File? = null
    private var currentDay: String = ""
    private var writeThread: HandlerThread? = null
    private var writeHandler: Handler? = null

    private val pending = ConcurrentLinkedQueue<String>()

    // Wall-clock of the last BufferedWriter flush. See writeLine() for the
    // throttled-flush rationale (2026-07-13 zero-byte-log incident).
    private var lastFlushMs: Long = 0L

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Start the logger. Safe to call more than once; subsequent calls
     * are no-ops. `context` is used for its application context only —
     * do not pass an Activity that will be destroyed soon.
     *
     * On success this writes a session-start marker with device
     * fingerprint (see [writeSessionHeader]) and schedules the
     * heartbeat. On failure (I/O error, storage unavailable), logs a
     * warning to Android logcat and remains a no-op — the plugin
     * still functions, we just lose the file backup.
     */
    fun init(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appCtx = context.applicationContext ?: context
        appContext = appCtx
        val thread = HandlerThread("XvDiag").apply { start() }
        writeThread = thread
        val handler = Handler(thread.looper)
        writeHandler = handler
        handler.post {
            try {
                pruneOldFiles(appCtx)
                openTodaysFile(appCtx)
                writeSessionHeader(appCtx)
                flushPending()
                scheduleHeartbeat()
            } catch (t: Throwable) {
                // I/O setup failed. Log to Android logcat and keep the
                // singleton in "started but no writer" state so [event]
                // silently no-ops rather than crashing the caller.
                Log.w(TAG, "init failed — file logging disabled", t)
            }
        }
    }

    /**
     * Record one diagnostic event. Fire-and-forget; safe from any
     * thread and any lifecycle stage. Never throws.
     *
     * The [tag] should match the caller's Android logcat tag (e.g.
     * `"XvVoicePlant"`) so a `grep TAG` on either the logcat dump or
     * the file surfaces the same events. Severity is one letter:
     * `I`, `W`, `E`, or `D` — matches the [Log] class shorthand.
     */
    fun event(
        tag: String,
        severity: Char = 'I',
        message: String,
    ) {
        if (!started.get()) return
        val line = formatLine(tag, severity, message)
        val handler = writeHandler
        if (handler == null) {
            // init() invoked but its handler post hasn't landed yet.
            // Queue for the flushPending pass.
            pending.offer(line)
            return
        }
        handler.post { writeLine(line) }
    }

    /**
     * Force any queued writes to disk. Callers should invoke this on
     * plugin teardown so a final flush lands before the process may
     * be reaped. Safe from any thread.
     */
    fun flush() {
        val handler = writeHandler ?: return
        handler.post {
            try {
                writer?.flush()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Shut down the writer + background thread. After [shutdown] the
     * next [init] call will re-open cleanly. Safe from any thread.
     */
    fun shutdown() {
        if (!started.compareAndSet(true, false)) return
        val handler = writeHandler
        val thread = writeThread
        writeHandler = null
        writeThread = null
        handler?.post {
            try {
                writer?.close()
            } catch (_: Throwable) {
            }
            writer = null
        }
        thread?.quitSafely()
    }

    /**
     * On-demand snapshot of process-observable state. Writes an audio
     * mode, self-managed phone-account count, and free-memory snapshot
     * to the log so a "why was PTT flaky right here" question can be
     * answered without needing an adb attach.
     *
     * Uses only APIs that do NOT require `READ_PHONE_STATE` (that
     * permission is deliberately not requested by XV — see
     * `PttCellularGate` for the why).
     */
    fun stateSnapshot(
        context: Context,
        reason: String,
    ) {
        if (!started.get()) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val mode =
                when (am?.mode) {
                    AudioManager.MODE_IN_CALL -> "IN_CALL"
                    AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
                    AudioManager.MODE_RINGTONE -> "RINGTONE"
                    AudioManager.MODE_NORMAL -> "NORMAL"
                    null -> "?"
                    else -> "mode=${am.mode}"
                }
            val rt = Runtime.getRuntime()
            val freeMb = rt.freeMemory() / (1024 * 1024)
            val totalMb = rt.totalMemory() / (1024 * 1024)
            val maxMb = rt.maxMemory() / (1024 * 1024)
            event(
                tag = "XvDiag",
                severity = 'I',
                message =
                "snapshot: reason='$reason' audioMode=$mode " +
                    "vmem=${freeMb}M/${totalMb}M/${maxMb}M",
            )
        } catch (t: Throwable) {
            event(tag = "XvDiag", severity = 'W', message = "snapshot threw: ${t.javaClass.simpleName}")
        }
    }

    /** Record a `Throwable` — writes the class name + message on one
     *  line so common grep patterns still work, then the stack trace
     *  on continuation lines. Never throws. */
    fun exception(
        tag: String,
        prefix: String,
        t: Throwable,
    ) {
        event(tag = tag, severity = 'E', message = "$prefix — ${t.javaClass.simpleName}: ${t.message}")
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        // Split the stack trace across multiple log lines so the
        // per-line severity prefix stays parseable. Cap at 20 frames
        // to bound worst-case output size on a runaway loop.
        sw.toString().lineSequence().take(20).forEach { frame ->
            event(tag = tag, severity = 'E', message = "  at $frame")
        }
    }

    private fun formatLine(
        tag: String,
        severity: Char,
        message: String,
    ): String = "[${timeFmt.format(Date())}] $tag $severity : $message"

    private fun writeLine(line: String) {
        val ctx = appContext ?: return
        try {
            rotateIfDayChanged(ctx)
            rotateIfTooLarge(ctx)
            val w = writer ?: return
            w.write(line)
            w.newLine()
            // Time-throttled flush. Field incident 2026-07-13: a fresh
            // install ran for ~2 h and the pulled log was 0 bytes — the
            // BufferedWriter's 8 KiB buffer never filled at idle
            // heartbeat volume, and a long-running service never reaches
            // the shutdown()/flush() durability boundary, so a process
            // kill lost the entire session. Flushing at most once per
            // FLUSH_INTERVAL_MS coalesces burst-PTT writes (100+/s) into
            // one flush per second while bounding worst-case data loss on
            // a kill to ~1 s — the whole point of a post-mortem log.
            val now = System.currentTimeMillis()
            if (now - lastFlushMs >= FLUSH_INTERVAL_MS) {
                w.flush()
                lastFlushMs = now
            }
        } catch (t: Throwable) {
            // I/O died mid-session (storage full, permission revoked).
            // Fall back to logcat and stop trying to write for this
            // process lifetime. Do NOT throw — callers must be safe
            // to fire-and-forget.
            Log.w(TAG, "writeLine failed — file logging paused", t)
            try {
                writer?.close()
            } catch (_: Throwable) {
            }
            writer = null
        }
    }

    private fun flushPending() {
        while (true) {
            val line = pending.poll() ?: break
            writeLine(line)
        }
    }

    private fun openTodaysFile(context: Context) {
        val dir = ensureDir(context) ?: return
        val today = dateFmt.format(Date())
        currentDay = today
        val file = File(dir, "$FILE_PREFIX$today$FILE_SUFFIX")
        currentFile = file
        writer =
            try {
                // FileWriter(file, true) → append mode
                BufferedWriter(FileWriter(file, true))
            } catch (t: Throwable) {
                Log.w(TAG, "cannot open $file for append", t)
                null
            }
    }

    private fun rotateIfDayChanged(context: Context) {
        val today = dateFmt.format(Date())
        if (today == currentDay) return
        try {
            writer?.close()
        } catch (_: Throwable) {
        }
        writer = null
        openTodaysFile(context)
    }

    private fun rotateIfTooLarge(context: Context) {
        val f = currentFile ?: return
        if (f.length() < MAX_FILE_BYTES) return
        try {
            writer?.close()
        } catch (_: Throwable) {
        }
        writer = null
        val dir = f.parentFile ?: return
        var idx = 1
        while (File(dir, "${f.nameWithoutExtension}.$idx$FILE_SUFFIX").exists()) idx++
        f.renameTo(File(dir, "${f.nameWithoutExtension}.$idx$FILE_SUFFIX"))
        openTodaysFile(context)
    }

    private fun pruneOldFiles(context: Context) {
        val dir = ensureDir(context) ?: return
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24L * 60L * 60L * 1000L
        dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
            ?.forEach { f ->
                if (f.lastModified() < cutoff) {
                    try {
                        f.delete()
                    } catch (_: Throwable) {
                    }
                }
            }
    }

    private fun ensureDir(context: Context): File? {
        val base = context.getExternalFilesDir(DIR_NAME) ?: return null
        if (!base.exists()) base.mkdirs()
        return base
    }

    private fun writeSessionHeader(context: Context) {
        val ctx = context
        val versionName =
            try {
                val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                pi.versionName ?: "?"
            } catch (_: Throwable) {
                "?"
            }
        val versionCode =
            try {
                val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pi.longVersionCode.toString()
                } else {
                    @Suppress("DEPRECATION")
                    pi.versionCode.toString()
                }
            } catch (_: Throwable) {
                "?"
            }
        val pid = android.os.Process.myPid()
        val uid = android.os.Process.myUid()
        event(
            tag = "XvDiag",
            severity = 'I',
            message = "======== SESSION START ========",
        )
        event(
            tag = "XvDiag",
            severity = 'I',
            message =
            "session: pkg=${ctx.packageName} v=$versionName vc=$versionCode " +
                "pid=$pid uid=$uid",
        )
        event(
            tag = "XvDiag",
            severity = 'I',
            message =
            "device: brand=${Build.BRAND} model=${Build.MODEL} " +
                "product=${Build.PRODUCT} device=${Build.DEVICE}",
        )
        event(
            tag = "XvDiag",
            severity = 'I',
            message =
            "os: release=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} " +
                "build=${Build.DISPLAY}",
        )
    }

    private fun scheduleHeartbeat() {
        val handler = writeHandler ?: return
        val runnable =
            object : Runnable {
                override fun run() {
                    val ctx = appContext
                    if (ctx == null) return
                    stateSnapshot(ctx, "heartbeat")
                    // Reschedule against the same handler so a shutdown()
                    // that quits the looper naturally stops the chain.
                    writeHandler?.postDelayed(this, HEARTBEAT_INTERVAL_MS)
                }
            }
        handler.postDelayed(runnable, HEARTBEAT_INTERVAL_MS)
    }
}
