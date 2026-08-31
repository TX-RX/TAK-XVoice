// Cross-process Binder contract between the XV plugin (in ATAK's
// UID) and XvVoiceService (in our APK's UID). The split is what
// makes background PTT possible: AudioRecord / SCO / etc need
// FOREGROUND_SERVICE_TYPE_MICROPHONE privilege, which is per-UID.
// Our APK declares the permission and runs the foreground service;
// ATAK's UID does not.
//
// The plugin is the "session orchestrator" — owns Mumble socket (TLS
// cert from ATAK runtime), CoT presence, UI, ATAK lifecycle. The
// service is the "voice plant" — owns mic, speaker, SCO, AINA
// buttons, codec, PTT state machine.
//
// Data path:
//   - TX: service captures mic -> encodes Opus -> sends to plugin
//         via IXvVoiceListener.onTxOpus -> plugin transmits via
//         Mumble TCP socket.
//   - RX: plugin receives Opus from Mumble -> hands to service via
//         IXvVoice.onRxOpus -> service decodes + plays.
//
// Data is small: ~50 B per Opus frame at 100 fps = 5 KB/s each
// direction. Binder buffer is 1 MB; latency overhead is ~80 us per
// transaction. Comfortably fine.
package com.atakmap.android.xv.service;

import com.atakmap.android.xv.service.IXvVoiceListener;

interface IXvVoice {
    // Lifecycle ----------------------------------------------------

    // AIDL contract version. Bump on every breaking schema change so
    // a stale plugin (loaded from a different XV APK build) can detect
    // the mismatch and refuse to issue calls that would behave
    // unexpectedly. v1 → v2: added notifyIncomingCall + corresponding
    // listener callbacks for VX direct-call ringing UI.
    int getApiVersion();

    // Plugin → service: register the listener (passed across the
    // Binder; service holds it via RemoteCallbackList which auto-
    // unregisters the listener if the plugin's process dies).
    void registerListener(IXvVoiceListener listener);
    void unregisterListener(IXvVoiceListener listener);

    // PTT (single dispatch path, replaces the in-plugin PttDispatcher)
    void pttDown(int slot);
    void pttUp(int slot);

    // Settings (mirror the in-plugin SharedPreferences). The service
    // keeps its own copy in its own data dir.
    void setLatchedMode(boolean enabled);
    void setPttTimeoutSec(int seconds);
    void setLatchedTimeoutSec(int seconds);
    void setStatusTonesEnabled(boolean enabled);

    // Hot Mic mode (PTT-latency optimization). When enabled, SCO is
    // pre-warmed for the entire time the operator is in a Mumble
    // channel — eliminates the 500-1500 ms cold-start on the first
    // PTT after a long pause. Cost: media apps stay paused for the
    // whole call rather than just during PTT bursts.
    void setHotMicEnabled(boolean enabled);
    void setTptTone(String name);
    void setOutputRoute(String name);

    // Short human label for the current audio route. The plugin's
    // main view shows this so the operator can see at a glance
    // whether they're on AINA / wired / built-in speaker / earpiece.
    // Updates are pushed via IXvVoiceListener.onAudioRouteChanged;
    // this is the initial-read accessor (called once on bind).
    String getAudioRouteLabel();

    // Optional override: a specific BT audio device (by MAC) that
    // should win over the regular route preference. Empty/null
    // disables the override and reverts to the priority chain.
    // Use case: AINA buttons but car BT audio, or AINA + headphones.
    void setOutputBtOverride(String mac);

    // AINA (BLE GATT subscriptions need to live in OUR process so
    // they survive ATAK backgrounding; otherwise ATAK's process can
    // be killed and the buttons go silent).
    void connectAina(String mac, String name, String kind);
    void disconnectAina();
    boolean isAinaConnected();

    // Drop the primary AINA button reader ONLY — leave the audio
    // route hint (preferredBtMacHint) and the connectedAinaMac in
    // place. Used when the operator flips the button-input protocol
    // on a currently-connected AINA to "no buttons / audio only":
    // XV stops listening for button events (SPP / BLE / BLE-HID
    // reader torn down) but the router still knows to prefer that
    // device for BT audio. A full [disconnectAina] would clear the
    // hint and randomize the BT audio pick on the next TX.
    void disconnectAinaReaderOnly();

    // EXTERNAL BUTTON PTT input — an optional BLE PTT puck (Pryme
    // BT-PTT-Z, PTT-Z01, generic BLE-HID) whose button drives slot 0
    // in parallel with the primary speakermic. Button-only role.
    // PttDispatcher's OR-gate keeps concurrent presses from cutting
    // each other off so a motorcyclist with an AINA helmet
    // speakermic + a handlebar Pryme puck can hold either button
    // without one tearing the other's TX down. The external button
    // is hard-locked to slot 0; PTTS / PTTE / MFB on the external
    // device are ignored.
    void connectExternalButton(String mac, String name, String kind);
    void disconnectExternalButton();
    boolean isExternalButtonConnected();

    // Samsung ruggedized-device Active Key PTT source. When enabled
    // AND the device is a Samsung Tab Active5 / XCover6 Pro / etc.
    // that actually has the key, the service registers a broadcast
    // receiver for `HARD_KEY_REPORT` and translates press / release
    // into slot-0 PTT edges via `PttSource.SAMSUNG_ACTIVE_KEY`. On
    // any non-Samsung device the plugin never enables this — it's a
    // zero-cost feature (no receiver registered, no behaviour
    // change). Independent of AINA / External Button; uses the
    // dispatcher's multi-source OR-gate so concurrent presses across
    // sources don't cut each other off.
    void setSamsungActiveKeyEnabled(boolean enabled);
    boolean isSamsungActiveKeyRunning();

    // Foreground-KeyEvent fallback for the Samsung Active Key. Some
    // firmware (verified on Tab Active5 / SM-X308U 2026-07-10) does
    // NOT emit `HARD_KEY_REPORT` and only routes the key as a
    // `KeyEvent` to the foreground activity. XvMapComponent hooks the
    // MapView's OnKeyListener, filters `KEYCODE == 1015`, and forwards
    // the down/up edge across this AIDL so the service's PttDispatcher
    // sees a `PttSource.SAMSUNG_ACTIVE_KEY`-tagged edge (same source
    // tag the broadcast path uses — the dispatcher's OR-gate collapses
    // duplicates when both paths happen to fire on the same press).
    // No-op on non-Samsung devices (plugin gates the call on
    // `SamsungActiveKey.isSupported`).
    void notifySamsungActiveKeyEdge(boolean isDown);

    // Mumble session signal. The plugin still owns the Mumble TCP
    // socket (because cert lookup needs ATAK runtime), but tells the
    // service when there's a live session so the service knows
    // canTransmit() — gate for TPT and bonk on no-channel presses.
    void setMumbleSessionState(boolean connectedAndInChannel);

    // Plugin → service: the voice transport just dropped (network
    // swap, server reachability loss, ping watchdog). If the operator
    // is mid-burst, the service plays the cutoff tone via the SCO
    // route so the speakermic surfaces the "comms lost" feedback,
    // then force-stops TX so the captured PCM stops being encoded
    // into a dead pipe. Separate from setMumbleSessionState(false)
    // because the latter is also fired by deliberate channel changes
    // where a tone + stop would be wrong.
    void notifyTransportLost();

    // Per-slot speak permission. Tracks the suppress flag on our own
    // UserState (set by OTS direction enforcement when our group's
    // direction is OUT, or by Mumble admin mute). When false, PTT on
    // that slot rejects with the deny tone instead of running through
    // PRIMING / TPT. Slot 0 = primary, 1 = secondary.
    void setCanSpeakOnSlot(int slot, boolean canSpeak);

    // Plugin → service: our session just moved to a different channel.
    // AudioPlayback drops incoming RX frames for the next ~100 ms to
    // suppress any in-flight audio from the old channel that was
    // already on the wire. Audit L4.
    void notifyChannelMoved(int slot);

    // RX path: plugin pushes Opus frames received from Mumble. The
    // service decodes and plays via AudioPlayback.
    // [slot] 0 = primary, 1 = secondary (VS2). Distinct AudioTracks
    // can be allocated per slot if we want simultaneous playback in
    // a future iteration.
    void onRxOpus(int slot, in byte[] opus, String speakerName);

    // TPT preview from settings UI.
    void playTptPreview();

    // Telecom self-managed call. Plugin can't reach
    // XvConnectionService directly (it's gated on
    // BIND_TELECOM_CONNECTION_SERVICE for Telecom-only access). The
    // voice service runs in the same UID as XvConnectionService and
    // can register the PhoneAccount + call TelecomManager.placeCall
    // on the plugin's behalf. Tag is the channel display name.
    void startChannelCall(String channelTag);
    void endChannelCall();

    // Plugin → service: an inbound VX direct-call REQUEST_CALL just
    // arrived. The service registers an INCOMING call with Telecom so
    // Android shows the system ring UI (lock-screen ANSWER/DECLINE,
    // ringtone, full-screen heads-up). Decisions come back via
    // IXvVoiceListener.onIncomingCallAnswered / onIncomingCallRejected.
    // tempChannelId + callerSession are echoed back in the callback so
    // the plugin knows which call the operator just answered.
    void notifyIncomingCall(String callerCallsign, int tempChannelId, int callerSession);

    // Plugin → service: the callee just accepted an outgoing call.
    // Engages the latched mic so the operator can talk. Outgoing
    // calls defer mic-engagement to this point so the caller's
    // mic isn't hot during the ringing phase (phone behavior, not
    // radio).
    void engagePrivateCallMic();

    // Tear down the service-side voice plant (used during plugin
    // unload to release SCO, focus, etc). The Service itself stays
    // alive until stopService.
    void teardown();
}
