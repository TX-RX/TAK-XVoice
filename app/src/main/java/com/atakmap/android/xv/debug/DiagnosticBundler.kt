package com.atakmap.android.xv.debug

import android.content.Context
import android.os.Build
import com.atakmap.android.xv.BuildConfig
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Captures a redacted snapshot of XV's recent logcat plus a small build/device
 * metadata header and packages both into a zip in the plugin's cache dir,
 * ready for [android.content.Intent.ACTION_SEND].
 *
 * Design notes:
 *  - We rely on each ATAK plugin sharing ATAK's process UID, so logcat run
 *    as the plugin captures every `Xv*` / `Aina*` log line plus ATAK's
 *    own output. On release builds READ_LOGS is signature-only, so the
 *    `-d` dump only returns our own process logs — that's actually what
 *    we want here.
 *  - Output is bounded to roughly the last [LOG_TAIL_BYTES] of logcat so
 *    a long-running session can't produce a runaway 100MB zip on a
 *    Surface Duo with weeks of uptime.
 *  - Redaction runs line-by-line on the in-memory text *before* we write
 *    it to disk. The redaction function is `internal` and pure so the
 *    unit-test surface stays trivial.
 *  - The zip lives in [Context.getCacheDir] so [androidx.core.content.FileProvider]
 *    can hand it to the receiving app via a content:// URI without needing
 *    external-storage permissions on Android 13+.
 */
class DiagnosticBundler {
    /**
     * Capture logcat + write metadata, zip both, and return the resulting
     * file in [Context.getCacheDir]. Synchronous — callers should hop to
     * a background thread (file I/O + a logcat process invocation is not
     * something you do on the main looper).
     */
    fun captureBundle(context: Context): File {
        val cacheDir = File(context.cacheDir, BUNDLE_SUBDIR).apply { mkdirs() }
        val ts = TIMESTAMP_FMT.format(Date())
        val zipFile = File(cacheDir, "xv-diagnostics-$ts.zip")

        val redactedLog = redact(readFilteredLogcat())
        val metadata = buildMetadata(ts)

        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putNextEntry(ZipEntry("metadata.txt"))
            zip.write(metadata.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("logcat-filtered.txt"))
            zip.write(redactedLog.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return zipFile
    }

    /**
     * Spawns `logcat -d -v threadtime`, reads stdout, and keeps only lines
     * whose tag prefix starts with one of [INTERESTING_TAG_PREFIXES]. The
     * input stream is bounded to the last [LOG_TAIL_BYTES] bytes so this
     * can't blow up the heap on a device that's been running for weeks.
     *
     * `threadtime` format is:
     *   `MM-DD HH:MM:SS.mmm  PID  TID L TAG: message`
     * We parse the tag column out of that and prefix-match.
     */
    private fun readFilteredLogcat(): String {
        val proc =
            try {
                Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime"))
            } catch (t: Throwable) {
                return "[diagnostic-bundle] failed to spawn logcat: ${t.javaClass.simpleName}: ${t.message}\n"
            }
        // Drain stderr in the background so a chatty logcat warning can't
        // wedge the pipe.
        Thread {
            try {
                proc.errorStream.use { it.copyTo(NullOutputStream) }
            } catch (_: Throwable) {
            }
        }.apply { isDaemon = true }.start()

        val rawBytes =
            try {
                proc.inputStream.use { it.readBytes() }
            } catch (t: Throwable) {
                return "[diagnostic-bundle] failed to read logcat: ${t.javaClass.simpleName}: ${t.message}\n"
            }
        try {
            proc.waitFor()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // Tail the input so we cap memory + zip size regardless of how
        // long the process has been alive.
        val trimmed =
            if (rawBytes.size <= LOG_TAIL_BYTES) {
                rawBytes
            } else {
                rawBytes.copyOfRange(rawBytes.size - LOG_TAIL_BYTES, rawBytes.size)
            }

        val out = StringBuilder(trimmed.size.coerceAtMost(LOG_TAIL_BYTES))
        BufferedReader(InputStreamReader(trimmed.inputStream(), Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (lineHasInterestingTag(line)) {
                    out.append(line).append('\n')
                }
            }
        }
        if (out.isEmpty()) {
            out.append("[diagnostic-bundle] no Xv*/Aina* logcat lines in the last ${LOG_TAIL_BYTES / 1024}KB window.\n")
        }
        return out.toString()
    }

    private fun buildMetadata(captureTs: String): String =
        buildString {
            append("XV diagnostic bundle\n")
            append("  generated:       ").append(captureTs).append('\n')
            append("  app id:          ").append(BuildConfig.APPLICATION_ID).append('\n')
            append("  app version:     ")
                .append(BuildConfig.VERSION_NAME)
                .append(" (code ")
                .append(BuildConfig.VERSION_CODE)
                .append(")\n")
            append("  build type:      ").append(BuildConfig.BUILD_TYPE).append('\n')
            append("  device model:    ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("  android sdk:     ").append(Build.VERSION.SDK_INT).append(" (").append(Build.VERSION.RELEASE).append(")\n")
            append("  fingerprint:     ").append(Build.FINGERPRINT).append('\n')
            append('\n')
            append(
                "Logcat is filtered to XV / AINA tags and redacted: BT MACs are masked to first+last octet, " +
                    "public IPv4 literals are replaced with [REDACTED-IP]. RFC1918 private (10.x, 172.16-31.x, " +
                    "192.168.x) and multicast (224.x, 239.x) addresses are kept intact — they're not sensitive " +
                    "and they're useful for diagnosing transport issues.\n",
            )
        }

    companion object {
        /**
         * One-call helper used by the Settings UI. Captures a bundle on a
         * background daemon thread, then launches an ACTION_SEND chooser
         * on the main looper. On any failure, toasts a short message.
         * Kept here (instead of inlined in [com.atakmap.android.xv.ui.XvDropDownReceiver])
         * so the UI wiring stays a one-liner.
         */
        fun captureAndShare(
            context: Context,
            launchContext: Context = context,
            mainHandler: android.os.Handler = android.os.Handler(android.os.Looper.getMainLooper()),
        ) {
            Thread {
                try {
                    val file = DiagnosticBundler().captureBundle(context)
                    val authority = context.packageName + ".fileprovider"
                    val uri =
                        androidx.core.content.FileProvider.getUriForFile(context, authority, file)
                    val send =
                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "XV diagnostics — ${file.name}")
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    val chooser =
                        android.content.Intent
                            .createChooser(send, "Share XV diagnostics")
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    mainHandler.post { launchContext.startActivity(chooser) }
                } catch (t: Throwable) {
                    android.util.Log.w("XvDiagnostics", "share bundle failed", t)
                    mainHandler.post {
                        android.widget.Toast
                            .makeText(
                                context,
                                "Couldn't build diagnostics: ${t.javaClass.simpleName}",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                    }
                }
            }.apply {
                isDaemon = true
                name = "xv-diag-bundle"
            }.start()
        }

        // Tag prefixes the bundler keeps. Everything Xv* (XvPlugin,
        // XvVoiceService, XvCallActivity, XV_PTT etc.) plus the AINA
        // subsystem. Case-insensitive match.
        private val INTERESTING_TAG_PREFIXES = arrayOf("Xv", "XV", "Aina")

        // ~5 MB of logcat tail. Bounds zip size on long-running sessions.
        private const val LOG_TAIL_BYTES = 5 * 1024 * 1024

        private const val BUNDLE_SUBDIR = "diagnostics"

        private val TIMESTAMP_FMT =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

        private object NullOutputStream : java.io.OutputStream() {
            override fun write(b: Int) {}

            override fun write(
                b: ByteArray,
                off: Int,
                len: Int,
            ) {}
        }

        private fun lineHasInterestingTag(line: String): Boolean {
            // threadtime format: "MM-DD HH:MM:SS.mmm  PID  TID L TAG: ..."
            // Find the level byte (single char: V/D/I/W/E/F/A) followed
            // by space, then the tag up to ':'. Cheaper than a regex on
            // millions of lines.
            val colon = line.indexOf(':', startIndex = 20)
            if (colon < 0) return false
            // Walk back from the colon to find the start of the tag.
            var tagEnd = colon
            // Trim trailing spaces in tag if any (logcat usually right-pads).
            while (tagEnd > 0 && line[tagEnd - 1] == ' ') tagEnd--
            var tagStart = tagEnd
            while (tagStart > 0 && line[tagStart - 1] != ' ') tagStart--
            if (tagStart >= tagEnd) return false
            for (prefix in INTERESTING_TAG_PREFIXES) {
                if (tagEnd - tagStart >= prefix.length &&
                    line.regionMatches(tagStart, prefix, 0, prefix.length, ignoreCase = false)
                ) {
                    return true
                }
            }
            return false
        }
    }
}

// Bluetooth MAC: six octets of hex separated by colons.
private val BT_MAC_REGEX = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

// IPv4 dotted quad. We further classify per-match in the replacement
// callback so RFC1918 + multicast keep their literal form.
private val IPV4_REGEX = Regex("\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b")

/**
 * Pure line-oriented redaction:
 *  - Mask BT MAC middle 4 octets: `AA:BB:CC:DD:EE:FF` → `AA:XX:XX:XX:XX:FF`.
 *  - Replace public IPv4 literals with `[REDACTED-IP]`. RFC1918 private
 *    ranges (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`) and IPv4
 *    multicast (`224.0.0.0/4` — anything `224.x` through `239.x`) are
 *    kept as-is because they reveal nothing about the operator's location
 *    or network identity, and they're operationally useful for triage
 *    (multicast group, LAN subnet of the transport).
 *
 * `internal` so DiagnosticBundlerTest can hit it without going through
 * the file/zip machinery.
 */
internal fun redact(input: String): String {
    var step = BT_MAC_REGEX.replace(input) { match ->
        val parts = match.value.split(':')
        // Defensive — the regex guarantees 6 parts but split + index access
        // ought to be explicit.
        if (parts.size != 6) {
            match.value
        } else {
            "${parts[0]}:XX:XX:XX:XX:${parts[5]}"
        }
    }
    step =
        IPV4_REGEX.replace(step) { match ->
            val o1 = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            val o2 = match.groupValues[2].toIntOrNull() ?: return@replace match.value
            val o3 = match.groupValues[3].toIntOrNull() ?: return@replace match.value
            val o4 = match.groupValues[4].toIntOrNull() ?: return@replace match.value
            // Each octet must fit in 0..255 to be a real IPv4 literal;
            // otherwise it's noise (a version string, a timestamp slice,
            // whatever) and we shouldn't touch it.
            if (o1 !in 0..255 || o2 !in 0..255 || o3 !in 0..255 || o4 !in 0..255) {
                return@replace match.value
            }
            if (isPrivateOrMulticast(o1, o2, o3, o4)) {
                match.value
            } else {
                "[REDACTED-IP]"
            }
        }
    return step
}

private fun isPrivateOrMulticast(
    o1: Int,
    o2: Int,
    @Suppress("UNUSED_PARAMETER") o3: Int,
    @Suppress("UNUSED_PARAMETER") o4: Int,
): Boolean {
    // RFC1918 private:
    //   10.0.0.0/8
    //   172.16.0.0/12  (172.16.x.x through 172.31.x.x)
    //   192.168.0.0/16
    if (o1 == 10) return true
    if (o1 == 172 && o2 in 16..31) return true
    if (o1 == 192 && o2 == 168) return true
    // IPv4 multicast: 224.0.0.0/4 (224.x through 239.x).
    if (o1 in 224..239) return true
    return false
}
