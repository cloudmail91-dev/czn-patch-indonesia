"""Android support for the patch tool: apply / restore / check the phone's install over adb.

usage:  CZN_Patch.exe android            pull the phone's files, patch them here, push them back
        CZN_Patch.exe android-restore    put the original files back
        CZN_Patch.exe android-check      show the device's game version and patch state

Everything is computed from the phone's own files, so nothing about the game is shipped with the tool.
Text inside the story chunks is fetched as byte ranges (tail/head on the device), never as whole
chunks, because those chunks are ~200 MB each.
"""
import hashlib, json, os, re, shutil, subprocess, sys, tempfile

import zstandard as zstd

import datafile, patch

PKG = 'com.smilegate.chaoszero.stove.google'
REMOTE = '/sdcard/Android/data/%s/files/gameres' % PKG
DEVICE_BACKUP = 'czn_id_backup'          # inside gameres, like the PC patcher's backup folder
FILES = lambda v: [(v['chunk'], 'chunks'), (v['story_tail'], 'chunks'), ('manifest.ssra', ''), ('manifest.ssra.etag', '')]


def adb_path():
    here = datafile.ROOT
    for c in (os.path.join(here, 'platform-tools', 'adb.exe'), os.path.join(here, 'adb.exe'), shutil.which('adb')):
        if c and os.path.exists(c):
            return c
    return 'adb'                          # last resort: whatever is on PATH


ADB = None


def run(args, binary=False, check=False):
    """adb wrapper. check=False by default because 'pidof'/'test -f' legitimately exit non-zero."""
    r = subprocess.run([ADB] + args, capture_output=True,
                       creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    if check and r.returncode:
        sys.exit('adb gagal (%s):\n%s' % (' '.join(args), r.stderr.decode('utf-8', 'replace').strip()))
    return r.stdout if binary else r.stdout.decode('utf-8', 'replace')


def shell(serial, cmd, binary=False):
    return run(['-s', serial, 'exec-out' if binary else 'shell', cmd], binary=binary)


def devices():
    out = run(['devices'], check=True)
    found = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == 'device':
            found.append(parts[0])
        elif len(parts) >= 2 and parts[1] == 'unauthorized':
            sys.exit(f'HP {parts[0]} belum mengizinkan USB debugging. Terima dialog "Allow USB debugging" di layar HP, lalu jalankan lagi.')
    return found


def pick_device():
    found = devices()
    if not found:
        sys.exit('Tidak ada HP terdeteksi.\n'
                 '  1. Aktifkan Opsi Pengembang > USB debugging (dan "Wireless debugging" kalau tanpa kabel).\n'
                 '  2. Sambungkan kabel / pairing nirkabel, terima dialog di layar HP.\n'
                 '  3. Cek dengan perintah: CZN_Patch.exe android-check')
    if len(found) > 1:
        print('Beberapa perangkat terdeteksi:', ', '.join(found))
        choice = input('Ketik nomor serial yang mau dipakai: ').strip()
        if choice not in found:
            sys.exit('Bukan salah satu dari daftar.')
        return choice
    return found[0]


def remote_version(serial):
    raw = shell(serial, 'cat %s/manifest.ssra.etag' % REMOTE).replace('\r\n', '\n')
    if not raw.strip():
        sys.exit(f'{REMOTE} tidak ditemukan di HP.\n'
                 'Pastikan game Chaos Zero Nightmare sudah pernah dijalankan minimal sekali di HP ini.')
    m = re.search(r'/patch/([\d.]+)/', raw)
    if not m:
        sys.exit('Tidak bisa membaca versi dari manifest.ssra.etag:\n' + raw[:200])
    return m.group(1).strip('.')


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''):
            h.update(b)
    return h.hexdigest()


def remote_sha(serial, rel):
    out = shell(serial, 'sha256sum %s/%s' % (REMOTE, rel))
    return out.split()[0] if out.split() else ''


def force_stop(serial):
    pid = shell(serial, 'pidof %s' % PKG).strip()
    if pid:
        shell(serial, 'am force-stop %s' % PKG)
        print('  game dihentikan (force-stop) supaya berkasnya tidak sedang dipakai')
    return bool(pid)


def pull_file(serial, rel, dest):
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    data = shell(serial, 'cat %s/%s' % (REMOTE, rel), binary=True)
    if not data:
        sys.exit('Gagal membaca %s/%s dari HP (kosong).' % (REMOTE, rel))
    with open(dest, 'wb') as f:
        f.write(data)
    return len(data)


def push_file(serial, rel, src):
    return run(['-s', serial, 'push', src, '%s/%s' % (REMOTE, rel)], check=True)


def fetch_range(serial, rel, off, size):
    """Read one byte range out of a big chunk on the device (tail skips, head limits)."""
    cmd = "tail -c +%d '%s/%s' | head -c %d" % (off + 1, REMOTE, rel, size)
    data = shell(serial, cmd, binary=True)
    if len(data) != size:
        sys.exit('Gagal mengambil %s @%d+%d dari HP (dapat %d byte).' % (rel, off, size, len(data)))
    return data


def story_provider(serial, cache):
    """patch.read_frame() hook: it must return the DECOMPRESSED frame, so decompress here."""
    def provide(name, off, csz):
        key = (name, off, csz)
        if key not in cache:
            cache[key] = zstd.ZstdDecompressor().decompress(fetch_range(serial, 'chunks/' + name, off, csz))
        return cache[key]
    return provide


def stage_from_device(serial, work, v):
    """Build a folder that the normal patcher can run on: pull the four files and stub the story chunks."""
    os.makedirs(os.path.join(work, 'chunks'), exist_ok=True)
    for name, sub in FILES(v):
        rel = (sub + '/' + name) if sub else name
        size = pull_file(serial, rel, os.path.join(work, sub, name))
        print('  ambil %-34s %10d byte' % (rel, size))

    # story chunks: pull only the 16-byte SSRC trailer (for the index) and the frames themselves
    cache = {}
    trailer_seen = set()
    story_list = json.load(open(os.path.join(datafile.DATA, 'story_files.json')))
    for f in story_list:
        stub = os.path.join(work, 'chunks', f['chunk'])
        if f['chunk'] not in trailer_seen:
            size_remote = int(shell(serial, 'wc -c < %s/chunks/%s' % (REMOTE, f['chunk'])).strip())
            trailer = fetch_range(serial, 'chunks/' + f['chunk'], size_remote - 16, 16)
            with open(stub, 'wb') as fh:
                fh.write(trailer)               # 16 bytes: the patcher only needs the index from here
            trailer_seen.add(f['chunk'])
        cache[(f['chunk'], f['off'], f['csz'])] = zstd.ZstdDecompressor().decompress(
            fetch_range(serial, 'chunks/' + f['chunk'], f['off'], f['csz']))
    print('  ambil %d potongan teks cerita (%d chunk) dari HP' % (len(cache), len(trailer_seen)))
    return cache


def backup_on_device(serial, v):
    shell(serial, 'mkdir -p %s/%s' % (REMOTE, DEVICE_BACKUP))
    for name, sub in FILES(v):
        rel = (sub + '/' + name) if sub else name
        target = '%s/%s/%s' % (REMOTE, DEVICE_BACKUP, name)
        if not shell(serial, 'test -f %s && echo yes' % target).strip():
            shell(serial, 'cp %s/%s %s' % (REMOTE, rel, target))
    print('  cadangan asli disimpan di %s/%s (di dalam HP)' % (REMOTE, DEVICE_BACKUP))


def backup_local(serial, work, v, version):
    dest = os.path.join(datafile.ROOT, 'android_backup', version)
    os.makedirs(dest, exist_ok=True)
    for name, sub in FILES(v):
        src = os.path.join(work, sub, name)
        if os.path.exists(src):
            shutil.copy2(src, os.path.join(dest, name))
    print('  cadangan asli juga disimpan di komputer: %s' % dest)


def already_patched(work):
    """Read the pulled UI table: if its text is already in the translation cache, the phone is patched."""
    patch.GAMERES, patch.BACKUP = work, os.path.join(work, '__none__')
    try:
        _, plain, _ = patch.load_original()
        rows = list(patch.rows(patch.parse_nodes(plain)[1]))
    except Exception:
        return False
    if not rows:
        return False
    values = set(datafile.load(datafile.find('cache.dat')).values())
    hit = sum(1 for _, t in rows if t.decode('utf-8', 'replace') in values)
    return hit > len(rows) * 0.3


def normalise_etag(work):
    """The Android client writes manifest.ssra.etag with LF and a trailing newline; match that exactly."""
    p = os.path.join(work, 'manifest.ssra.etag')
    text = open(p, newline='').read().replace('\r\n', '\n')
    open(p, 'wb').write('\n'.join(text.split('\n')).encode())


def check(serial=None):
    global ADB
    ADB = adb_path()
    serial = serial or pick_device()
    v = json.load(open(os.path.join(datafile.DATA, 'version.json')))
    print('Perangkat : %s' % serial)
    print('Versi game: %s' % remote_version(serial))
    print('Didukung  : %s (data terjemahan di folder data\\)' % v['version'])
    state = []
    for name, sub in FILES(v):
        rel = (sub + '/' + name) if sub else name
        state.append((rel, remote_sha(serial, rel)))
    meta = shell(serial, 'cat %s/patch-meta.json' % REMOTE)
    print('\nStatus berkas di HP:')
    for rel, h in state:
        print('  %-34s %s' % (rel, h[:16] + '...' if h else '(tidak ada)'))
    if meta.strip():
        try:
            d = json.loads(meta)
            print('\npatch-meta.json: result=%s files=%s bytes=%s' % (
                d.get('result'), d.get('plan', {}).get('files'), d.get('downloaded', {}).get('files')))
        except ValueError:
            pass
    print('\nBandingkan sendiri dengan versi terpatch kalau perlu; jalankan "android" untuk memasang,'
          ' "android-restore" untuk mengembalikan.')


def main(cmd, args):
    global ADB
    ADB = adb_path()
    if cmd == 'android-check':
        return check(args[0] if args else None)

    serial = pick_device()
    v = json.load(open(os.path.join(datafile.DATA, 'version.json')))
    device_v = remote_version(serial)
    print('Perangkat: %s | versi game di HP: %s' % (serial, device_v))
    if device_v != v['version']:
        sys.exit('Versi game tidak cocok. Patch ini untuk %s, HP kamu %s.\n'
                 'Jalankan update.bat untuk mengambil data terbaru, atau pasang versi game yang sesuai.'
                 % (v['version'], device_v))

    if cmd == 'android-restore':
        remote_backup = '%s/%s' % (REMOTE, DEVICE_BACKUP)
        have = shell(serial, 'test -d %s && echo yes' % remote_backup).strip()
        local = os.path.join(datafile.ROOT, 'android_backup', v['version'])
        if have:
            print('Mengembalikan dari cadangan di dalam HP...')
            for name, sub in FILES(v):
                rel = (sub + '/' + name) if sub else name
                shell(serial, 'cp %s/%s %s/%s' % (remote_backup, name, REMOTE, rel))
        elif os.path.isdir(local):
            print('Mengembalikan dari cadangan di komputer (%s)...' % local)
            for name, sub in FILES(v):
                rel = (sub + '/' + name) if sub else name
                push_file(serial, rel, os.path.join(local, name))
        else:
            sys.exit('Tidak ada cadangan asli (di HP maupun di komputer). Ambil ulang berkas asli lewat '
                     'launcher (verifikasi berkas) atau pasang ulang game.')
        print('Hasil:')
        for name, sub in FILES(v):
            rel = (sub + '/' + name) if sub else name
            print('  %-34s %s' % (rel, remote_sha(serial, rel)[:16] + '...'))
        print('Selesai: berkas asli sudah dipasang kembali. Buka game untuk memastikan.')
        return

    work_root = os.path.join(datafile.ROOT, 'android_work')
    os.makedirs(work_root, exist_ok=True)
    work = tempfile.mkdtemp(prefix='czn_android_', dir=work_root)
    print('Menyiapkan ruang kerja: %s' % work)
    force_stop(serial)
    cache = stage_from_device(serial, work, v)
    if already_patched(work) and '--force' not in args:
        sys.exit('HP ini sepertinya sudah terpatch (teks UI-nya sudah bahasa Indonesia).\n'
                 '  Pasang ulang dari asli : CZN_Patch.exe android-restore  lalu jalankan lagi\n'
                 '  Tetap paksa pasang     : CZN_Patch.exe android --force')
    backup_on_device(serial, v)
    backup_local(serial, work, v, v['version'])

    print('Memasang terjemahan (tidak ada permintaan jaringan, semua dihitung dari berkas HP)...')
    patch.FRAME_PROVIDER = story_provider(serial, cache)
    saved, sys.argv = sys.argv, ['CZN_Patch.exe', 'apply', work]
    try:
        patch.main()
    finally:
        sys.argv = saved
        patch.FRAME_PROVIDER = None

    print('Mengirim berkas hasil patch ke HP...')
    normalise_etag(work)
    expect = {}
    for name, sub in FILES(v):
        rel = (sub + '/' + name) if sub else name
        push_file(serial, rel, os.path.join(work, sub, name))
        expect[rel] = sha256_file(os.path.join(work, sub, name))

    print('Memeriksa hasil di HP...')
    bad = [rel for rel, want in expect.items() if remote_sha(serial, rel) != want]
    if bad:
        sys.exit('Berkas berikut tidak cocok setelah dikirim:\n  ' + '\n  '.join(bad) +
                 '\nJalankan: CZN_Patch.exe android-restore')
    for rel in expect:
        print('  OK %-34s %s' % (rel, expect[rel][:16] + '...'))
    print('\nSelesai. Buka game; kalau teksnya masih Inggris, jalankan "CZN_Patch.exe android-check".')
    print('Mengembalikan ke asli: "CZN_Patch.exe android-restore".')
    shutil.rmtree(work, ignore_errors=True)
