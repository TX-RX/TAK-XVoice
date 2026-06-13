package com.atakmap.android.xv.transport.mumble

import com.atakmap.android.xv.transport.VxCompat
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import mumble.MumbleProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Coverage for MumbleSession.dispatch — the wire-protocol decode +
 * listener-callback fan-out. Driven via the [dispatchForTest] seam so
 * each message type can be exercised with hand-crafted protobuf bytes,
 * without opening a real socket / read thread.
 *
 * Production callsites that matter:
 *   - VS1 connect: every server-pushed message (Version → ChannelState
 *     → ServerSync → PermissionQuery → UserState → TextMessage → …)
 *     funnels through dispatch.
 *   - VS2 in the same way (parallel session).
 *
 * Coverage targets:
 *   - Each MumbleMessageType routes to the right Listener callback.
 *   - ChannelState merges (name preserved across permission-only updates).
 *   - VX private-call signal parsing recognizes the documented format.
 *   - UDP_TUNNEL voice frames decode and route to onVoice.
 *   - Unknown message types don't throw.
 */
class MumbleSessionDispatchTest {
    private fun session(listener: MumbleSession.Listener): MumbleSession =
        MumbleSession(
            host = "test.example.com",
            port = 64738,
            listener = listener,
            takServerHost = "test.example.com",
            slotSuffix = "VS1",
            vxCompat = VxCompat.OFF,
            deviceUid = "ANDROID-test-uid",
        )

    // ============================================================
    // ServerSync → ourSession + READY transition
    // ============================================================

    @Test
    fun `SERVER_SYNC sets ourSessionId and fires onConnected with welcome text`() {
        val l = mockk<MumbleSession.Listener>(relaxed = true)
        val s = session(l)
        val sync =
            MumbleProto.ServerSync
                .newBuilder()
                .setSession(42)
                .setMaxBandwidth(60_000)
                .setWelcomeText("Welcome to Murmur")
                .build()
        s.dispatchForTest(MumbleMessageType.SERVER_SYNC, sync.toByteArray())
        assertEquals(42, s.ourSessionId())
        verify(exactly = 1) { l.onConnected("Welcome to Murmur") }
    }

    @Test
    fun `SERVER_SYNC without welcome text fires onConnected with null`() {
        val l = mockk<MumbleSession.Listener>(relaxed = true)
        val s = session(l)
        val sync =
            MumbleProto.ServerSync
                .newBuilder()
                .setSession(7)
                .setMaxBandwidth(60_000)
                .build()
        s.dispatchForTest(MumbleMessageType.SERVER_SYNC, sync.toByteArray())
        verify(exactly = 1) { l.onConnected(null) }
    }

    // ============================================================
    // ChannelState — merge, no-clobber on permission updates
    // ============================================================

    @Test
    fun `CHANNEL_STATE adds a new channel and fires onChannel`() {
        val l = mockk<MumbleSession.Listener>(relaxed = true)
        val s = session(l)
        val ch =
            MumbleProto.ChannelState
                .newBuilder()
                .setChannelId(6)
                .setName("REACT")
                .build()
        s.dispatchForTest(MumbleMessageType.CHANNEL_STATE, ch.toByteArray())
        assertEquals("REACT", s.channelNameById(6))
        assertEquals(6, s.channelIdByName("REACT"))
        verify(exactly = 1) { l.onChannel(any()) }
    }

    @Test
    fun `CHANNEL_STATE permission-only update preserves the previously cached name`() {
        // Server frequently sends a follow-up ChannelState with only
        // permissions changed (no name field). Production code must
        // merge — overwriting the name with empty would erase the
        // operator's channel directory.
        val l = mockk<MumbleSession.Listener>(relaxed = true)
        val s = session(l)
        val initial =
            MumbleProto.ChannelState
                .newBuilder()
                .setChannelId(6)
                .setName("REACT")
                .build()
        s.dispatchForTest(MumbleMessageType.CHANNEL_STATE, initial.toByteArray())
        val permissionOnly =
            MumbleProto.ChannelState
                .newBuilder()
                .setChannelId(6)
                // no name field
                .build()
        s.dispatchForTest(MumbleMessageType.CHANNEL_STATE, permissionOnly.toByteArray())
        assertEquals(
            "permission-only update must preserve cached name",
            "REACT",
            s.channelNameById(6),
        )
    }

    @Test
    fun `CHANNEL_REMOVE drops the channel from the directory`() {
        val l = mockk<MumbleSession.Listener>(relaxed = true)
        val s = session(l)
        s.dispatchForTest(
            MumbleMessageType.CHANNEL_STATE,
            MumbleProto.ChannelState
                .newBuilder()
                .setChannelId(6)
                .setName("REACT")
                .build()
                .toByteArray(),
        )
        s.dispatchForTest(
            MumbleMessageType.CHANNEL_REMOVE,
            MumbleProto.ChannelRemove
                .newBuilder()
                .setChannelId(6)
                .build()
                .toByteArray(),
        )
        assertNull("channel must be gone from directory", s.channelNameById(6))
        verify(exactly = 1) { l.onChannelRemove(6) }
    }

    // ============================================================
    // User events
    // ============================================================

    @Test
    fun `USER_STATE fires onUser with the parsed state`() {
        val l = mockk<MumbleSession.Listener>(relaxed = true)
        val s = session(l)
        val u =
            MumbleProto.UserState
                .newBuilder()
                .setSession(31)
                .setName("Alpha---a3f8b2VS1")
                .setChannelId(0)
                .build()
        s.dispatchForTest(MumbleMessageType.USER_STATE, u.toByteArray())
        verify(exactly = 1) { l.onUser(any()) }
    }

    @Test
    fun `USER_REMOVE fires onUserRemove`() {
        val l = mockk<MumbleSession.Listener>(relaxed = true)
        val s = session(l)
        val r =
            MumbleProto.UserRemove
                .newBuilder()
                .setSession(31)
                .setReason("kicked for spam")
                .build()
        s.dispatchForTest(MumbleMessageType.USER_REMOVE, r.toByteArray())
        verify(exactly = 1) { l.onUserRemove(any()) }
    }

    // ============================================================
    // TextMessage + VX signal parsing
    // ============================================================

    @Test
    fun `TEXT_MESSAGE fires onTextMessage for any message`() {
        val l = mockk<MumbleSession.Listener>(relaxed = true)
        val s = session(l)
        val m =
            MumbleProto.TextMessage
                .newBuilder()
                .setActor(5)
                .setMessage("hello world")
                .build()
        s.dispatchForTest(MumbleMessageType.TEXT_MESSAGE, m.toByteArray())
        verify(exactly = 1) { l.onTextMessage(any()) }
        verify(exactly = 0) { l.onPrivateCallSignal(any(), any(), any()) }
    }

    @Test
    fun `TEXT_MESSAGE recognised as VX signal fires both callbacks`() {
        val l = mockk<MumbleSession.Listener>(relaxed = true)
        val s = session(l)
        // Wire format: "[TAK MxVx : ACTION ]payload"
        val m =
            MumbleProto.TextMessage
                .newBuilder()
                .setActor(7)
                .setMessage("[TAK MxVx : REQUEST_CALL ]42")
                .build()
        val actionSlot = slot<String>()
        val payloadSlot = slot<String>()
        val fromSlot = slot<Long>()
        s.dispatchForTest(MumbleMessageType.TEXT_MESSAGE, m.toByteArray())
        verify(exactly = 1) { l.onTextMessage(any()) }
        verify(exactly = 1) {
            l.onPrivateCallSignal(
                capture(actionSlot),
                capture(payloadSlot),
                capture(fromSlot),
            )
        }
        assertEquals("REQUEST_CALL", actionSlot.captured)
        assertEquals("42", payloadSlot.captured)
        assertEquals(7L, fromSlot.captured)
    }

    @Test
    fun `parseVxSignal — recognises REQUEST_CALL with payload`() {
        val r = MumbleSession.parseVxSignal("[TAK MxVx : REQUEST_CALL ]42")
        assertNotNull(r)
        assertEquals("REQUEST_CALL", r!!.first)
        assertEquals("42", r.second)
    }

    @Test
    fun `parseVxSignal — recognises CANCEL_CALL without payload`() {
        val r = MumbleSession.parseVxSignal("[TAK MxVx : CANCEL_CALL ]")
        assertNotNull(r)
        assertEquals("CANCEL_CALL", r!!.first)
        assertEquals("", r.second)
    }

    @Test
    fun `parseVxSignal — null text returns null`() {
        assertNull(MumbleSession.parseVxSignal(null))
    }

    @Test
    fun `parseVxSignal — non-VX text returns null`() {
        assertNull(MumbleSession.parseVxSignal("hello world"))
    }

    @Test
    fun `parseVxSignal — missing closing bracket returns null`() {
        // Defensive: malformed wire would crash substring() if not guarded.
        assertNull(MumbleSession.parseVxSignal("[TAK MxVx : REQUEST_CALL42"))
    }

    // ============================================================
    // REJECT + PERMISSION_DENIED
    // ============================================================

    @Test
    fun `REJECT fires onReject`() {
        val l = mockk<MumbleSession.Listener>(relaxed = true)
        val s = session(l)
        val r =
            MumbleProto.Reject
                .newBuilder()
                .setType(MumbleProto.Reject.RejectType.WrongUserPW)
                .setReason("bad password")
                .build()
        s.dispatchForTest(MumbleMessageType.REJECT, r.toByteArray())
        verify(exactly = 1) { l.onReject(any()) }
    }

    @Test
    fun `PERMISSION_DENIED fires onPermissionDenied`() {
        val l = mockk<MumbleSession.Listener>(relaxed = true)
        val s = session(l)
        val d =
            MumbleProto.PermissionDenied
                .newBuilder()
                .setType(MumbleProto.PermissionDenied.DenyType.Permission)
                .setReason("no enter")
                .build()
        s.dispatchForTest(MumbleMessageType.PERMISSION_DENIED, d.toByteArray())
        verify(exactly = 1) { l.onPermissionDenied(any()) }
    }

    // ============================================================
    // UDP_TUNNEL — voice frame extraction
    // ============================================================

    @Test
    fun `UDP_TUNNEL routes voice payload to onVoice`() {
        val l = mockk<MumbleSession.Listener>(relaxed = true)
        val s = session(l)
        // Build a server-shape voice packet: header byte (type=4 + target=0),
        // session varint, sequence varint, opus-header varint, opus bytes.
        val opus = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val frame =
            byteArrayOf(
                0x80.toByte(), // type=4 << 5, target=0
                0x05, // session=5
                0x09, // sequence=9
                opus.size.toByte(), // opus-header: terminator=false, len=4
            ) + opus
        val sessionSlot = slot<Long>()
        val sequenceSlot = slot<Long>()
        val opusSlot = slot<ByteArray>()
        s.dispatchForTest(MumbleMessageType.UDP_TUNNEL, frame)
        verify(exactly = 1) {
            l.onVoice(capture(sessionSlot), capture(sequenceSlot), capture(opusSlot))
        }
        assertEquals(5L, sessionSlot.captured)
        assertEquals(9L, sequenceSlot.captured)
        assertEquals(opus.toList(), opusSlot.captured.toList())
    }

    @Test
    fun `UDP_TUNNEL with malformed payload silently drops (no listener call, no throw)`() {
        val l = mockk<MumbleSession.Listener>(relaxed = true)
        val s = session(l)
        // Truncated frame — parse returns null, dispatch should not
        // call onVoice and not throw.
        s.dispatchForTest(MumbleMessageType.UDP_TUNNEL, byteArrayOf(0x80.toByte()))
        verify(exactly = 0) { l.onVoice(any(), any(), any()) }
    }

    // ============================================================
    // Unknown / informational types — no crash, no listener calls
    // ============================================================

    @Test
    fun `unknown message type does not throw and does not call any listener method`() {
        val l = mockk<MumbleSession.Listener>(relaxed = true)
        val s = session(l)
        // 99 is not in MumbleMessageType.
        s.dispatchForTest(99, byteArrayOf(0x01, 0x02, 0x03))
        verify(exactly = 0) { l.onUser(any()) }
        verify(exactly = 0) { l.onChannel(any()) }
        verify(exactly = 0) { l.onTextMessage(any()) }
        verify(exactly = 0) { l.onConnected(any()) }
    }

    @Test
    fun `PING is silently absorbed (server ping reply, no listener call)`() {
        val l = mockk<MumbleSession.Listener>(relaxed = true)
        val s = session(l)
        val ping =
            MumbleProto.Ping
                .newBuilder()
                .setTimestamp(123_456_789L)
                .build()
        s.dispatchForTest(MumbleMessageType.PING, ping.toByteArray())
        verify(exactly = 0) { l.onConnected(any()) }
        verify(exactly = 0) { l.onUser(any()) }
    }

    @Test
    fun `garbage payload bytes for a known type do not throw (parse error is swallowed)`() {
        // Production code wraps each dispatch case in try/catch so a
        // single corrupt frame doesn't kill the read loop. Pin that
        // behavior — defensive against a malformed wire from an OTS
        // version mismatch.
        val l = mockk<MumbleSession.Listener>(relaxed = true)
        val s = session(l)
        // Random bytes for CHANNEL_STATE — proto parser will throw,
        // dispatch must swallow.
        s.dispatchForTest(MumbleMessageType.CHANNEL_STATE, byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        verify(exactly = 0) { l.onChannel(any()) }
    }
}
