"""Chaos Zero Nightmare English text patcher (educational).

UI text: lang_en_b03_0.ssrc, last zstd frame = XOR'd "PLPc" table, rewritten in place.
Story text: "text_en" fields inside JSON cells of PLPc tables in the base pack (listed in
story_files.json). Translated copies are appended to the base pack's last chunk and the
manifest records are pointed there, so no other file moves.
Chain kept consistent: frame -> chunk trailer xxh64 -> manifest chunk table + file
records (virtual offset, csize, dsize) -> manifest.ssra.etag (xxh64 + size of manifest).

usage: patch apply|restore "<game folder>"      (the folder that contains bin/)
       patch dump "<game folder>" out.tsv
"""
import collections, json, os, shutil, struct, sys
import xxhash, zstandard as zstd

import datafile

GAMERES = BACKUP = None   # set by setup() from the game folder given on the command line
FRAME_PROVIDER = None     # set by android.py when the story chunks live on a phone instead of on disk
MANIFEST, ETAG = 'manifest.ssra', 'manifest.ssra.etag'
STORY_LIST = os.path.join(datafile.DATA, 'story_files.json')
# where the text lives in one game version; written by scan.py
with open(os.path.join(datafile.DATA, 'version.json')) as _f: _v = json.load(_f)
GAME_VERSION, CHUNK, LANG_PACK, FRAME_OFF = _v['version'], _v['chunk'], _v['lang_pack'], _v['frame_off']
STORY_PACK, STORY_TAIL = _v['story_pack'], _v['story_tail']

def src(name):
    # original bytes: the backup if we keep one for this file, else the untouched game file
    b = os.path.join(BACKUP, name)
    return b if os.path.exists(b) else os.path.join(GAMERES, 'chunks' if name.endswith('.ssrc') else '', name)


def read_frame(name, off, csz):
    if FRAME_PROVIDER is not None:          # Android flow: frames are fetched from the device on demand
        return FRAME_PROVIDER(name, off, csz)
    with open(src(name), 'rb') as f:
        f.seek(off); return zstd.ZstdDecompressor().decompress(f.read(csz))


def load_original():
    chunk = open(src(CHUNK), 'rb').read()
    enc = zstd.ZstdDecompressor().decompressobj().decompress(chunk[FRAME_OFF:])
    # keystream = most common byte per position (plaintext is mostly 0x00); turn it into the master key
    ks = bytes(collections.Counter(enc[j::256]).most_common(1)[0][0] for j in range(256))
    key = bytes(ks[(i - len(enc)) % 256] for i in range(256))
    return chunk, xor(enc, key), key


def xor(data, key):
    # PLPc: byte j is XOR'd with master key[(j + len(data)) % 256], so the stream depends on file size
    r = len(data) % 256
    key = key[r:] + key[:r]
    k = (key * (len(data) // 256 + 1))[:len(data)]
    return (int.from_bytes(data, 'little') ^ int.from_bytes(k, 'little')).to_bytes(len(data), 'little')


# PLPc layout: header(43) | table: n_slots x [u8 0][u32 abs node ptr] | nodes back to back | optional
# 38-byte "PLPc" footer (counts only). Node count sits at @17 of the header, or of the footer if present.
# node: u32 size | u8 2 | u8 name_len | u32 value_len | u8 0 | u32 B (abs node ptr or 0) | name | value
# UI row node: name = key, value = key\0text\0
HDR_NODES, HDR_SLOTS, TABLE, NODE = 17, 21, 43, '<IBBIBI'


def parse_nodes(plain):
    n_slots = struct.unpack_from('<I', plain, HDR_SLOTS)[0]
    pos, nodes = TABLE + 5 * n_slots, []
    while pos < len(plain) and plain[pos:pos + 4] != b'PLPc':
        size, t, nl, vl, _, b = struct.unpack_from(NODE, plain, pos)
        assert t == 2 and size == 15 + nl + vl, f'bad node at {pos}'
        nodes.append((pos, plain[pos + 15:pos + 15 + nl], plain[pos + 15 + nl:pos + size], b))
        pos += size
    tail = plain[pos:]
    assert len(nodes) == struct.unpack_from('<I', tail or plain, HDR_NODES)[0]
    return n_slots, nodes, tail


def rebuild(plain, new_vals):
    """Lay the nodes out again with new values {node index: bytes}, remapping every absolute pointer."""
    n_slots, nodes, tail = parse_nodes(plain)
    remap, pos = {}, TABLE + 5 * n_slots
    for i, (old, name, value, _) in enumerate(nodes):
        remap[old] = pos; pos += 15 + len(name) + len(new_vals.get(i, value))
    out = bytearray(plain[:TABLE])
    for s in range(n_slots):
        p = struct.unpack_from('<I', plain, TABLE + 5 * s + 1)[0]
        out += b'\0' + struct.pack('<I', remap[p] if p else 0)
    for i, (_, name, value, b) in enumerate(nodes):
        value = new_vals.get(i, value)
        out += struct.pack(NODE, 15 + len(name) + len(value), 2, len(name), len(value), 0, remap[b] if b else 0)
        out += name + value
    return bytes(out + tail)


def rows(nodes):
    for _, name, value, _ in nodes:
        parts = value.split(b'\0')
        if len(parts) == 3 and parts[0] == name and parts[2] == b'':
            yield name.decode(), parts[1]


def translate_ui(plain, tr):
    new_vals = {}
    for i, (_, name, value, _) in enumerate(parse_nodes(plain)[1]):
        key = name.decode('utf-8', 'replace')
        parts = value.split(b'\0')
        if key in tr and len(parts) == 3 and parts[0] == name:
            text = tr[key].encode()
            assert b'\0' not in text
            new_vals[i] = name + b'\0' + text + b'\0'
    print(f'UI: patched {len(new_vals)} entries')
    return rebuild(plain, new_vals)


def story_cells(plain):
    """(node index, cell index, parsed JSON list) for every story JSON cell."""
    for i, (_, _, value, _) in enumerate(parse_nodes(plain)[1]):
        for c, cell in enumerate(value.split(b'\0')):
            if cell[:2] == b'[{': yield i, c, json.loads(cell)


def story_texts(key):
    for f in json.load(open(STORY_LIST)):
        plain = xor(read_frame(f['chunk'], f['off'], f['csz']), key)
        for _, _, lines in story_cells(plain):
            for o in lines:
                if o.get('text_en'): yield o['text_en']


def translate_story(plain, cache):
    nodes = parse_nodes(plain)[1]; new_vals = {}; done = 0
    for i, c, lines in story_cells(plain):
        hit = [o for o in lines if o.get('text_en') in cache]
        if not hit: continue
        for o in hit: o['text_en'] = cache[o['text_en']]
        done += len(hit)
        cells = new_vals.get(i, nodes[i][2]).split(b'\0')
        cells[c] = json.dumps(lines, ensure_ascii=False).encode()
        new_vals[i] = b'\0'.join(cells)
    return rebuild(plain, new_vals), done


# --- manifest -------------------------------------------------------------
def chunk_entries(m):
    return {struct.unpack_from('<IH', m, 64 + 32 * k): 64 + 32 * k for k in range(struct.unpack_from('<I', m, 12)[0])}


def pack_base(m, pack, idx):
    """Virtual offset where chunk idx starts inside its pack (sum of earlier bodies)."""
    return sum(struct.unpack_from('<Q', m, e + 8)[0] for (i, p), e in chunk_entries(m).items() if p == pack and i < idx)


def chunk_idx(name):
    with open(src(name), 'rb') as f:
        f.seek(-16, 2); t = f.read(16)
    assert t[:4] == b'SSRC'
    return struct.unpack('<I', t[4:8])[0]


def move_record(m, x_old, csz_old, dsz_old, x_new, csz_new, dsz_new):
    old = struct.pack('<QII', x_old, csz_old, dsz_old)
    j = m.find(old)
    assert j >= 0 and m.find(old, j + 1) < 0, 'manifest record not found exactly once'
    struct.pack_into('<QII', m, j, x_new, csz_new, dsz_new)


def write_chunk(m, pack, name, body):
    body += b'\0' * (-len(body) % 16)
    idx, h = chunk_idx(name), xxhash.xxh64(body).intdigest()
    data = body + b'SSRC' + struct.pack('<IQ', idx, h)
    struct.pack_into('<QQQ', m, chunk_entries(m)[(idx, pack)] + 8, len(body), len(data), h)
    open(os.path.join(GAMERES, 'chunks', name), 'wb').write(data)


def encrypt(plain, key):
    enc = zstd.ZstdCompressor(level=19).compress(xor(plain, key))
    assert xor(zstd.ZstdDecompressor().decompress(enc), key) == plain   # round-trip self-check
    return enc


def backup():
    os.makedirs(BACKUP, exist_ok=True)
    files = [os.path.join(GAMERES, 'chunks', CHUNK), os.path.join(GAMERES, 'chunks', STORY_TAIL),
             os.path.join(GAMERES, MANIFEST), os.path.join(GAMERES, ETAG)]
    for f in files:
        dst = os.path.join(BACKUP, os.path.basename(f))
        if not os.path.exists(dst): shutil.copy2(f, dst)


def find_gameres(path):
    for sub in ('', os.path.join('bin', 'appdata', 'cznlive', 'gameres')):
        p = os.path.join(path, sub)
        if os.path.exists(os.path.join(p, MANIFEST)): return p
    sys.exit(f'manifest.ssra not found under {path} (give the game folder that contains bin)')


def setup(folder):
    """Point at the game, and refuse a backup left over from another game version (it would feed old files in)."""
    global GAMERES, BACKUP
    GAMERES = find_gameres(folder)
    BACKUP = os.path.join(GAMERES, 'czn_id_backup')
    url = open(os.path.join(GAMERES, ETAG), newline='').readline()
    old = os.path.join(BACKUP, ETAG)
    if os.path.exists(old) and open(old, newline='').readline() != url:
        sys.exit(f'The game was updated since the backup was made. Delete {BACKUP} (verify game files in the launcher first), then retry.')
    return url


def main():
    if len(sys.argv) < 3 or sys.argv[1] not in ('apply', 'restore', 'dump'): sys.exit(__doc__)
    cmd, url = sys.argv[1], setup(sys.argv[2])
    if GAME_VERSION not in url:
        sys.exit(f'Versi game tidak cocok. Patch ini untuk {GAME_VERSION}, game kamu: {url.strip()}')
    backup()
    if cmd == 'restore':
        for f, sub in ((CHUNK, 'chunks'), (STORY_TAIL, 'chunks'), (MANIFEST, ''), (ETAG, '')):
            shutil.copy2(os.path.join(BACKUP, f), os.path.join(GAMERES, sub, f))
        print('restored'); return
    chunk, plain, key = load_original()
    if cmd == 'dump':
        with open(sys.argv[3], 'w', encoding='utf-8') as f:
            for k, t in rows(parse_nodes(plain)[1]): f.write(k + '\t' + t.decode('utf-8', 'replace').replace('\n', '\\n') + '\n')
        print('dumped'); return
    m = bytearray(open(src(MANIFEST), 'rb').read())

    # UI table: rewrite the last frame of its chunk in place
    assert rebuild(plain, {}) == plain, 'PLPc rebuild is not lossless'
    cache = datafile.load(datafile.find('cache.dat'))   # packed english -> indonesian; looked up now, the editor may have just saved
    tr = {k: cache[t.decode()] for k, t in rows(parse_nodes(plain)[1]) if t.decode() in cache}
    new_plain = translate_ui(plain, tr)
    got = dict(rows(parse_nodes(new_plain)[1]))
    assert all(got[k] == v.encode() for k, v in tr.items() if k in got), 'translated rows did not read back'
    enc = encrypt(new_plain, key)
    x = pack_base(m, LANG_PACK, chunk_idx(CHUNK)) + FRAME_OFF
    move_record(m, x, zstd_frame_len(chunk, FRAME_OFF), len(plain), x, len(enc), len(new_plain))
    write_chunk(m, LANG_PACK, CHUNK, chunk[:FRAME_OFF] + enc)

    # story tables: append translated copies to the end of the base pack, repoint their records
    if os.path.exists(STORY_LIST):
        tail = bytearray(open(src(STORY_TAIL), 'rb').read()[:-16])
        tail_x = pack_base(m, STORY_PACK, chunk_idx(STORY_TAIL))
        total = 0
        for f in json.load(open(STORY_LIST)):
            enc_old = read_frame(f['chunk'], f['off'], f['csz'])
            sp = xor(enc_old, key)
            new_sp, done = translate_story(sp, cache)
            if not done: continue
            enc = encrypt(new_sp, key)
            tail += b'\0' * (-len(tail) % 16)
            x_old = pack_base(m, STORY_PACK, chunk_idx(f['chunk'])) + f['off']
            move_record(m, x_old, f['csz'], f['dsz'], tail_x + len(tail), len(enc), len(new_sp))
            tail += enc; total += done
        write_chunk(m, STORY_PACK, STORY_TAIL, bytes(tail))
        print(f'story: patched {total} lines')

    raw = open(src(ETAG), newline='').read()      # a dump pulled off Android may use LF instead of CRLF
    nl = '\r\n' if '\r\n' in raw else '\n'
    etag = raw.split(nl)
    etag[2], etag[3] = str(xxhash.xxh64(bytes(m)).intdigest()), str(len(m))
    open(os.path.join(GAMERES, MANIFEST), 'wb').write(m)
    open(os.path.join(GAMERES, ETAG), 'w', newline='').write(nl.join(etag))
    print('ok')


def zstd_frame_len(data, off):
    d = zstd.ZstdDecompressor().decompressobj(); d.decompress(data[off:])
    return len(data) - off - len(d.unused_data)


def ask():
    """Double-click mode (no arguments): ask what to do and where, so the window does not just close."""
    import tkinter as tk
    from tkinter import filedialog
    print('Chaos Zero Nightmare - patch bahasa Indonesia\n')
    print('  1  pasang patch ke game PC')
    print('  2  kembalikan game PC ke asli')
    print('  3  pasang patch ke HP Android (USB debugging / wireless debugging)')
    print('  4  kembalikan HP Android ke asli')
    print('  5  cek status HP Android (versi game + keadaan patch)')
    choice = input('\nKetik 1-5: ').strip() if sys.stdin else ''
    if choice in ('3', '4', '5'):
        sys.argv = [sys.argv[0], {'3': 'android', '4': 'android-restore', '5': 'android-check'}[choice]]
        return
    if choice not in ('1', '2'): sys.exit('Pilihan tidak dikenal.')
    print('Pilih folder game (yang berisi folder "bin")...')
    root = tk.Tk(); root.withdraw(); root.attributes('-topmost', True)
    folder = filedialog.askdirectory(title='Pilih folder game Chaos Zero Nightmare (yang berisi bin)')
    if not folder: sys.exit('Tidak ada folder dipilih.')
    sys.argv = [sys.argv[0], 'apply' if choice == '1' else 'restore', os.path.normpath(folder)]


if __name__ == '__main__':
    interactive = len(sys.argv) < 2
    code = 0
    try:
        if interactive: ask()
        if sys.argv[1] in ('android', 'android-restore', 'android-check'):
            import android
            android.main(sys.argv[1], sys.argv[2:])
        else:
            main()
    except SystemExit as e:   # print the reason, and keep a double-clicked window open long enough to read it
        if e.code not in (None, 0):
            print(e.code); code = 1
    except Exception as e:
        print(f'Gagal: {e!r}'); code = 1
    if interactive:
        try: input('\nTekan Enter untuk menutup...')
        except EOFError: pass
    sys.exit(code)
