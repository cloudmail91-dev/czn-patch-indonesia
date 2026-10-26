package id.czn.patch

import com.github.luben.zstd.Zstd
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.InflaterInputStream

/**
 * Port of src/patch.py working directly on the phone's gameres folder (see that file for the format notes).
 * Differences: originals always come from czn_id_backup, the story tail is streamed to disk instead of held in
 * memory, and story JSON cells are edited in place (only the text_en values change) instead of re-serialised.
 */
class Patcher(private val res: File, private val log: (String) -> Unit) {
    private val backup = File(res, BACKUP)

    fun gameVersion(): String? =
        Regex("/patch/([\\d.]+)/").find(etagUrl(File(res, ETAG)))?.groupValues?.get(1)?.trim('.')

    fun backupVersion(): String? = File(backup, ETAG).takeIf { it.exists() }
        ?.let { Regex("/patch/([\\d.]+)/").find(etagUrl(it))?.groupValues?.get(1)?.trim('.') }

    fun restore() {
        check(File(backup, ETAG).exists()) {
            "Tidak ada cadangan asli di HP. Hapus data game / pasang ulang game supaya berkas asli diunduh lagi."
        }
        check(etagUrl(File(backup, ETAG)) == etagUrl(File(res, ETAG))) {
            "Game sudah update sejak cadangan dibuat, jadi cadangannya sudah tidak berlaku. Berkas game sekarang " +
                "kemungkinan sudah asli; kalau masih bermasalah, hapus data game supaya diunduh ulang."
        }
        for (f in backup.listFiles()!!) { f.copyTo(place(f.name), overwrite = true); log("  kembalikan ${f.name}") }
        val e = File(res, ETAG)
        if (e.exists()) e.writeBytes((e.readText(Charsets.ISO_8859_1)
            .replace("\r\n", "\n").trimEnd('\n') + "\n").toByteArray(Charsets.ISO_8859_1))
    }

    /** True when the UI table already holds translated text (more than 30% of rows are cache values). */
    fun looksPatched(dataDir: File): Boolean = runCatching {
        val v = JSONObject(File(dataDir, "version.json").readText())
        val chunk = place(v.getString("chunk")).readBytes()
        val plain = uiPlain(chunk, v.getInt("frame_off")).first
        val rows = rows(plain, parseNodes(plain).nodes)
        val values = Dat.load(File(dataDir, "cache.dat")).values.toHashSet()
        rows.isNotEmpty() && rows.count { String(it.second, Charsets.UTF_8) in values } > rows.size * 0.3
    }.getOrDefault(false)

    fun apply(dataDir: File, level: Int = 19) {
        val v = JSONObject(File(dataDir, "version.json").readText())
        val version = v.getString("version"); val chunk = v.getString("chunk"); val storyTail = v.getString("story_tail")
        val langPack = v.getInt("lang_pack"); val frameOff = v.getInt("frame_off"); val storyPack = v.getInt("story_pack")
        val url = etagUrl(File(res, ETAG))
        check(version in url) { "Versi game tidak cocok. Data terjemahan untuk $version, game di HP: ${gameVersion()}" }
        if (File(backup, ETAG).exists() && etagUrl(File(backup, ETAG)) != url) {
            log("Cadangan lama (versi ${backupVersion()}) sudah tidak berlaku, dihapus.")
            backup.deleteRecursively()
        }
        val names = listOf(chunk, storyTail, MANIFEST, ETAG)
        if (!names.all { File(backup, it).exists() }) {
            check(!looksPatched(dataDir)) {
                "Berkas game sudah terpatch, tapi cadangan aslinya tidak ada. Hapus data game (atau pasang ulang) " +
                    "supaya berkas asli diunduh lagi, lalu pasang lagi."
            }
            backup.mkdirs()
            for (n in names) File(backup, n).let { if (!it.exists()) place(n).copyTo(it) }
            log("Cadangan asli disimpan di gameres/$BACKUP")
        }
        val cache = Dat.load(File(dataDir, "cache.dat"))
        log("Data terjemahan: ${cache.size} entri")

        // UI table: rewrite the last frame of its chunk in place
        val chunkBytes = File(backup, chunk).readBytes()
        val (plain, key) = uiPlain(chunkBytes, frameOff)
        val frameLen = Zstd.findFrameCompressedSize(chunkBytes, frameOff, chunkBytes.size - frameOff).also { zcheck(it) }.toInt()
        val m = File(backup, MANIFEST).readBytes()
        check(rebuild(plain, emptyMap()).contentEquals(plain)) { "PLPc rebuild is not lossless" }
        val tr = HashMap<String, String>()
        for ((k, t) in rows(plain, parseNodes(plain).nodes)) cache[String(t, Charsets.UTF_8)]?.let { tr[k] = it }
        val newPlain = translateUi(plain, tr)
        val got = rows(newPlain, parseNodes(newPlain).nodes).toMap()
        check(tr.all { (k, t) -> got[k]?.contentEquals(t.toByteArray()) ?: true }) { "translated rows did not read back" }
        val encUi = encrypt(newPlain, key, level)
        val x = packBase(m, langPack, chunkIdx(src(chunk))) + frameOff
        moveRecord(m, x, frameLen, plain.size, x, encUi.size, newPlain.size)
        val langOut = Xxh64.Out(tmpOf(chunk))
        langOut.write(chunkBytes, 0, frameOff); langOut.write(encUi)
        finishChunk(m, langPack, chunkIdx(src(chunk)), langOut)

        // story tables: append translated copies to the end of the base pack, repoint their records
        val storyFile = File(dataDir, "story_files.json")
        if (storyFile.exists()) {
            val list = JSONArray(storyFile.readText())
            val tailIdx = chunkIdx(src(storyTail))
            val tailX = packBase(m, storyPack, tailIdx)
            val out = Xxh64.Out(tmpOf(storyTail))
            var total = 0
            run {
                src(storyTail).inputStream().use { inp -> copyN(inp, out, src(storyTail).length() - 16) }
                for (i in 0 until list.length()) {
                    val f = list.getJSONObject(i)
                    val name = f.getString("chunk"); val off = f.getLong("off"); val csz = f.getInt("csz"); val dsz = f.getInt("dsz")
                    val sp = xor(readFrame(src(name), off, csz), key)
                    val (newSp, done) = translateStory(sp, cache)
                    log("  cerita ${i + 1}/${list.length()}: $done baris")
                    if (done == 0) continue
                    val enc = encrypt(newSp, key, level)
                    out.pad16()
                    val xOld = packBase(m, storyPack, chunkIdx(src(name))) + off
                    moveRecord(m, xOld, csz, dsz, tailX + out.count, enc.size, newSp.size)
                    out.write(enc); total += done
                }
            }
            finishChunk(m, storyPack, tailIdx, out)
            log("Cerita: $total baris diterjemahkan")
        }

        // The Android client writes this file with LF and a trailing newline. A CRLF copy (the PC
        // launcher's style) makes the client distrust the local manifest, fetch the server manifest and
        // re-download the patched chunks - the text goes back to English. Always normalise to LF.
        val nl = "\n"
        val etag = File(backup, ETAG).readText(Charsets.ISO_8859_1).replace("\r\n", nl)
            .trimEnd('\n').split(nl).toMutableList()
        check(etag.size >= 4) { "manifest.ssra.etag tidak seperti biasanya" }
        etag[2] = java.lang.Long.toUnsignedString(Xxh64.hash(m)); etag[3] = m.size.toString()

        // everything is computed; now swap the files in (originals stay in the backup)
        moveIn(chunk); if (storyFile.exists()) moveIn(storyTail)
        writeIn(MANIFEST, m); writeIn(ETAG, (etag.joinToString(nl) + nl).toByteArray(Charsets.ISO_8859_1))
        log("UI: ${tr.size} entri diterjemahkan")
    }

    // --- files ----------------------------------------------------------------
    private fun place(name: String) = if (name.endsWith(".ssrc")) File(res, "chunks/$name") else File(res, name)
    private fun src(name: String) = File(backup, name).takeIf { it.exists() } ?: place(name)
    private fun tmpOf(name: String) = File(place(name).path + ".czn_tmp")
    private fun moveIn(name: String) =
        Files.move(tmpOf(name).toPath(), place(name).toPath(), StandardCopyOption.REPLACE_EXISTING)
    private fun writeIn(name: String, data: ByteArray) { tmpOf(name).writeBytes(data); moveIn(name) }

    private fun copyN(inp: java.io.InputStream, out: Xxh64.Out, n: Long) {
        val buf = ByteArray(1 shl 20); var left = n
        while (left > 0) {
            val r = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); check(r > 0) { "short read" }
            out.write(buf, 0, r); left -= r
        }
    }

    /** write_chunk(): pad the body (still through the hashing stream), close it, append the SSRC trailer, record sizes + hash in the manifest chunk table. */
    private fun finishChunk(m: ByteArray, pack: Int, idx: Int, out: Xxh64.Out) {
        out.pad16(); out.close()
        val h = out.hash(); val body = out.count
        FileOutputStream(out.path, true).use {
            it.write(byteArrayOf('S'.code.toByte(), 'S'.code.toByte(), 'R'.code.toByte(), 'C'.code.toByte()))
            it.write(le(12).putInt(idx).putLong(h).array())
        }
        val e = chunkEntries(m).getValue(idx to pack)
        le(m).putLong(e + 8, body).putLong(e + 16, body + 16).putLong(e + 24, h)
    }

    companion object {
        const val PKG = "com.smilegate.chaoszero.stove.google"
        const val MANIFEST = "manifest.ssra"; const val ETAG = "manifest.ssra.etag"; const val BACKUP = "czn_id_backup"
        private const val Z: Byte = 0
        private const val HDR_NODES = 17; private const val HDR_SLOTS = 21; private const val TABLE = 43

        fun etagUrl(f: File) = f.readText(Charsets.ISO_8859_1).substringBefore('\n').trimEnd('\r')
        private fun le(b: ByteArray): ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        private fun le(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        private fun zcheck(r: Long) = check(!Zstd.isError(r)) { "zstd: " + Zstd.getErrorName(r) }

        fun unzstd(src: ByteArray, off: Int, len: Int): ByteArray {
            val size = Zstd.getFrameContentSize(src, off, len); check(size >= 0) { "zstd frame without size" }
            val out = ByteArray(size.toInt())
            val n = Zstd.decompressByteArray(out, 0, out.size, src, off, len); zcheck(n)
            check(n == size) { "zstd: short frame" }
            return out
        }

        fun readFrame(f: File, off: Long, csz: Int): ByteArray {
            val buf = ByteArray(csz)
            RandomAccessFile(f, "r").use { it.seek(off); it.readFully(buf) }
            return unzstd(buf, 0, csz)
        }

        /** load_original(): the UI frame, decrypted, and the master key recovered from it. */
        fun uiPlain(chunk: ByteArray, frameOff: Int): Pair<ByteArray, ByteArray> {
            val len = Zstd.findFrameCompressedSize(chunk, frameOff, chunk.size - frameOff).also { zcheck(it) }.toInt()
            val enc = unzstd(chunk, frameOff, len)
            val key = masterKey(enc)
            return xor(enc, key) to key
        }

        /** keystream = most common byte per position (plaintext is mostly 0x00); ties go to the first seen, like Counter. */
        fun masterKey(enc: ByteArray): ByteArray {
            val count = IntArray(65536); val first = IntArray(65536)
            for (i in enc.indices) {
                val k = ((i and 255) shl 8) or (enc[i].toInt() and 255)
                if (count[k]++ == 0) first[k] = i
            }
            val ks = ByteArray(256) { j ->
                var best = -1
                for (b in 0 until 256) {
                    val k = (j shl 8) or b
                    if (count[k] == 0) continue
                    if (best < 0) { best = b; continue }
                    val bk = (j shl 8) or best
                    if (count[k] > count[bk] || (count[k] == count[bk] && first[k] < first[bk])) best = b
                }
                best.toByte()
            }
            return ByteArray(256) { ks[Math.floorMod(it - enc.size, 256)] }
        }

        /** PLPc: byte j is XOR'd with master key[(j + len(data)) % 256]. */
        fun xor(data: ByteArray, key: ByteArray): ByteArray {
            val r = data.size % 256
            return ByteArray(data.size) { (data[it].toInt() xor key[(it + r) and 255].toInt()).toByte() }
        }

        fun encrypt(plain: ByteArray, key: ByteArray, level: Int): ByteArray {
            val enc = Zstd.compress(xor(plain, key), level)
            check(xor(unzstd(enc, 0, enc.size), key).contentEquals(plain)) { "zstd round-trip failed" }
            return enc
        }

        // --- PLPc tables ----------------------------------------------------------
        class Node(val pos: Int, val nameOff: Int, val nameLen: Int, val valOff: Int, val valLen: Int, val b: Int)
        class Nodes(val nSlots: Int, val nodes: List<Node>, val tailOff: Int)

        fun parseNodes(plain: ByteArray): Nodes {
            val bb = le(plain)
            val nSlots = bb.getInt(HDR_SLOTS)
            var pos = TABLE + 5 * nSlots
            val nodes = ArrayList<Node>()
            while (pos < plain.size && !isPlpc(plain, pos)) {
                val size = bb.getInt(pos); val t = plain[pos + 4].toInt()
                val nl = plain[pos + 5].toInt() and 255; val vl = bb.getInt(pos + 6); val b = bb.getInt(pos + 11)
                check(t == 2 && size == 15 + nl + vl) { "bad node at $pos" }
                nodes += Node(pos, pos + 15, nl, pos + 15 + nl, vl, b)
                pos += size
            }
            val count = if (pos < plain.size) bb.getInt(pos + HDR_NODES) else bb.getInt(HDR_NODES)
            check(nodes.size == count) { "node count mismatch" }
            return Nodes(nSlots, nodes, pos)
        }

        private fun isPlpc(b: ByteArray, p: Int) =
            p + 4 <= b.size && b[p] == 'P'.code.toByte() && b[p + 1] == 'L'.code.toByte() && b[p + 2] == 'P'.code.toByte() && b[p + 3] == 'c'.code.toByte()

        /** Lay the nodes out again with new values {node index: bytes}, remapping every absolute pointer. */
        fun rebuild(plain: ByteArray, newVals: Map<Int, ByteArray>): ByteArray {
            val (nSlots, nodes, tailOff) = parseNodes(plain).let { Triple(it.nSlots, it.nodes, it.tailOff) }
            val remap = HashMap<Int, Int>(nodes.size * 2)
            var pos = TABLE + 5 * nSlots
            nodes.forEachIndexed { i, n -> remap[n.pos] = pos; pos += 15 + n.nameLen + (newVals[i]?.size ?: n.valLen) }
            val out = ByteArray(pos + plain.size - tailOff)
            val ob = le(out); val pb = le(plain)
            System.arraycopy(plain, 0, out, 0, TABLE)
            for (s in 0 until nSlots) {
                val p = pb.getInt(TABLE + 5 * s + 1)
                ob.putInt(TABLE + 5 * s + 1, if (p != 0) remap.getValue(p) else 0)
            }
            var w = TABLE + 5 * nSlots
            nodes.forEachIndexed { i, n ->
                val v = newVals[i]; val vl = v?.size ?: n.valLen
                ob.putInt(w, 15 + n.nameLen + vl); out[w + 4] = 2; out[w + 5] = n.nameLen.toByte(); ob.putInt(w + 6, vl)
                ob.putInt(w + 11, if (n.b != 0) remap.getValue(n.b) else 0)
                System.arraycopy(plain, n.nameOff, out, w + 15, n.nameLen)
                if (v != null) System.arraycopy(v, 0, out, w + 15 + n.nameLen, vl)
                else System.arraycopy(plain, n.valOff, out, w + 15 + n.nameLen, vl)
                w += 15 + n.nameLen + vl
            }
            System.arraycopy(plain, tailOff, out, w, plain.size - tailOff)
            return out
        }

        /** value.split(b'\0') as [start, end) ranges inside plain. */
        private fun splitZ(b: ByteArray, from: Int, to: Int): List<IntRange> {
            val parts = ArrayList<IntRange>(); var s = from
            for (i in from until to) if (b[i].toInt() == 0) { parts += s until i; s = i + 1 }
            parts += s until to
            return parts
        }

        private fun sameAsName(b: ByteArray, part: IntRange, n: Node) =
            part.last - part.first + 1 == n.nameLen && (0 until n.nameLen).all { b[part.first + it] == b[n.nameOff + it] }

        /** UI row node: name = key, value = key\0text\0 */
        fun rows(plain: ByteArray, nodes: List<Node>): List<Pair<String, ByteArray>> = nodes.mapNotNull { n ->
            val p = splitZ(plain, n.valOff, n.valOff + n.valLen)
            if (p.size == 3 && sameAsName(plain, p[0], n) && p[2].isEmpty())
                String(plain, n.nameOff, n.nameLen, Charsets.UTF_8) to plain.copyOfRange(p[1].first, p[1].last + 1)
            else null
        }

        fun translateUi(plain: ByteArray, tr: Map<String, String>): ByteArray {
            val newVals = HashMap<Int, ByteArray>()
            parseNodes(plain).nodes.forEachIndexed { i, n ->
                val t = tr[String(plain, n.nameOff, n.nameLen, Charsets.UTF_8)] ?: return@forEachIndexed
                val p = splitZ(plain, n.valOff, n.valOff + n.valLen)
                if (p.size == 3 && sameAsName(plain, p[0], n)) {
                    val text = t.toByteArray(); check(0.toByte() !in text)
                    newVals[i] = plain.copyOfRange(n.nameOff, n.nameOff + n.nameLen) + Z + text + Z
                }
            }
            return rebuild(plain, newVals)
        }

        /** Story JSON cells ("[{...") : replace each "text_en" string value found in the cache. */
        fun translateStory(plain: ByteArray, cache: Map<String, String>): Pair<ByteArray, Int> {
            val newVals = HashMap<Int, ByteArray>(); var done = 0
            parseNodes(plain).nodes.forEachIndexed { i, n ->
                var changed = false
                val cells = splitZ(plain, n.valOff, n.valOff + n.valLen).map { r ->
                    val cell = plain.copyOfRange(r.first, r.last + 1)
                    if (cell.size < 2 || cell[0] != '['.code.toByte() || cell[1] != '{'.code.toByte()) return@map cell
                    val (s, k) = Json.replaceTextEn(String(cell, Charsets.UTF_8), cache)
                    if (k == 0) cell else { changed = true; done += k; s.toByteArray() }
                }
                if (changed) newVals[i] = cells.reduce { a, b -> a + Z + b }
            }
            return rebuild(plain, newVals) to done
        }

        // --- manifest -------------------------------------------------------------
        fun chunkEntries(m: ByteArray): Map<Pair<Int, Int>, Int> {
            val bb = le(m)
            return (0 until bb.getInt(12)).associate { k ->
                val e = 64 + 32 * k
                (bb.getInt(e) to (bb.getShort(e + 4).toInt() and 0xffff)) to e
            }
        }

        /** Virtual offset where chunk idx starts inside its pack (sum of earlier bodies). */
        fun packBase(m: ByteArray, pack: Int, idx: Int): Long {
            val bb = le(m)
            return chunkEntries(m).entries.filter { it.key.second == pack && it.key.first < idx }.sumOf { bb.getLong(it.value + 8) }
        }

        fun chunkIdx(f: File): Int = RandomAccessFile(f, "r").use { r ->
            val t = ByteArray(16); r.seek(r.length() - 16); r.readFully(t)
            check(String(t, 0, 4, Charsets.ISO_8859_1) == "SSRC") { "${f.name}: no SSRC trailer" }
            le(t).getInt(4)
        }

        fun moveRecord(m: ByteArray, xOld: Long, cszOld: Int, dszOld: Int, xNew: Long, cszNew: Int, dszNew: Int) {
            val old = le(16).putLong(xOld).putInt(cszOld).putInt(dszOld).array()
            val j = indexOf(m, old, 0)
            check(j >= 0 && indexOf(m, old, j + 1) < 0) { "manifest record not found exactly once" }
            le(m).putLong(j, xNew).putInt(j + 8, cszNew).putInt(j + 12, dszNew)
        }

        private fun indexOf(h: ByteArray, n: ByteArray, from: Int): Int {
            outer@ for (i in from..h.size - n.size) {
                for (k in n.indices) if (h[i + k] != n[k]) continue@outer
                return i
            }
            return -1
        }
    }
}

/** Packed data files (.dat), see src/datafile.py: zlib + XOR with a SHA-256 keystream. */
object Dat {
    private val KEY = "czn-id-patch-v1".toByteArray()

    fun load(f: File): Map<String, String> {
        val data = f.readBytes()
        check(String(data, 0, 4, Charsets.ISO_8859_1) == "CZN1") { "${f.name}: not a packed data file" }
        val plain = ByteArray(data.size - 4)
        val sha = MessageDigest.getInstance("SHA-256")
        var i = 0L; var p = 0
        while (p < plain.size) {
            sha.update(KEY); sha.update(ByteBuffer.allocate(8).putLong(i++).array())
            for (b in sha.digest()) { if (p == plain.size) break; plain[p] = (data[p + 4].toInt() xor b.toInt()).toByte(); p++ }
        }
        val o = JSONObject(InflaterInputStream(plain.inputStream()).readBytes().toString(Charsets.UTF_8))
        val map = HashMap<String, String>(o.length() * 2)
        for (k in o.keys()) map[k] = o.getString(k)
        return map
    }
}

/** Just enough JSON string handling to swap "text_en" values without touching the rest of a cell. */
object Json {
    private fun strEnd(s: String, start: Int): Int {        // index just past the closing quote
        var i = start + 1
        while (i < s.length) { when (s[i]) { '\\' -> i += 2; '"' -> return i + 1; else -> i++ } }
        error("unterminated JSON string")
    }

    fun unescape(s: String): String {
        val b = StringBuilder(s.length); var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c != '\\') { b.append(c); continue }
            when (val e = s[i++]) {
                'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b')
                'f' -> b.append('\u000c'); 'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                else -> b.append(e)                            // \" \\ \/
            }
        }
        return b.toString()
    }

    /** Same escaping as Python's json.dumps(ensure_ascii=False). */
    fun escape(s: String): String {
        val b = StringBuilder(s.length + 2).append('"')
        for (c in s) when {
            c == '"' -> b.append("\\\""); c == '\\' -> b.append("\\\\"); c == '\n' -> b.append("\\n")
            c == '\r' -> b.append("\\r"); c == '\t' -> b.append("\\t"); c == '\b' -> b.append("\\b")
            c == '\u000c' -> b.append("\\f"); c < ' ' -> b.append("\\u%04x".format(c.code))
            else -> b.append(c)
        }
        return b.append('"').toString()
    }

    fun replaceTextEn(s: String, cache: Map<String, String>): Pair<String, Int> {
        val out = StringBuilder(); var last = 0; var n = 0; var i = 0
        while (i < s.length) {
            if (s[i] != '"') { i++; continue }
            val end = strEnd(s, i)
            if (end - i == 9 && s.startsWith("\"text_en\"", i)) {
                var j = end
                while (j < s.length && s[j].isWhitespace()) j++
                if (j < s.length && s[j] == ':') {
                    j++
                    while (j < s.length && s[j].isWhitespace()) j++
                    if (j < s.length && s[j] == '"') {
                        val vEnd = strEnd(s, j)
                        val t = unescape(s.substring(j + 1, vEnd - 1)).takeIf { it.isNotEmpty() }?.let { cache[it] }
                        if (t != null) { out.append(s, last, j).append(escape(t)); last = vEnd; n++ }
                        i = vEnd; continue
                    }
                }
            }
            i = end
        }
        return (if (n == 0) s else out.append(s, last, s.length).toString()) to n
    }
}
