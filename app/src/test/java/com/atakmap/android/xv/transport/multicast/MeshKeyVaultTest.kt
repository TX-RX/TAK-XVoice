package com.atakmap.android.xv.transport.multicast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshKeyVaultTest {
    private fun key(fill: Int) = ByteArray(AeadCodec.KEY_BYTES) { fill.toByte() }

    @Test
    fun `empty vault round-trips`() {
        val blob = MeshKeyVault.serialize(emptyMap())
        assertEquals(emptyMap<String, ByteArray>(), MeshKeyVault.deserialize(blob))
    }

    @Test
    fun `multi-entry vault round-trips with keys intact`() {
        val keys =
            mapOf(
                "ops-1" to key(0x11),
                "falcon-73" to key(0x22),
                "bravo" to key(0x33),
            )
        val back = MeshKeyVault.deserialize(MeshKeyVault.serialize(keys))
        assertEquals(keys.keys, back.keys)
        keys.forEach { (name, k) -> assertArrayEquals("key for $name", k, back[name]) }
    }

    @Test
    fun `serialization is deterministic regardless of insertion order`() {
        val a = linkedMapOf("z" to key(1), "a" to key(2), "m" to key(3))
        val b = linkedMapOf("a" to key(2), "m" to key(3), "z" to key(1))
        assertArrayEquals(MeshKeyVault.serialize(a), MeshKeyVault.serialize(b))
    }

    @Test
    fun `unicode channel names survive`() {
        val keys = mapOf("café-1" to key(0x44), "日本-2" to key(0x55))
        assertEquals(keys.keys, MeshKeyVault.deserialize(MeshKeyVault.serialize(keys)).keys)
    }

    @Test
    fun `serialize rejects a wrong-length key`() {
        assertThrows(IllegalArgumentException::class.java) {
            MeshKeyVault.serialize(mapOf("bad" to ByteArray(16)))
        }
    }

    @Test
    fun `deserialize rejects a truncated blob`() {
        val blob = MeshKeyVault.serialize(mapOf("ops-1" to key(0x11)))
        assertThrows(IllegalArgumentException::class.java) {
            MeshKeyVault.deserialize(blob.copyOfRange(0, blob.size - 4))
        }
    }

    @Test
    fun `deserialize rejects an unknown version`() {
        val blob = MeshKeyVault.serialize(mapOf("ops-1" to key(0x11)))
        blob[3] = 0x09 // corrupt the version int (big-endian, low byte)
        assertThrows(IllegalArgumentException::class.java) { MeshKeyVault.deserialize(blob) }
    }

    @Test
    fun `deserialize rejects garbage`() {
        assertThrows(IllegalArgumentException::class.java) {
            MeshKeyVault.deserialize(byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun `keys are binary — never routed through a text encoder`() {
        // A key full of bytes that are invalid UTF-8 must survive intact;
        // this guards against anyone "helpfully" stringifying the blob.
        val gnarly = ByteArray(AeadCodec.KEY_BYTES) { (it * 37 + 0x80).toByte() }
        val back = MeshKeyVault.deserialize(MeshKeyVault.serialize(mapOf("c" to gnarly)))
        assertArrayEquals(gnarly, back["c"])
        assertTrue(back["c"]!!.any { it.toInt() and 0xFF >= 0x80 })
    }
}
