"""Packed data files (.dat): zlib + XOR with a SHA-256 keystream. This hides the text from casual reading and
search; it is obfuscation, not secrecy, because the key has to ship inside the exes."""
import hashlib, json, os, sys, zlib

# release layout: the exes sit in ROOT, the scripts in ROOT/src, all data in ROOT/data
ROOT = os.path.dirname(sys.executable) if getattr(sys, 'frozen', False) else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, 'data')
# copies packed into the exe (PyInstaller --add-data); a file in DATA wins so editor saves and updates override them
BUNDLED = os.path.join(getattr(sys, '_MEIPASS', ROOT), 'data')


def find(name):
    p = os.path.join(DATA, name)
    return p if os.path.exists(p) else os.path.join(BUNDLED, name)

KEY = b'czn-id-patch-v1'
MAGIC = b'CZN1'


def _xor(data):
    n, out, i = len(data), bytearray(), 0
    while len(out) < n:
        out += hashlib.sha256(KEY + i.to_bytes(8, 'big')).digest(); i += 1
    return (int.from_bytes(data, 'little') ^ int.from_bytes(out[:n], 'little')).to_bytes(n, 'little')


def pack(obj):
    return MAGIC + _xor(zlib.compress(json.dumps(obj, ensure_ascii=False).encode('utf-8'), 9))


def unpack(data):
    assert data[:4] == MAGIC, 'not a packed data file'
    return json.loads(zlib.decompress(_xor(data[4:])).decode('utf-8'))


def load(path):
    with open(path, 'rb') as f: return unpack(f.read())


def save(path, obj):
    with open(path + '.tmp', 'wb') as f: f.write(pack(obj))
    os.replace(path + '.tmp', path)   # a crash mid-write cannot leave a half-written file
