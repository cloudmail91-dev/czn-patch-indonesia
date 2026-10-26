package id.czn.patch

import com.github.luben.zstd.util.Native
import java.io.File
import kotlin.system.exitProcess

/**
 * Entry point run through Shizuku as `app_process` (shell uid), which may read and write the game's Android/data
 * folder. Shizuku's UserService is not used: it crashes inside LoadedApk.makeApplication on HyperOS.
 * args: <nativeLibDir> <cmd> [dataDir]. Output: log lines; "=value" is the result, "!message" an error.
 */
object Cli {
    val GAMERES = File("/storage/emulated/0/Android/data/${Patcher.PKG}/files/gameres")

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            // no LoadedApk here, so load zstd-jni from the app's extracted native libs by path
            val lib = File(args[0]).listFiles()?.firstOrNull { it.name.startsWith("libzstd-jni") }
                ?: error("libzstd-jni tidak ditemukan di ${args[0]}")
            System.load(lib.path); Native.assumeLoaded()
            println("=" + run(args[1], args.getOrElse(2) { "" }))
        } catch (e: Throwable) {
            println("!" + (e.message ?: e.toString()))
            System.out.flush(); exitProcess(1)
        }
        System.out.flush(); exitProcess(0)
    }

    private fun log(s: String) { println(s); System.out.flush() }

    private fun run(cmd: String, dataDir: String): String {
        val p = Patcher(GAMERES, ::log)
        val data = File(dataDir)
        val found = File(GAMERES, Patcher.ETAG).exists()
        if (cmd == "version") return if (found) p.gameVersion() ?: "" else ""
        if (!found) error("Folder game tidak ditemukan. Jalankan game sekali sampai selesai mengunduh data, lalu coba lagi.")
        when (cmd) {
            "apply" -> { forceStop(); p.apply(data) }
            "restore" -> { forceStop(); p.restore() }
            "check" -> {
                log("Versi game di HP : ${p.gameVersion()}")
                log("Cadangan asli    : ${p.backupVersion()?.let { "ada (versi $it)" } ?: "tidak ada"}")
                if (dataDir.isNotEmpty()) log("Status teks UI   : " + if (p.looksPatched(data)) "sudah bahasa Indonesia" else "bahasa Inggris (asli)")
            }
            else -> error("unknown command $cmd")
        }
        return ""
    }

    private fun forceStop() {
        Runtime.getRuntime().exec(arrayOf("am", "force-stop", Patcher.PKG)).waitFor()
        log("Game dihentikan supaya berkasnya tidak sedang dipakai.")
    }
}
