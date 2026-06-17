package com.atakmap.android.xv.aina

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.atakmap.android.xv.transport.ReconnectPolicy
import java.io.IOException
import java.util.UUID

// AINA APTT V1 — Bluetooth Classic SPP/RFCOMM. The V1 hardware emits ASCII
// frames (`+PTT=P`, `+PTT=R`, etc) on a serial channel discovered via the
// standard SPP UUID. Read loop owns the socket and blocks on
// inputStream.read(); callers must marshal back to whatever thread they need.
@SuppressLint("MissingPermission")
class AinaSppReader(
    private val context: Context,
    private val onEvent: (AinaButton, isDown: Boolean) -> Unit,
    private val onConnectionState: (Boolean) -> Unit = {},
    // Fired exactly once when the SPP reader hits a non-recoverable
    // condition (today: BLUETOOTH_CONNECT runtime permission revoked).
    // The reader does NOT enter the ReconnectPolicy backoff after a
    // fatal — the operator has to act (re-grant the permission, or
    // pair a different device) before another connect attempt makes
    // sense. Callers can surface this in the UI; default no-op keeps
    // older call sites compiling unchanged.
    private val onFatal: (reason: String) -> Unit = {},
    // Test seam — injected socket factory. Production stays on the
    // BluetoothDevice methods; unit tests pass a fake factory that
    // can throw SecurityException / IOException on demand so the
    // permission-revoked and SDP-failed branches can be exercised
    // without standing up a real BluetoothSocket. Public so unit
    // tests in the same module can substitute without needing
    // reflection (Kotlin's overload-resolution rules block a SAM
    // lambda when the type itself is internal).
    private val socketFactory: SocketFactory = DefaultSocketFactory,
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
            Log.i(TAG, "reconnect: re-opening SPP to ${redactMac(dev.address)}")
            parser.reset()
            thread = Thread({ runReadLoop(dev) }, "aina-spp-reader").also { it.start() }
        }

    fun connect(device: BluetoothDevice) {
        disconnect()
        stopRequested = false
        targetDevice = device
        reconnectPolicy.reset()
        parser.reset()
        Log.i(
            TAG,
            "Connecting SPP to AINA at ${redactMac(device.address)} (${device.name ?: "?"})",
        )
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
        val result = openSppSocketWithRetry(device)
        when (result) {
            is OpenResult.Success -> {
                socket = result.socket
                Log.i(TAG, "SPP connected")
                // Successful open — reset backoff so the next drop starts
                // from the short end of the schedule again.
                reconnectPolicy.reset()
                onConnectionState(true)
                pumpInputStream(result.socket)
            }
            is OpenResult.Fatal -> {
                Log.e(TAG, "SPP open FATAL — not scheduling retry: ${result.reason}")
                onConnectionState(false)
                onFatal(result.reason)
                return
            }
            is OpenResult.Exhausted -> {
                onConnectionState(false)
                // Initial connect burst exhausted. Stay in the reconnect
                // loop so the operator doesn't have to dig into Settings
                // when the AINA finally finishes booting / comes into
                // range.
                if (!stopRequested) scheduleReconnect("initial connect burst exhausted")
                return
            }
        }
    }

    private fun pumpInputStream(sock: BluetoothSocket) {
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

    internal sealed class OpenResult {
        data class Success(
            val socket: BluetoothSocket,
        ) : OpenResult()

        // Hard failure — caller MUST NOT schedule another retry.
        // Today only one trigger: BLUETOOTH_CONNECT runtime permission
        // revoked (SecurityException survives the secure→insecure
        // fallback). Carries a human-readable reason so the UI layer
        // can render *why* without parsing logs.
        data class Fatal(
            val reason: String,
        ) : OpenResult()

        // Burst exhausted but still recoverable — schedule the next
        // ReconnectPolicy delay. This is the "AINA hasn't finished
        // booting / is out of range" case.
        object Exhausted : OpenResult()
    }

    private fun openSppSocketWithRetry(device: BluetoothDevice): OpenResult {
        var lastErr: Throwable? = null
        // Initially follow the legacy attempt-3-uses-insecure schedule.
        // Flipped early to true the moment we see a SecurityException
        // or "SDP failed" IOException on createRfcomm... — those
        // signals tell us the secure path will never work this burst
        // (M4 quick-win), so we save two attempt's worth of latency by
        // jumping straight to the insecure fallback.
        var forceInsecureForBurst = false
        for (attempt in 1..MAX_CONNECT_ATTEMPTS) {
            if (stopRequested) return OpenResult.Exhausted
            // Log bond state at each connect attempt — distinguishing
            // "AINA wasn't pre-bonded" from "AINA bonded but service
            // not advertising yet" is one of the field-recurring
            // diagnoses we want quick visibility on.
            val bondState =
                try {
                    when (device.bondState) {
                        BluetoothDevice.BOND_BONDED -> "BONDED"
                        BluetoothDevice.BOND_BONDING -> "BONDING"
                        BluetoothDevice.BOND_NONE -> "NONE"
                        else -> "UNKNOWN(${device.bondState})"
                    }
                } catch (_: Throwable) {
                    "UNREADABLE"
                }
            // First N-1 attempts use the standard SDP-based secure
            // socket. The final attempt falls back to an insecure
            // socket (no SDP lookup, fixed channel 1) because some
            // V1 firmware revisions advertise SDP unreliably.
            val useInsecure = forceInsecureForBurst || attempt == MAX_CONNECT_ATTEMPTS
            Log.i(
                TAG,
                "SPP connect attempt $attempt bond=$bondState insecure=$useInsecure " +
                    "mac=${redactMac(device.address)}",
            )
            val sock =
                try {
                    socketFactory.create(device, SPP_UUID, useInsecure)
                } catch (se: SecurityException) {
                    // Quick-win M3: SecurityException on RFCOMM
                    // socket create on Android 12+ means
                    // BLUETOOTH_CONNECT is revoked. Retrying produces
                    // the same outcome forever; the operator has to
                    // re-grant the permission before another attempt
                    // makes sense. Log loudly, propagate via the
                    // fatal callback, and DO NOT enter the
                    // ReconnectPolicy backoff.
                    Log.e(
                        TAG,
                        "create${if (useInsecure) "Insecure" else ""}Rfcomm... " +
                            "attempt $attempt threw SecurityException — " +
                            "perm=${permissionStatus()} mac=${redactMac(device.address)} — " +
                            "BLUETOOTH_CONNECT revoked, treating as FATAL",
                        se,
                    )
                    return OpenResult.Fatal(
                        "BLUETOOTH_CONNECT runtime permission revoked " +
                            "(SecurityException on RFCOMM create)",
                    )
                } catch (ioe: IOException) {
                    // Quick-win M4: "SDP failed" / "service not
                    // found" on createRfcomm... means SDP lookup hit
                    // a busted record on the AINA. The secure path
                    // can't recover within the same burst because
                    // SDP doesn't get re-fetched per attempt — flip
                    // to insecure (fixed channel 1, no SDP lookup)
                    // immediately for the rest of the burst so a
                    // device with a permanently busted SDP record
                    // gets the insecure path inside the FIRST burst
                    // instead of waiting for a full reconnect cycle.
                    Log.w(
                        TAG,
                        "create${if (useInsecure) "Insecure" else ""}Rfcomm... " +
                            "attempt $attempt threw IOException: ${ioe.message}",
                        ioe,
                    )
                    lastErr = ioe
                    if (!useInsecure && isSdpFailureMessage(ioe.message)) {
                        Log.w(
                            TAG,
                            "SDP-style failure detected — switching to INSECURE for remainder of burst",
                        )
                        forceInsecureForBurst = true
                    }
                    null
                } catch (t: Throwable) {
                    Log.w(
                        TAG,
                        "create${if (useInsecure) "Insecure" else ""}Rfcomm... " +
                            "attempt $attempt failed",
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
                return OpenResult.Success(sock)
            } catch (se: SecurityException) {
                // SecurityException from .connect() (not the create)
                // is a strong signal that BLUETOOTH_CONNECT is gone —
                // the socket builder accepted but the actual connect
                // can't get past the runtime permission gate. Fatal.
                Log.e(
                    TAG,
                    "SPP connect attempt $attempt threw SecurityException — perm=${permissionStatus()}",
                    se,
                )
                try {
                    sock.close()
                } catch (_: Throwable) {
                }
                return OpenResult.Fatal(
                    "BLUETOOTH_CONNECT runtime permission revoked " +
                        "(SecurityException on RFCOMM connect)",
                )
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
        return OpenResult.Exhausted
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

    private fun permissionStatus(): String =
        try {
            val granted =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ) == PackageManager.PERMISSION_GRANTED
            if (granted) "BLUETOOTH_CONNECT=GRANTED" else "BLUETOOTH_CONNECT=DENIED"
        } catch (_: Throwable) {
            "BLUETOOTH_CONNECT=UNREADABLE"
        }

    private fun isSdpFailureMessage(msg: String?): Boolean {
        if (msg.isNullOrEmpty()) return false
        val lower = msg.lowercase()
        return lower.contains("sdp") || lower.contains("service") || lower.contains("read failed")
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

    /**
     * Test seam — abstracts the two BluetoothDevice socket-builder
     * methods so unit tests can supply a fake. Production uses
     * [DefaultSocketFactory] which just delegates to the device.
     *
     * Public so unit tests in the same module can pass a SAM lambda
     * via the [AinaSppReader] primary constructor (Kotlin overload
     * resolution rejects the lambda when the SAM type is internal).
     * Production callers don't reference this type at all.
     */
    fun interface SocketFactory {
        @Throws(IOException::class, SecurityException::class)
        fun create(
            device: BluetoothDevice,
            uuid: UUID,
            insecure: Boolean,
        ): BluetoothSocket
    }

    object DefaultSocketFactory : SocketFactory {
        override fun create(
            device: BluetoothDevice,
            uuid: UUID,
            insecure: Boolean,
        ): BluetoothSocket =
            if (insecure) {
                device.createInsecureRfcommSocketToServiceRecord(uuid)
            } else {
                device.createRfcommSocketToServiceRecord(uuid)
            }
    }

    companion object {
        private const val TAG = "AinaSppReader"
        private const val MAX_CONNECT_ATTEMPTS = 3

        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
