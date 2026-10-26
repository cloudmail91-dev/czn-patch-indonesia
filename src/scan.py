"""Find where the text lives in the installed game version -> version.json (+ story_files.json).

usage: python src/scan.py "<game folder>" [--no-story]
       (--no-story only refreshes data/version.json, skipping the ~40 min story scan)
Run it after a game update, on unpatched files: verify the game files in the launcher and delete the old
czn_id_backup folder first. A backup of the same version is read instead of the patched game files.
"""
import collections, glob, json, os, re, struct, sys
import xxhash, zstandard as zstd

import datafile, patch

NO_STORY = '--no-story' in sys.argv


def chunk_files():
    return sorted(os.path.basename(p) for p in glob.glob(os.path.join(patch.GAMERES, 'chunks', '*.ssrc')))


def frames(data):
    """(offset, compressed size, decompressed bytes) of every zstd frame in a chunk."""
    pos = 0
    while pos < len(data):
        i = data.find(b'\x28\xb5\x2f\xfd', pos)
        if i < 0: return
        d = zstd.ZstdDecompressor().decompressobj(); win = data[i:i + 256 * 1024 * 1024]
        try: out = d.decompress(win)
        except Exception: pos = i + 4; continue
        csz = len(win) - len(d.unused_data); pos = i + max(csz, 4)
        yield i, csz, out


def find_key(enc):
    """Master XOR key recovered from one encrypted PLPc file (plaintext is mostly 0x00), or None."""
    ks = bytes(collections.Counter(enc[j::256]).most_common(1)[0][0] for j in range(256))
    key = bytes(ks[(i - len(enc)) % 256] for i in range(256))
    return key if bytes(enc[j] ^ key[(j + len(enc)) % 256] for j in range(4)) == b'PLPc' else None


def trailer(name):
    with open(patch.src(name), 'rb') as f:
        f.seek(-16, 2); t = f.read(16)
    assert t[:4] == b'SSRC', name
    return struct.unpack('<IQ', t[4:])   # (index inside its pack, xxh64 of the chunk body)


def main():
    args = [a for a in sys.argv[1:] if a != '--no-story']
    if len(args) != 1: sys.exit(__doc__)
    patch.setup(args[0])
    m = open(patch.src(patch.MANIFEST), 'rb').read()
    entries = patch.chunk_entries(m)
    by_hash = {struct.unpack_from('<Q', m, e + 24)[0]: pack for (_, pack), e in entries.items()}
    pack_of = {n: by_hash[h] for n in chunk_files() if (h := trailer(n)[1]) in by_hash}
    for n in chunk_files():
        if n not in pack_of: print(f'skipping {n}: not in the manifest (left over from an older version)')
    used = sorted(pack_of)   # from here on only chunks the game still uses

    # UI table: the big encrypted PLPc frame inside one of the lang_en chunks
    found = None
    for name in (n for n in used if n.startswith('lang_en')):
        for off, csz, enc in frames(open(patch.src(name), 'rb').read()):
            if len(enc) > 1_000_000 and (key := find_key(enc)):
                found = (name, off, key)
    assert found, 'no UI text table found in the lang_en chunks'
    chunk, off, key = found
    print(f'UI text: {chunk} frame @ {off:#x}, pack {pack_of[chunk]}')

    story = None
    if not NO_STORY:
        story = []
        for name in (n for n in used if not n.startswith(('lang_', 'bin_', 'voice_'))):
            hits = 0
            for off2, csz, out in frames(open(patch.src(name), 'rb').read()):
                if out[:3] == b'SCT': continue
                plain = patch.xor(out, key) if bytes(out[j] ^ key[(j + len(out)) % 256] for j in range(4)) == b'PLPc' else out
                if b'"text_en"' in plain:
                    story.append({'chunk': name, 'off': off2, 'csz': csz, 'dsz': len(out)}); hits += 1
            print(f'{name}: {hits} story files (total {len(story)})', flush=True)
        json.dump(story, open(patch.STORY_LIST, 'w'), indent=0)

    # story translations are appended to the last chunk of the pack that holds the story files
    names = [f['chunk'] for f in story] if story else [f['chunk'] for f in json.load(open(patch.STORY_LIST))]
    story_pack = pack_of[names[0]]
    tail = max((n for n in used if pack_of[n] == story_pack), key=lambda n: trailer(n)[0])
    url = open(os.path.join(patch.GAMERES, patch.ETAG), newline='').readline()
    version = re.search(r'/patch/([\d.]+)/', url).group(1)
    info = {'version': version, 'chunk': chunk, 'lang_pack': pack_of[chunk], 'frame_off': off,
            'story_pack': story_pack, 'story_tail': tail}
    json.dump(info, open(os.path.join(datafile.DATA, 'version.json'), 'w'), indent=1)
    print('version.json:', info)


if __name__ == '__main__':
    main()
