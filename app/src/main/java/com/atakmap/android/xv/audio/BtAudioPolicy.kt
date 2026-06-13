package com.atakmap.android.xv.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

// What kind of Bluetooth audio path is currently available, if any. The
// answer drives which AudioTrack profile (STREAM_MUSIC vs STREAM_VOICE_CALL)
// AudioPlayback should build, and whether we need to engage SCO before
// playing.
enum class BtAudioMode {
    // No BT audio device is connected. Use built-in speaker / earpiece /
    // wired path per user preference.
    NONE,

    // BT device connected and supports A2DP. STREAM_MUSIC routes there
    // automatically without us touching SCO.
    A2DP_AVAILABLE,

    // BT device connected as HFP / Headset profile only (e.g. AINA APTT,
    // Pryme, Sheepdog speakermics). Audio only flows over the SCO link, so
    // we must startBluetoothSco + MODE_IN_COMMUNICATION + STREAM_VOICE_CALL
    // for it to reach the device's speaker.
    HFP_ONLY,
}

// Detects which BT audio mode applies right now. Uses BluetoothAdapter's
// HEADSET profile proxy + AudioManager's output device list.
@SuppressLint("MissingPermission")
class BtAudioPolicy(
    private val context: Context,
) {
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val bluetoothAdapter: BluetoothAdapter? =
        BluetoothAdapter.getDefaultAdapter()

    @Volatile
    private var headsetProxy: BluetoothHeadset? = null

    // MAC addresses of BT devices we have observed an ACL_DISCONNECTED for
    // since their last ACL_CONNECTED. The HEADSET profile proxy keeps a
    // device in `connectedDevices` for several seconds after the remote
    // powers off (the OS doesn't notice until the supervision timeout
    // expires). We use this set to override stale proxy data — a
    // disconnect-flagged device must NOT cause classify() to claim
    // HFP_ONLY, otherwise TPT routes through USAGE_VOICE_COMMUNICATION
    // to a dead SCO link and the operator hears nothing.
    private val aclDisconnected: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val aclReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                ctx: Context?,
                intent: Intent?,
            ) {
                val action = intent?.action ?: return
                val device =
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        ?: return
                val mac = device.address ?: return
                when (action) {
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        aclDisconnected += mac
                        invalidateClassifyCache()
                        Log.i(TAG, "ACL_DISCONNECTED $mac — invalidating HFP cache")
                    }
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        if (aclDisconnected.remove(mac)) {
                            invalidateClassifyCache()
                            Log.i(TAG, "ACL_CONNECTED $mac — re-trusting HFP cache")
                        }
                    }
                }
            }
        }

    private val profileServiceListener =
        object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(
                profile: Int,
                proxy: BluetoothProfile,
            ) {
                if (profile == BluetoothProfile.HEADSET) {
                    headsetProxy = proxy as BluetoothHeadset
                    invalidateClassifyCache()
                    Log.i(TAG, "HEADSET profile proxy connected")
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HEADSET) {
                    headsetProxy = null
                    invalidateClassifyCache()
                    Log.i(TAG, "HEADSET profile proxy disconnected — rebinding")
                    // Re-bind. Without this rebind, the proxy stays
                    // null forever after the first profile-disconnect
                    // (BT stack restart, Quick Settings BT toggle,
                    // OS update), and classify() falls back to the
                    // audio-HAL-only path indefinitely. Audit L3.
                    rebindHeadsetProxy()
                }
            }
        }

    /** Listens for `BluetoothAdapter.STATE_ON` so the headset profile
     *  proxy gets rebound after the operator toggles BT off + on (Quick
     *  Settings, system update, airplane-mode cycle). Without this, the
     *  proxy can stay null indefinitely after a BT cycle even if the
     *  profile-service-disconnected callback never fires. Audit L3. */
    private val btStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                ctx: Context?,
                intent: Intent?,
            ) {
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                if (state == BluetoothAdapter.STATE_ON) {
                    Log.i(TAG, "BluetoothAdapter STATE_ON — rebinding HEADSET profile proxy")
                    invalidateClassifyCache()
                    rebindHeadsetProxy()
                } else if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    invalidateClassifyCache()
                    headsetProxy = null
                }
            }
        }

    private fun rebindHeadsetProxy() {
        try {
            bluetoothAdapter?.getProfileProxy(context, profileServiceListener, BluetoothProfile.HEADSET)
        } catch (t: Throwable) {
            Log.w(TAG, "rebindHeadsetProxy: getProfileProxy threw", t)
        }
    }

    fun start() {
        rebindHeadsetProxy()
        // ACL state broadcasts are unprotected (system-bus). Registering
        // exported because pre-Android-13 doesn't accept the explicit
        // RECEIVER_EXPORTED flag; on 13+ omitting the flag is treated as
        // exported for sender=system. Either way the only side effect is
        // updating our internal aclDisconnected set.
        val filter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(aclReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(aclReceiver, filter)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ACL receiver registration failed — stale-HFP cache fix won't activate", t)
        }
        // L3: also listen for the BluetoothAdapter STATE_CHANGED so we
        // rebind after the operator toggles BT off + on. Without this,
        // the proxy stays null indefinitely.
        try {
            val btFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(btStateReceiver, btFilter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(btStateReceiver, btFilter)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "BT state receiver registration failed — proxy rebind won't fire after BT toggle", t)
        }
    }

    fun stop() {
        try {
            context.unregisterReceiver(aclReceiver)
        } catch (_: Throwable) {
        }
        try {
            context.unregisterReceiver(btStateReceiver)
        } catch (_: Throwable) {
        }
        aclDisconnected.clear()
        headsetProxy?.let {
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, it)
        }
        headsetProxy = null
    }

    // Inspect the current BT audio landscape. Called on every RX/TX entry to
    // decide which audio profile we need.
    //
    // HFP wins over A2DP when both are present. Speakermics (AINA V1/V2,
    // Pryme, Sheepdog, Vox Tactical) advertise both profiles: A2DP for
    // playback, HFP for the mic. The mic only flows over SCO — so as
    // soon as the user keys PTT we have to engage SCO to capture, and
    // there's no point routing playback through A2DP separately while
    // SCO is up (the BT chipsets multiplex the link). Treating the
    // device as HFP_ONLY for both sides keeps RX and TX on the same
    // path, which matches operator expectations on a PTT mic.
    //
    // A2DP_AVAILABLE only fires for pure-A2DP devices (Bluetooth
    // earbuds without a HEADSET profile, car kits, JBL-style speakers).
    // For those, mic capture stays on the built-in mic.
    //
    // NONE means no BT audio path — phone speaker / wired / earpiece
    // per AudioRouter.
    // Short-window cache for classify(). Production hot-path: TxController
    // calls classify() multiple times per PTT cycle (start, scoListener
    // CONNECTED, startTpt, on every onPreferredDeviceChanged). Every call
    // dereferences the BluetoothHeadset proxy + iterates AudioManager's
    // output device list + (when verbose) calls `device.alias` which can
    // throw SecurityException without BLUETOOTH_CONNECT. Caching by ~250 ms
    // eliminates the duplicate work without hiding hot-attach events: any
    // BT device add/remove fires a callback chain that re-classifies on
    // the spot anyway. Audit M16.
    @Volatile
    private var cachedMode: BtAudioMode? = null

    @Volatile
    private var cachedAtMs: Long = 0L

    fun classify(): BtAudioMode {
        val now = android.os.SystemClock.elapsedRealtime()
        val cm = cachedMode
        if (cm != null && now - cachedAtMs < CLASSIFY_CACHE_MS) return cm
        val mode = classifyUncached()
        cachedMode = mode
        cachedAtMs = now
        return mode
    }

    /** Invalidate the [classify] result cache. Called by hot-attach
     *  callbacks (ACL broadcast, headset profile proxy change) so the
     *  cache reflects the new BT state on the next classify() call. */
    fun invalidateClassifyCache() {
        cachedMode = null
    }

    private fun classifyUncached(): BtAudioMode {
        val rawHfp = headsetProxy?.connectedDevices.orEmpty()
        val hfp = rawHfp.filter { it.address !in aclDisconnected }
        if (hfp.size != rawHfp.size) {
            Log.i(
                TAG,
                "classify: filtered ${rawHfp.size - hfp.size} ACL-disconnected device(s) from proxy report",
            )
        }
        if (hfp.isNotEmpty()) {
            // device.alias touches BLUETOOTH_CONNECT — wrap in try/catch
            // and gate at DEBUG so the SecurityException path doesn't
            // surface in normal field logs.
            val label =
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    try {
                        hfp.joinToString { it.alias ?: it.address }
                    } catch (_: SecurityException) {
                        hfp.joinToString { it.address }
                    }
                } else {
                    "${hfp.size} device(s)"
                }
            Log.i(TAG, "classify: HFP_ONLY ($label)")
            return BtAudioMode.HFP_ONLY
        }
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val sco = outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        if (sco) {
            Log.i(TAG, "classify: HFP_ONLY (SCO output endpoint present, headset proxy empty)")
            return BtAudioMode.HFP_ONLY
        }
        val a2dp = outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        if (a2dp) {
            Log.i(TAG, "classify: A2DP_AVAILABLE (no HFP profile)")
            return BtAudioMode.A2DP_AVAILABLE
        }
        return BtAudioMode.NONE
    }

    fun connectedHfpDevices(): List<BluetoothDevice> = headsetProxy?.connectedDevices?.filter { it.address !in aclDisconnected }.orEmpty()

    companion object {
        private const val TAG = "XvBtAudioPolicy"

        // Cache TTL for classify(). Long enough to absorb the multi-call
        // PTT-down burst (start → scoListener → startTpt → ~5-10 calls in
        // <50 ms), short enough that any state change visible to the
        // operator (plug in headphones, pair a new device) re-classifies
        // on the next call. Hot-attach events (ACL broadcast, headset
        // profile proxy change) invalidate the cache explicitly anyway,
        // so the TTL is just a safety net against silent OS state drift.
        private const val CLASSIFY_CACHE_MS: Long = 250L

        /**
         * Pure-function classifier. Mirrors the production [classify]
         * priority chain:
         *
         *   1. HFP profile proxy reports connected device(s), filtered
         *      to exclude ACL-disconnected MACs → HFP_ONLY.
         *   2. AudioManager output list contains an SCO endpoint (the
         *      proxy is slow or empty, but the audio HAL still routes
         *      SCO) → HFP_ONLY.
         *   3. AudioManager output list contains an A2DP endpoint (no
         *      HFP profile) → A2DP_AVAILABLE.
         *   4. Otherwise → NONE.
         *
         * Pure so BtAudioPolicyClassifyTest can pin every branch
         * without standing up the BluetoothHeadset proxy.
         */
        @androidx.annotation.VisibleForTesting
        internal fun classifyFromInputs(
            hfpProxyMacs: List<String>,
            aclDisconnectedMacs: Set<String>,
            hasScoOutput: Boolean,
            hasA2dpOutput: Boolean,
        ): BtAudioMode {
            val livehfp = hfpProxyMacs.filter { it !in aclDisconnectedMacs }
            if (livehfp.isNotEmpty()) return BtAudioMode.HFP_ONLY
            if (hasScoOutput) return BtAudioMode.HFP_ONLY
            if (hasA2dpOutput) return BtAudioMode.A2DP_AVAILABLE
            return BtAudioMode.NONE
        }
    }
}
