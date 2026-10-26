# Chaos Zero Nightmare – Patch Bahasa Indonesia (PC & Android)

Patch terjemahan Indonesia untuk Chaos Zero Nightmare versi PC **dan Android**.
Mengganti teks bahasa Inggris dengan bahasa Indonesia: UI/menu dan dialog cerita.

> Fan project, tidak berafiliasi dengan Smilegate/Stove. Terjemahan dibuat dengan model AI (Claude Haiku) dan
> belum dikoreksi manusia, jadi masih ada kalimat yang kaku atau salah. Koreksi sangat diterima.

## Peringatan

- **Risiko akun.** Game ini punya anti-cheat. Patch ini hanya mengubah berkas resource game
  (tidak menyentuh anti-cheat, tidak memakai injeksi/hook), tapi tetap memodifikasi berkas game.
  Pakai dengan risiko sendiri. Penulis tidak bertanggung jawab atas banned atau kerusakan.
- **Hanya versi PC dan Android, patch resource 1.0.84102.** Setelah game update, patch bisa berhenti bekerja atau tertimpa.
- **Android didukung** lewat kabel USB (atau wireless debugging) dan `adb` yang sudah disertakan. Tidak perlu root.
  Permintaan pertamanya: aktifkan Opsi Pengembang > USB debugging di HP. Setelah game update, jalankan menu Android lagi.
- Teks yang tidak ada di tabel terjemahan tetap bahasa Inggris (termasuk layar "TAP TO START").

## Isi

```
CZN_Patch.exe        alat patch: pasang / kembalikan (PC dan Android)
CZN_Editor.exe       editor terjemahan (tab UI dan Story) + tombol Apply ke game
CZN_Patch_Android.apk aplikasi Android: pasang / kembalikan langsung di HP lewat Shizuku (tanpa PC)
update.bat           unduh versi terbaru dari GitHub
platform-tools/      adb (Google) untuk mode Android
data/
  cache.dat          terjemahan {Inggris: Indonesia} (terkompresi dan diacak)
  sets.dat           daftar teks UI dan Story untuk editor
  story_files.json   lokasi berkas cerita di resource game
  version.json       versi game dan lokasi tabel teks (dibuat oleh scan.py)
  glossary.txt       istilah yang dipertahankan/ditetapkan
src/                 kode Python dari kedua exe, plus scan.py dan android.py
android_app/         kode sumber APK (Kotlin); build: android_app\gradlew assembleRelease
```

Tidak ada berkas game yang disertakan. Alat ini membaca berkas milik kamu sendiri — di PC maupun di HP.

## Cara pakai

Biarkan susunan folder seperti di atas (exe harus berada di sebelah folder `data`).
`cache.dat` dan `sets.dat` sudah ada di dalam exe, jadi boleh tidak ada di `data/`. Kalau ada, berkas di `data/`
yang dipakai (misalnya hasil simpan editor).

1. Tutup game dan launcher. Pastikan game versi 1.0.83709 dan berkasnya utuh
   (verifikasi berkas lewat launcher kalau perlu).
2. Klik dua kali `CZN_Patch.exe`, ketik `1`, lalu pilih folder game (yang berisi `bin`).
   Berkas asli dicadangkan otomatis ke `...\gameres\czn_id_backup`.
   Lewat terminal: `CZN_Patch.exe apply "D:\Games\ChaosZeroNightmare"`.
3. Buka game.

### Mengembalikan ke asli

Klik dua kali `CZN_Patch.exe` lalu ketik `2`, atau lewat terminal:
`CZN_Patch.exe restore "D:\Games\ChaosZeroNightmare"`.

## Android tanpa PC (APK + Shizuku)

1. Pasang [Shizuku](https://github.com/RikkaApps/Shizuku/releases) (juga ada di Play Store) dan nyalakan lewat
   Wireless debugging (atau root). Panduan lengkapnya ada di dalam aplikasi (tombol **Panduan pemakaian**).
2. Pasang `CZN_Patch_Android.apk`, buka, tekan **Pasang terjemahan**, lalu izinkan di dialog Shizuku.
3. Setelah game update, tekan **Pasang terjemahan** lagi. Kalau data di APK tidak cocok dengan versi game, aplikasi
   otomatis mengunduh `data/version.json`, `data/story_files.json`, dan `data/cache.dat` dari branch `main` repo GitHub
   (sama dengan `update.bat`). Jadi cukup push data baru ke GitHub, APK tidak perlu di-build ulang.

Cadangan asli disimpan di HP (`gameres/czn_id_backup`), sama seperti alat PC, jadi kedua cara bisa dipakai bergantian.

## Android (lewat kabel USB atau wireless debugging)

1. Di HP: Pengaturan > Tentang ponsel > ketuk "Nomor bentukan" 7x untuk membuka Opsi Pengembang,
   lalu aktifkan **USB debugging** (kalau tanpa kabel: aktifkan juga **Wireless debugging** dan pairing).
2. Sambungkan kabel ke komputer, lalu terima dialog "Allow USB debugging" di layar HP.
3. Klik dua kali `CZN_Patch.exe` dan pilih **3** (pasang patch ke HP). Alat ini akan:
   membaca versi game di HP → menyalin berkas yang perlu → menerjemahkan di komputer →
   mengirim balik → memeriksa ulang setiap berkas.
   Cadangan asli otomatis disimpan di HP (`gameres\czn_id_backup`) dan di komputer (`android_backup\<versi>`).
4. Buka game. Kalau teksnya masih Inggris, jalankan **5** (cek status) lalu kirim hasilnya saat lapor masalah.

Lewat terminal:

```
CZN_Patch.exe android           pasang patch ke HP
CZN_Patch.exe android-restore   kembalikan HP ke bahasa Inggris
CZN_Patch.exe android-check     tampilkan versi game di HP dan keadaan berkas
```

Catatan: game harus sudah pernah dijalankan minimal sekali di HP (berkasnya diunduh saat pertama main),
dan HP harus versi game yang sama dengan `data\version.json` — kalau tidak sama, alat akan menolak dan
menyuruh kamu menjalankan `update.bat`.

### Dengan Python (tanpa exe)

```
pip install -r src/requirements.txt
python src/patch.py apply "D:\Games\ChaosZeroNightmare"
python src/editor.py
```

## Setelah game update

1. Verifikasi berkas lewat launcher, lalu hapus folder `czn_id_backup` di `...\gameres` (PC) dan
   `...\Android\data\...\files\gameres\czn_id_backup` (Android).
2. Jalankan `update.bat`. Kalau versi baru sudah didukung, `data/version.json` ikut diperbarui; lalu pasang lagi
   (PC maupun HP).
3. Kalau belum didukung, `CZN_Patch.exe` menolak dengan pesan "Versi game tidak cocok". Pengelola memperbaruinya dengan
   `python src/scan.py "<folder game>"`, lalu menerjemahkan teks baru.

## Berkontribusi

Perbaiki terjemahan dengan `CZN_Editor.exe`. Ada tab **UI** dan **Story**, tabel Inggris/Indonesia yang bisa dicari
(Inggris, Indonesia, atau keduanya), klik sel Indonesia untuk mengubah (Enter = terapkan, Shift+Enter = baris baru,
Esc = batal), lalu **Ctrl+S** untuk menyimpan ke `data/cache.dat`. Baris dengan tag yang berbeda dari aslinya diberi warna merah.
Tombol **Apply ke game** menyimpan lalu langsung memasang terjemahan ke game.
Pertahankan tag/placeholder persis seperti aslinya: `<br>`, `<cc>..</>`, `{0}`, `$Istilah$`, `#nama#`, `[fc]..[/]`, dan `|`.
Tag yang berubah bisa merusak tampilan atau membuat game error.

---

## English

Unofficial fan Indonesian translation patch for **Chaos Zero Nightmare (PC, resource patch 1.0.83709)**.
`CZN_Patch.exe apply "<game folder>"` rewrites the English UI and story text tables with the Indonesian ones in
`cache.dat`, backs up the originals, and fixes the manifest hash chain. `restore` undoes it. It only modifies resource
files (no anti-cheat bypass), but modifying game files may still risk your account. Use at your own risk. No game files
are included. Android is not supported (the game re-downloads modified files).
Translations are machine-generated (Claude Haiku 5.5) and unreviewed.
