package id.czn.patch

import java.io.File
import kotlin.system.exitProcess

/** Runs inside Shizuku's process (shell uid), which may read and write the game's Android/data folder. */
class PatchService : IPatchService.Stub() {
    override fun destroy() = exitProcess(0)

    override fun run(cmd: String, dataDir: String, log: ILog): String = try {
        val p = Patcher(GAMERES, log::line)
        val data = File(dataDir)
        if (cmd != "version" && !File(GAMERES, Patcher.ETAG).exists())
            error("Folder game tidak ditemukan. Jalankan game sekali sampai selesai mengunduh data, lalu coba lagi.")
        when (cmd) {
            "version" -> if (File(GAMERES, Patcher.ETAG).exists()) p.gameVersion() ?: "" else ""
            "apply" -> { forceStop(log); p.apply(data); "" }
            "restore" -> { forceStop(log); p.restore(); "" }
            "check" -> {
                log.line("Versi game di HP : ${p.gameVersion()}")
                log.line("Cadangan asli    : ${p.backupVersion()?.let { "ada (versi $it)" } ?: "tidak ada"}")
                if (dataDir.isNotEmpty()) log.line("Status teks UI   : " + if (p.looksPatched(data)) "sudah bahasa Indonesia" else "bahasa Inggris (asli)")
                ""
            }
            else -> error("unknown command $cmd")
        }
    } catch (e: Throwable) {
        "!" + (e.message ?: e.toString())
    }

    private fun forceStop(log: ILog) {
        Runtime.getRuntime().exec(arrayOf("am", "force-stop", Patcher.PKG)).waitFor()
        log.line("Game dihentikan supaya berkasnya tidak sedang dipakai.")
    }

    companion object {
        val GAMERES = File("/storage/emulated/0/Android/data/${Patcher.PKG}/files/gameres")
    }
}
