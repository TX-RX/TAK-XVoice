package com.atakmap.android.xv.aina

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.atakmap.android.xv.transport.ReconnectPolicy
import java.io.IOException
import java.util.UUID

// AINA APTT V1 — Bluetooth Classic SPP/RFCOMM. The V1 hardware emits ASCII
// frames (`+PTT=P`, `+PTT=R`, etc) on a serial channel discovered via the
// standard SPP UUID. Read loop owns the socket and blocks on
// inputStream.read(); callers must marshal back to whatever thread they need.
@SuppressLint("MissingPermission")
class AinaSppReader(
    @Suppress("unused") private val context: Context,
    private val onEvent: (AinaButton, isDown: Boolean) -> Unit,
    private val onConnectionState: (Boolean) -> Unit = {},
) {
    private val parser = AinaAsciiParser()

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var stopRequested: Boolean = false

    // Last device the operator selected. Survives socket churn so the
    // reconnect path knows who to re-attach to. Cleared by disconnect().
    @Volatile
    private var targetDevice: BluetoothDevice? = null

    // Backoff state for the reconnect path. Resets on every successful
    // socket open. Tuned for the BR/EDR range scenario — operator
    // walks back into range within a minute or they don't.
    private val reconnectPolicy = ReconnectPolicy(ReconnectPolicy.SPP_SCHEDULE)
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable =
        Runnable {
            val dev = targetDevice
            if (stopRequested || dev == null) return@Runnable
            Log.i(TAG, "reconnect: re-opening SPP to ${dev.address}")
            parser.reset()
            thread = Thread({ runReadLoop(dev) }, "aina-spp-reader").also { it.start() }
        }

    fun connect(device: BluetoothDevice) {
        disconnect()
        stopRequested = false
        targetDevice = device
        reconnectPolicy.reset()
        parser.reset()
        Log.i(TAG, "Connecting SPP to AINA at ${device.address} (${device.name ?: "?"})")
        thread = Thread({ runReadLoop(device) }, "aina-spp-reader").also { it.start() }
    }

    fun isConnecting(): Boolean = thread?.isAlive == true

    fun disconnect() {
        stopRequested = true
        targetDevice = null
        reconnectHandler.removeCallbacks(reconnectRunnable)
        reconnectPolicy.reset()
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        thread?.interrupt()
        thread = null
        onConnectionState(false)
    }

    // Schedule the next reconnect attempt using the shared
    // ReconnectPolicy. Caller fires this when the read loop exits
    // (out of range, socket dropped) OR when openSppSocketWithRetry
    // gives up its in-burst attempts. The policy decides delay; we
    // post on the main looper so it doesn't pile up on the read
    // thread that's about to exit.
    private fun scheduleReconnect(reason: String) {
        if (stopRequested) return
        if (targetDevice == null) return
        reconnectHandler.removeCallbacks(reconnectRunnable)
        val delay = reconnectPolicy.nextDelayMs()
        Log.i(
            TAG,
            "scheduleReconnect: attempt ${reconnectPolicy.attemptCount()} in ${delay}ms ($reason)",
        )
        reconnectHandler.postDelayed(reconnectRunnable, delay)
    }

    private fun runReadLoop(device: BluetoothDevice) {
        // BluetoothSocket contract: cancel discovery before connect or it may block.
        try {
            BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        } catch (_: Throwable) {
        }

        // V1 voice responders frequently fail their first SPP connect
        // because the RFCOMM service record isn't advertised until the
        // ACL link is fully up. The jeffypooo/aina-android reference
        // retries up to 3 times — we do the same plus a small backoff
        // and an insecure-socket fallback on the final attempt for
        // devices whose service discovery is unreliable on Android 14+.
        val sock =
            openSppSocketWithRetry(device) ?: run {
                onConnectionState(false)
                // Initial connect burst exhausted. Stay in the reconnect
                // loop so the operator doesn't have to dig into Settings
                // when the AINA finally finishes booting / comes into
                // range.
                if (!stopRequested) scheduleReconnect("initial connect burst exhausted")
                return
            }
        socket = sock
        Log.i(TAG, "SPP connected")
        // Successful open — reset backoff so the next drop starts
        // from the short end of the schedule again.
        reconnectPolicy.reset()
        onConnectionState(true)

        val buf = ByteArray(64)
        try {
            val input = sock.inputStream
            while (!stopRequested) {
                val n =
                    try {
                        input.read(buf)
                    } catch (t: IOException) {
                        if (!stopRequested) Log.w(TAG, "SPP read error: ${t.message}")
                        -1
                    }
                if (n <= 0) break
                Log.i(
                    TAG,
                    "rx ${n}B: ascii='${printable(buf, n)}' hex=${hex(buf, n)}",
                )
                for ((button, isDown) in parser.process(buf, n)) {
                    Log.i(TAG, "parsed: $button down=$isDown")
                    try {
                        onEvent(button, isDown)
                    } catch (t: Throwable) {
                        Log.w(TAG, "onEvent handler threw", t)
                    }
                }
            }
        } finally {
            Log.i(TAG, "SPP read loop exiting")
            try {
                sock.close()
            } catch (_: Throwable) {
            }
            socket = null
            parser.reset()
            onConnectionState(false)
            // Read-loop exit reason: stopRequested means operator
            // disconnected (no retry); anything else is an out-of-range
            // / socket-dropped event that should trigger reconnect so
            // the operator's PTT works again when they walk back into
            // range.
            if (!stopRequested) scheduleReconnect("read loop exit")
        }
    }

    private fun openSppSocketWithRetry(device: BluetoothDevice): BluetoothSocket? {
        var lastErr: Throwable? = null
        for (attempt in 1..MAX_CONNECT_ATTEMPTS) {
            if (stopRequested) return null
            // First N-1 attempts use the standard SDP-based secure
            // socket. The final attempt falls back to an insecure
            // socket (no SDP lookup, fixed channel 1) because some
            // V1 firmware revisions advertise SDP unreliably.
            val useInsecure = attempt == MAX_CONNECT_ATTEMPTS
            val sock =
                try {
                    if (useInsecure) {
                        device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                    } else {
                        device.createRfcommSocketToServiceRecord(SPP_UUID)
                    }
                } catch (t: Throwable) {
                    Log.w(
                        TAG,
                        "create${if (useInsecure) "Insecure" else ""}Rfcomm... attempt $attempt failed",
                        t,
                    )
                    lastErr = t
                    null
                }
            if (sock == null) {
                sleepBackoff(attempt)
                continue
            }
            try {
                sock.connect()
                if (useInsecure) {
                    // Insecure-socket fallback succeeded. The connection
                    // skipped SDP lookup and is using fixed channel 1
                    // without authenticated pairing of the RFCOMM service.
                    // For AINA V1 speakermics this is harmless — the
                    // device only carries button events, no PII — but
                    // it's worth flagging in case a future device class
                    // surfaces here where the insecure path matters.
                    // Field complaint M9: this fallback was previously
                    // silent on success; only the failed attempts logged.
                    Log.w(
                        TAG,
                        "SPP connected via INSECURE fallback on attempt $attempt — " +
                            "device's RFCOMM SDP record is unreliable, using unauthenticated channel-1 path",
                    )
                } else {
                    Log.i(TAG, "SPP connect succeeded on attempt $attempt (secure)")
                }
                return sock
            } catch (t: IOException) {
                Log.w(TAG, "SPP connect attempt $attempt failed: ${t.message}")
                lastErr = t
                try {
                    sock.close()
                } catch (_: Throwable) {
                }
                sleepBackoff(attempt)
            }
        }
        Log.e(TAG, "SPP connect failed after $MAX_CONNECT_ATTEMPTS attempts", lastErr)
        return null
    }

    private fun sleepBackoff(attempt: Int) {
        // 400ms, 800ms — enough for the AINA's RFCOMM service to settle
        // post-ACL but short enough not to feel laggy if it's just busy.
        val sleepMs = 400L * attempt
        try {
            Thread.sleep(sleepMs)
        } catch (_: InterruptedException) {
        }
    }

    private fun printable(
        buf: ByteArray,
        n: Int,
    ): String {
        val sb = StringBuilder(n)
        for (i in 0 until n) {
            val c = (buf[i].toInt() and 0xFF).toChar()
            sb.append(
                when {
                    c == '\r' -> "\\r"
                    c == '\n' -> "\\n"
                    c.code in 0x20..0x7E -> c.toString()
                    else -> "."
                },
            )
        }
        return sb.toString()
    }

    private fun hex(
        buf: ByteArray,
        n: Int,
    ): String {
        val sb = StringBuilder(n * 3)
        for (i in 0 until n) {
            if (i > 0) sb.append(' ')
            sb.append(String.format("%02X", buf[i].toInt() and 0xFF))
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "AinaSppReader"
        private const val MAX_CONNECT_ATTEMPTS = 3

        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
