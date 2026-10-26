package id.czn.patch

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private val buttons = ArrayList<Button>()
    private var busy = false

    @Volatile private var svc: IPatchService? = null
    private var connected = CountDownLatch(1)
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            svc = IPatchService.Stub.asInterface(binder); connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) { svc = null }
    }
    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, PatchService::class.java.name))
            .daemon(false).processNameSuffix("patch").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
    }
    private val logger = object : ILog.Stub() { override fun line(s: String) = log(s) }
    private val onBinder = Shizuku.OnBinderReceivedListener { runOnUiThread { refreshStatus() } }
    private val onPermission = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refreshStatus() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(TextView(this).apply { text = "Chaos Zero Nightmare\nPatch Bahasa Indonesia"; textSize = 20f; setTypeface(null, Typeface.BOLD) })
        status = TextView(this).apply { setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(status)
        button(root, "Panduan pemakaian") { showGuide() }
        button(root, "Pasang terjemahan") { withService(::install) }
        button(root, "Kembalikan ke bahasa Inggris") { withService(::restore) }
        button(root, "Cek status") { withService(::check) }
        button(root, "Ambil data terbaru dari GitHub") { background { fetchGithub(); log("Data GitHub: versi ${versionOf(githubDir())}") } }
        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true) }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = pad / 2 })
        setContentView(root)

        Shizuku.addBinderReceivedListenerSticky(onBinder)
        Shizuku.addRequestPermissionResultListener(onPermission)
        refreshStatus()
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (!prefs.getBoolean("guide_seen", false)) { showGuide(); prefs.edit().putBoolean("guide_seen", true).apply() }
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(onBinder)
        Shizuku.removeRequestPermissionResultListener(onPermission)
        if (svc != null) runCatching { Shizuku.unbindUserService(args, conn, true) }
        super.onDestroy()
    }

    private fun button(root: LinearLayout, label: String, onClick: () -> Unit) {
        val b = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { onClick() } }
        buttons += b; root.addView(b)
    }

    // --- Shizuku ----------------------------------------------------------------
    /** null when Shizuku is running and we hold its permission, else what the user must do. */
    private fun shizukuProblem(): String? = when {
        !Shizuku.pingBinder() -> "Shizuku belum aktif. Buka aplikasi Shizuku lalu tekan Start (lihat Panduan)."
        Shizuku.isPreV11() -> "Versi Shizuku terlalu lama. Perbarui aplikasi Shizuku."
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "Aplikasi ini belum diizinkan di Shizuku."
        else -> null
    }

    private fun refreshStatus() {
        val p = shizukuProblem()
        status.text = p ?: "Shizuku aktif dan sudah diizinkan. Siap dipakai."
        status.setTextColor(if (p == null) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt())
    }

    private fun withService(job: (IPatchService) -> Unit) {
        val p = shizukuProblem()
        if (p != null) {
            refreshStatus()
            if (Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                log("Izinkan aplikasi ini di dialog Shizuku, lalu tekan tombolnya lagi.")
                Shizuku.requestPermission(1)
            } else log(p)
            return
        }
        if (svc == null) { connected = CountDownLatch(1); Shizuku.bindUserService(args, conn) }
        val latch = connected
        background {
            val s = svc ?: run { latch.await(30, TimeUnit.SECONDS); svc } ?: error("Gagal menyambung ke Shizuku. Coba Stop lalu Start lagi di aplikasi Shizuku.")
            job(s)
        }
    }

    private fun call(s: IPatchService, cmd: String, dataDir: String = ""): String =
        s.run(cmd, dataDir, logger).also { if (it.startsWith("!")) error(it.drop(1)) }

    // --- actions (background thread) -------------------------------------------
    private fun install(s: IPatchService) {
        val game = gameVersion(s)
        val data = dataFor(game, download = true)!!
        log("Memasang terjemahan (data versi ${versionOf(data)}). Tunggu 1-3 menit, jangan tutup aplikasi...")
        call(s, "apply", data.path)
        log("\nSELESAI. Silakan buka game.")
    }

    private fun restore(s: IPatchService) {
        log("Mengembalikan berkas asli...")
        call(s, "restore")
        log("\nSELESAI. Game kembali ke bahasa Inggris.")
    }

    private fun check(s: IPatchService) {
        val game = call(s, "version")
        log("Data bawaan aplikasi: versi ${versionOf(bundledDir())}")
        log("Data dari GitHub    : versi ${versionOf(githubDir()) ?: "(belum diunduh)"}")
        if (game.isEmpty()) { log("Folder game tidak ditemukan di HP. Jalankan game sekali dulu."); return }
        call(s, "check", dataFor(game, download = false)?.path ?: "")
    }

    private fun gameVersion(s: IPatchService) = call(s, "version").ifEmpty {
        error("Folder game tidak ditemukan. Jalankan game sekali sampai selesai mengunduh data, tutup, lalu coba lagi.")
    }.also { log("Versi game di HP: $it") }

    // --- translation data: bundled in the APK, or newer from GitHub --------------
    private fun bundledDir() = File(getExternalFilesDir(null), "bundled").also { dir ->
        dir.mkdirs()   // copied out every time so the Shizuku process (shell uid) can read it
        for (n in DATA_FILES) assets.open(n).use { inp -> File(dir, n).outputStream().use { inp.copyTo(it) } }
    }

    private fun githubDir() = File(getExternalFilesDir(null), "github")

    private fun versionOf(dir: File): String? =
        runCatching { JSONObject(File(dir, "version.json").readText()).getString("version") }.getOrNull()

    private fun dataFor(game: String, download: Boolean): File? {
        val bundled = bundledDir()
        if (versionOf(bundled) == game) return bundled
        if (versionOf(githubDir()) == game) return githubDir()
        if (!download) return null
        log("Data bawaan untuk versi ${versionOf(bundled)}, game kamu $game. Mengambil data terbaru dari GitHub...")
        fetchGithub()
        val got = versionOf(githubDir())
        if (got == game) return githubDir()
        error("Data terjemahan untuk versi $game belum tersedia (di GitHub masih versi $got). Tunggu pembaruan patch, lalu coba lagi.")
    }

    /** Download all data files first, then swap them in together so a half download never mixes versions. */
    private fun fetchGithub() {
        val dir = githubDir().apply { mkdirs() }
        for (n in DATA_FILES) {
            val c = URL("$RAW/$n").openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 60000
            try {
                if (c.responseCode != 200) error("Gagal mengunduh $n dari GitHub (HTTP ${c.responseCode}).")
                c.inputStream.use { inp -> File(dir, "$n.part").outputStream().use { inp.copyTo(it) } }
            } finally { c.disconnect() }
            log("  unduh $n: ${File(dir, "$n.part").length()} byte")
        }
        JSONObject(File(dir, "version.json.part").readText()).getString("version")   // throws on a broken download
        check(File(dir, "cache.dat.part").readBytes().copyOf(4).contentEquals("CZN1".toByteArray())) { "cache.dat dari GitHub rusak." }
        for (n in DATA_FILES) check(File(dir, "$n.part").renameTo(File(dir, n).also { it.delete() })) { "Gagal menyimpan $n" }
    }

    // --- ui helpers ---------------------------------------------------------------
    private fun background(job: () -> Unit) {
        if (busy) return
        busy = true; buttons.forEach { it.isEnabled = false }; logView.text = ""
        thread {
            try { job() } catch (e: Throwable) { log("\nGAGAL: ${e.message ?: e}") }
            finally { runOnUiThread { busy = false; buttons.forEach { it.isEnabled = true }; refreshStatus() } }
        }
    }

    private fun log(s: String) = runOnUiThread {
        logView.append(s + "\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showGuide() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply { text = GUIDE; setPadding(pad, pad, pad, pad); setTextIsSelectable(true) }
        AlertDialog.Builder(this).setTitle("Panduan").setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("Mengerti", null).show()
    }

    companion object {
        private const val REPO = "cloudmail91-dev/czn-patch-indonesia"     // same repo as update.bat
        private const val RAW = "https://raw.githubusercontent.com/$REPO/main/data"
        private val DATA_FILES = listOf("version.json", "story_files.json", "cache.dat")

        private val GUIDE = """
PERSIAPAN (sekali saja)
1. Pasang aplikasi Shizuku dari Play Store (atau github.com/RikkaApps/Shizuku/releases).
2. Buka Opsi Pengembang: Pengaturan > Tentang ponsel > ketuk "Nomor bentukan" (Build number) 7 kali.
3. Sambungkan HP ke Wi-Fi, lalu buka Pengaturan > Opsi pengembang dan aktifkan "Debugging nirkabel" (Wireless debugging).
4. Buka Shizuku > "Mulai melalui Debugging nirkabel" > Pairing. Ikuti petunjuknya: buka "Sambungkan perangkat dengan kode penyambungan" di menu Debugging nirkabel, lalu ketik kodenya di notifikasi Shizuku.
5. Kembali ke Shizuku dan tekan Start. Tunggu sampai tertulis "Shizuku sedang berjalan".
   Punya root? Cukup buka Shizuku dan pilih Start dengan root.
6. Buka aplikasi ini, tekan "Pasang terjemahan", lalu pilih Izinkan di dialog Shizuku.

PENTING: setiap HP restart, Shizuku ikut mati. Nyalakan Debugging nirkabel, buka Shizuku, tekan Start lagi. Pairing tidak perlu diulang.

MEMASANG
1. Jalankan game minimal sekali sampai selesai mengunduh data, lalu tutup game.
2. Tekan "Pasang terjemahan" dan tunggu sampai muncul SELESAI (1-3 menit). Game dihentikan otomatis selama proses.
3. Buka game.

SETELAH GAME UPDATE
Game mengunduh berkas baru, jadi teks kembali ke bahasa Inggris. Tekan "Pasang terjemahan" lagi.
Kalau data di aplikasi tidak cocok dengan versi game, aplikasi otomatis mengambil data terbaru dari GitHub. Kalau di GitHub juga belum ada, berarti patch untuk versi itu belum dibuat. Tunggu dulu.

MENGEMBALIKAN KE BAHASA INGGRIS
Tekan "Kembalikan ke bahasa Inggris". Berkas asli disimpan di folder gameres/czn_id_backup di HP.

KALAU GAGAL
Tekan "Cek status", lalu salin isi log (tekan lama pada teks) dan kirim saat lapor masalah.

PERINGATAN
Fan project, tidak berafiliasi dengan Smilegate/Stove. Patch ini mengubah berkas resource game (tanpa injeksi atau hook), tapi tetap modifikasi: risiko akun ditanggung sendiri. Terjemahan dibuat dengan AI dan belum dikoreksi manusia.
""".trimIndent()
    }
}
