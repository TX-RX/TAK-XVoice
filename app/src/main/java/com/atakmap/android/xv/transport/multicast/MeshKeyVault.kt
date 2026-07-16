package com.atakmap.android.xv.transport.multicast

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure (de)serialization of the per-channel pre-shared key set for
 * encrypted-at-rest persistence. Produces a compact binary blob that a
 * [com.atakmap.android.xv.security] Keystore box then AES-GCM-encrypts
 * before it ever touches disk — the keys are NEVER written as plaintext
 * or JSON.
 *
 * Keeping this layer pure and binary (not JSON) is deliberate: keys
 * should not pass through a text encoder that might get logged or landed
 * in a stringy pref by accident. The only thing that persists is the
 * ciphertext of [serialize]'s output.
 *
 * Wire format (all ints big-endian):
 *
 *     [ int  version = 1                     ]
 *     [ int  entryCount                      ]
 *     repeated entryCount times:
 *       [ int    channelNameLen              ]
 *       [ bytes  channelName (UTF-8)         ]
 *       [ int    keyLen (== 32)              ]
 *       [ bytes  key                         ]
 *
 * [deserialize] validates aggressively (version, bounds, key length) and
 * throws [IllegalArgumentException] on anything malformed, so a corrupted
 * or truncated store fails closed rather than installing junk keys.
 */
object MeshKeyVault {
    const val VERSION = 1

    // A vault holding more channels than any real deployment, or a name
    // longer than any real channel, is malformed input — cap so a bad
    // length field can't drive a huge allocation.
    private const val MAX_ENTRIES = 4_096
    private const val MAX_NAME_BYTES = 4_096

    fun serialize(keys: Map<String, ByteArray>): ByteArray {
        keys.values.forEach {
            require(it.size == AeadCodec.KEY_BYTES) {
                "channel key must be ${AeadCodec.KEY_BYTES} bytes, got ${it.size}"
            }
        }
        // Sort by channel name so the blob is deterministic — the same
        // key set always serializes identically (stable ciphertext diffs,
        // easier to reason about).
        val entries = keys.entries.sortedBy { it.key }
        val nameBytes = entries.map { it.key.toByteArray(Charsets.UTF_8) }
        val size =
            4 + 4 +
                entries.indices.sumOf { 4 + nameBytes[it].size + 4 + entries[it].value.size }
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(VERSION)
        buf.putInt(entries.size)
        entries.forEachIndexed { i, e ->
            buf.putInt(nameBytes[i].size)
            buf.put(nameBytes[i])
            buf.putInt(e.value.size)
            buf.put(e.value)
        }
        return buf.array()
    }

    fun deserialize(blob: ByteArray): Map<String, ByteArray> {
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.BIG_ENDIAN)
        return try {
            val version = buf.int
            require(version == VERSION) { "unsupported mesh key vault version $version" }
            val count = buf.int
            require(count in 0..MAX_ENTRIES) { "entry count $count out of bounds" }
            val out = LinkedHashMap<String, ByteArray>(count)
            repeat(count) {
                val nameLen = buf.int
                require(nameLen in 1..MAX_NAME_BYTES) { "name length $nameLen out of bounds" }
                require(buf.remaining() >= nameLen) { "truncated: name" }
                val name = ByteArray(nameLen).also { buf.get(it) }
                val keyLen = buf.int
                require(keyLen == AeadCodec.KEY_BYTES) { "key length $keyLen != ${AeadCodec.KEY_BYTES}" }
                require(buf.remaining() >= keyLen) { "truncated: key" }
                val key = ByteArray(keyLen).also { buf.get(it) }
                out[String(name, Charsets.UTF_8)] = key
            }
            out
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (t: Throwable) {
            // BufferUnderflow etc. → uniform "malformed" contract.
            throw IllegalArgumentException("malformed mesh key vault: ${t.message}")
        }
    }
}
