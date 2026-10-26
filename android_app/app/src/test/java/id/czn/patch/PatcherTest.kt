package id.czn.patch

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Runs against a copy of real game files: -Dczn.gameres=<gameres copy> -Dczn.data=<release data folder>. */
class PatcherTest {
    private val res = System.getProperty("czn.gameres")?.let(::File)
    private val data = System.getProperty("czn.data")?.let(::File)

    @Test fun xxh64MatchesGameHashes() {
        assumeTrue(res != null)
        val m = File(res, Patcher.MANIFEST).readBytes()
        val etag = File(res, Patcher.ETAG).readText().split(Regex("\r?\n"))
        assertEquals(etag[2], java.lang.Long.toUnsignedString(Xxh64.hash(m)))
        val chunk = File(res, "chunks").listFiles()!!.filter { it.name.endsWith(".ssrc") }.minBy { it.length() }.readBytes()
        val trailer = ByteBuffer.wrap(chunk, chunk.size - 8, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val h = Xxh64(); h.update(chunk, 0, 7); h.update(chunk, 7, chunk.size - 16 - 7)   // odd split exercises the buffer
        assertEquals(trailer, h.digest())
    }

    @Test fun jsonEscapeLikePython() {
        val (s, n) = Json.replaceTextEn("""[{"id": 1, "text_en": "Hi \"you\"\n", "x": "text_en"}]""", mapOf("Hi \"you\"\n" to "Halo\t\"kamu\"\u0001"))
        assertEquals(1, n)
        assertEquals("""[{"id": 1, "text_en": "Halo\t\"kamu\"\u0001", "x": "text_en"}]""", s)
    }

    @Test fun applyAndRestore() {
        assumeTrue(res != null && data != null)
        val p = Patcher(res!!, ::println)
        p.apply(data!!)
        p.restore()
        assertEquals(true, File(res, Patcher.MANIFEST).readBytes().contentEquals(File(res, "${Patcher.BACKUP}/${Patcher.MANIFEST}").readBytes()))
        // restore rewrites the etag with LF + trailing newline, whatever the backup used
        val etag = File(res, Patcher.ETAG).readText()
        assertEquals(File(res, "${Patcher.BACKUP}/${Patcher.ETAG}").readText().replace("\r\n", "\n").trimEnd('\n') + "\n", etag)
        assertEquals(false, '\r' in etag)
        assertEquals(false, p.looksPatched(data))
        p.apply(data)                           // second apply starts from the backup again
        assertEquals(true, p.looksPatched(data))
    }
}
