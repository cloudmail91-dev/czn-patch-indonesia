# Chaos Zero Nightmare – Patch Bahasa Indonesia

Patch yang mengubah teks game Chaos Zero Nightmare (menu, UI, dan dialog cerita) dari bahasa Inggris ke
bahasa Indonesia. Bisa dipakai di **PC** dan **Android**.

> Fan project, tidak berafiliasi dengan Smilegate/Stove. Terjemahan dibuat dengan AI dan belum dikoreksi
> manusia, jadi masih ada kalimat yang kaku atau salah.

## Sebelum mulai

- **Pakai dengan risiko sendiri.** Patch ini hanya mengganti berkas teks game (tidak menyentuh anti-cheat),
  tapi tetap memodifikasi berkas game. Pembuat patch tidak bertanggung jawab atas banned atau kerusakan.
- **Versi game yang didukung: 1.0.84103.** Kalau game sudah update ke versi lain, lihat
  [Setelah game update](#setelah-game-update).
- Sebagian kecil teks memang tetap bahasa Inggris (misalnya layar "TAP TO START").

## Unduh

Buka halaman [Releases](https://github.com/cloudmail91-dev/czn-patch-indonesia/releases/latest) lalu unduh:

| Kamu main di | Unduh |
|---|---|
| PC | `CZN_Patch_PC.zip` |
| Android, tanpa PC | `CZN_Patch_Android.apk` |
| Android, lewat PC + kabel USB | `CZN_Patch_PC.zip` |

Untuk PC, ekstrak zip ke folder mana saja. Jangan pindahkan `CZN_Patch.exe` keluar dari folder itu
(exe harus tetap di sebelah folder `data`).

---

## PC

### Pasang

1. Tutup game dan launcher.
2. Klik dua kali `CZN_Patch.exe`, ketik `1`, lalu pilih folder game (folder yang berisi `bin`).
3. Tunggu sampai selesai, lalu buka game.

Berkas asli otomatis dicadangkan, jadi bisa dikembalikan kapan saja.

### Kembalikan ke bahasa Inggris

Klik dua kali `CZN_Patch.exe`, ketik `2`, lalu pilih folder game yang sama.

---

## Android tanpa PC (APK)

Butuh aplikasi [Shizuku](https://github.com/RikkaApps/Shizuku/releases) (juga ada di Play Store). Tidak perlu root.

1. Mainkan game minimal sekali sampai unduhan data selesai, lalu tutup game.
2. Pasang dan nyalakan Shizuku lewat **Wireless debugging**. Kalau bingung, buka aplikasi patch lalu tekan
   **Panduan pemakaian**, di sana ada langkah lengkapnya.
3. Pasang `CZN_Patch_Android.apk`, buka, tekan **Pasang terjemahan**, lalu izinkan di dialog Shizuku.
4. Buka game.

Untuk mengembalikan ke bahasa Inggris, tekan **Kembalikan ke bahasa Inggris** di aplikasi.

## Android lewat PC (kabel USB)

1. Mainkan game minimal sekali di HP sampai unduhan data selesai, lalu tutup game.
2. Di HP: Pengaturan > Tentang ponsel > ketuk **Nomor bentukan** 7 kali untuk membuka Opsi Pengembang,
   lalu aktifkan **USB debugging**.
3. Sambungkan HP ke PC dengan kabel, lalu tekan **Izinkan** pada dialog "Allow USB debugging" di HP.
4. Klik dua kali `CZN_Patch.exe`, ketik `3`, lalu tunggu sampai selesai.
5. Buka game di HP.

Menu lain di `CZN_Patch.exe` untuk Android:
- `4` – kembalikan HP ke bahasa Inggris
- `5` – cek status (versi game dan keadaan berkas). Sertakan hasilnya kalau melaporkan masalah.

---

## Setelah game update

Setelah game update, terjemahan biasanya hilang atau patch menolak dengan pesan **"Versi game tidak cocok"**.

- **PC / Android lewat PC:** jalankan `update.bat` untuk mengunduh patch dan terjemahan terbaru, lalu pasang lagi.
- **APK:** tekan **Pasang terjemahan** lagi. Aplikasi otomatis mengambil data terbaru.

Kalau masih "Versi game tidak cocok", berarti patch untuk versi game terbaru belum tersedia. Tunggu rilis berikutnya.

## Masalah umum

- **Teks masih bahasa Inggris.** Pastikan game benar-benar tertutup saat memasang, lalu pasang ulang.
  Di Android lewat PC, jalankan cek status (`5`) untuk melihat berkas mana yang belum terpasang.
- **Game error atau minta unduh ulang data.** Kembalikan ke bahasa Inggris (di PC bisa juga verifikasi berkas
  lewat launcher), jalankan `update.bat`, lalu pasang lagi.
- **HP tidak terdeteksi di PC.** Cabut lalu pasang lagi kabelnya, pastikan USB debugging aktif dan dialog izin
  sudah diterima. Coba kabel lain kalau perlu (sebagian kabel hanya untuk mengisi daya).

Laporkan masalah lewat [Issues](https://github.com/cloudmail91-dev/czn-patch-indonesia/issues).

## Ikut memperbaiki terjemahan

Ada terjemahan yang salah? Buka `CZN_Editor.exe` (ada di dalam `CZN_Patch_PC.zip`):

1. Pilih tab **UI** atau **Story**, lalu cari teksnya (bisa cari kata Inggris atau Indonesia).
2. Klik sel bahasa Indonesia untuk mengubahnya (Enter = terapkan, Shift+Enter = baris baru, Esc = batal).
3. Tekan **Ctrl+S** untuk menyimpan, atau **Apply ke game** untuk menyimpan sekaligus memasangnya ke game.

Jangan ubah kode khusus di dalam teks, seperti `<br>`, `<cc>..</>`, `{0}`, `$Istilah$`, `#nama#`, `[fc]..[/]`,
dan `|`. Kode yang berubah bisa merusak tampilan atau membuat game error (baris seperti ini ditandai merah).

Kirim perbaikanmu lewat Issues atau Pull Request di GitHub.

---

## English

Unofficial Indonesian translation patch for **Chaos Zero Nightmare** (PC and Android, game version 1.0.84103).
Machine-translated and unreviewed. Modifies game resource files only (no anti-cheat bypass), but use at your own risk.

- **PC:** extract `CZN_Patch_PC.zip`, run `CZN_Patch.exe`, choose `1`, select the game folder (the one containing `bin`).
  Choose `2` to restore.
- **Android (no PC):** install `CZN_Patch_Android.apk` with [Shizuku](https://github.com/RikkaApps/Shizuku/releases)
  running, then tap **Pasang terjemahan**.
- **Android (via PC):** enable USB debugging, connect the phone, run `CZN_Patch.exe` and choose `3`.
- **After a game update:** run `update.bat` (or tap **Pasang terjemahan** again in the APK), then patch again.
