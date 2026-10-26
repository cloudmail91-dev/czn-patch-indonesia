package id.czn.patch

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** Streaming XXH64 (seed 0); the game checks chunk bodies and the manifest with it. */
class Xxh64 {
    private val v = longArrayOf(P1 + P2, P2, 0, -P1)
    private val buf = ByteArray(32); private var used = 0; private var total = 0L

    fun update(b: ByteArray, off: Int = 0, len: Int = b.size) {
        var p = off; val end = off + len; total += len
        if (used > 0) {
            val n = minOf(32 - used, len); System.arraycopy(b, p, buf, used, n); used += n; p += n
            if (used < 32) return
            stripe(buf, 0); used = 0
        }
        while (p + 32 <= end) { stripe(b, p); p += 32 }
        System.arraycopy(b, p, buf, 0, end - p); used = end - p
    }

    private fun stripe(b: ByteArray, p: Int) { for (i in 0..3) v[i] = round(v[i], long(b, p + 8 * i)) }

    fun digest(): Long {
        var h = if (total >= 32) {
            var a = rotl(v[0], 1) + rotl(v[1], 7) + rotl(v[2], 12) + rotl(v[3], 18)
            for (x in v) a = (a xor round(0, x)) * P1 + P4
            a
        } else P5
        h += total
        var p = 0
        while (p + 8 <= used) { h = rotl(h xor round(0, long(buf, p)), 27) * P1 + P4; p += 8 }
        if (p + 4 <= used) { h = rotl(h xor (int(buf, p) * P1), 23) * P2 + P3; p += 4 }
        while (p < used) { h = rotl(h xor ((buf[p].toLong() and 0xff) * P5), 11) * P1; p++ }
        h = (h xor (h ushr 33)) * P2; h = (h xor (h ushr 29)) * P3
        return h xor (h ushr 32)
    }

    /** File writer that hashes and counts everything written to it. */
    class Out(val path: File) : OutputStream() {
        private val out = BufferedOutputStream(FileOutputStream(path), 1 shl 20)
        private val h = Xxh64(); var count = 0L; private set
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); h.update(b, off, len); count += len }
        fun pad16() = write(ByteArray(((-count) and 15).toInt()))
        fun hash() = h.digest()
        override fun close() = out.close()
    }

    companion object {
        private const val P1 = -7046029288634856825L   // 0x9E3779B185EBCA87
        private const val P2 = -4417276706812531889L   // 0xC2B2AE3D27D4EB4F
        private const val P3 = 1609587929392839161L    // 0x165667B19E3779F9
        private const val P4 = -8796714831421723037L   // 0x85EBCA77C2B2AE63
        private const val P5 = 2870177450012600261L    // 0x27D4EB2F165667C5

        fun hash(b: ByteArray) = Xxh64().apply { update(b) }.digest()
        private fun rotl(x: Long, r: Int) = (x shl r) or (x ushr (64 - r))
        private fun round(acc: Long, input: Long) = rotl(acc + input * P2, 31) * P1
        private fun long(b: ByteArray, p: Int): Long {
            var r = 0L; for (i in 7 downTo 0) r = (r shl 8) or (b[p + i].toLong() and 0xff); return r
        }
        private fun int(b: ByteArray, p: Int): Long {
            var r = 0L; for (i in 3 downTo 0) r = (r shl 8) or (b[p + i].toLong() and 0xff); return r
        }
    }
}
