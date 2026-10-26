"""Desktop editor for cache.dat (english -> indonesian). Run: python editor.py
Two tabs (UI / Story), each a searchable table. Click an Indonesian cell to edit it (Enter = apply,
Shift+Enter = new line, Esc = cancel). Ctrl+S writes cache.dat (the first save keeps cache.dat.bak).
The 'Apply ke game' button saves, then runs the patcher on the game folder (remembered in editor_settings.json).
"""
import collections, contextlib, json, os, queue, re, shutil, sys, threading, tkinter as tk
from tkinter import filedialog, messagebox, ttk

import datafile
import patch

CACHE = os.path.join(datafile.DATA, 'cache.dat')   # saves always go here; reads fall back to the copy inside the exe
SETTINGS = os.path.join(datafile.ROOT, 'editor_settings.json')   # remembered game folder
PAGE = 300
# game markup that must survive a translation unchanged (same pattern the translator validates with)
TAG = re.compile(r'<[^<>]*>|\{\d+\}|\$[^$\n]*\$|#[^#\s]+#|\[(?:/|c|C|fc|nfc|cfc)\]|\|')
SCOPES = ('Inggris + Indonesia', 'Inggris', 'Indonesia')
MODES = ('Semua', 'Sudah diedit', 'Sama dengan Inggris', 'Tag bermasalah')


def tags(s):
    return collections.Counter(TAG.findall(s))


def one_line(s):
    return s.replace('\n', ' ↵ ')


class Tab:
    def __init__(self, app, parent, keys):
        self.app, self.keys, self.view, self.page = app, keys, list(range(len(keys))), 0
        self.query, self.scope, self.mode = tk.StringVar(), tk.StringVar(value=SCOPES[0]), tk.StringVar(value=MODES[0])
        bar = ttk.Frame(parent); bar.pack(fill='x', padx=6, pady=6)
        ttk.Label(bar, text='Cari:').pack(side='left')
        entry = ttk.Entry(bar, textvariable=self.query, width=40); entry.pack(side='left', padx=4)
        ttk.Combobox(bar, textvariable=self.scope, values=SCOPES, state='readonly', width=20).pack(side='left', padx=4)
        ttk.Combobox(bar, textvariable=self.mode, values=MODES, state='readonly', width=20).pack(side='left', padx=4)
        self.info = ttk.Label(bar); self.info.pack(side='right')
        self.after_id = None
        self.query.trace_add('write', lambda *_: self.later())
        self.scope.trace_add('write', lambda *_: self.filter()); self.mode.trace_add('write', lambda *_: self.filter())

        mid = ttk.Frame(parent); mid.pack(fill='both', expand=True, padx=6)
        self.tree = ttk.Treeview(mid, columns=('n', 'en', 'id'), show='headings', selectmode='browse')
        for c, text, w, st in (('n', '#', 60, False), ('en', 'English', 520, True), ('id', 'Indonesia (klik untuk mengubah)', 520, True)):
            self.tree.heading(c, text=text); self.tree.column(c, width=w, stretch=st, anchor='w')
        sb = ttk.Scrollbar(mid, command=self.tree.yview); self.tree.configure(yscrollcommand=sb.set)
        self.tree.pack(side='left', fill='both', expand=True); sb.pack(side='right', fill='y')
        self.tree.tag_configure('edited', background='#e3f2e1'); self.tree.tag_configure('bad', foreground='#c62828')
        self.tree.bind('<ButtonRelease-1>', self.click)
        self.tree.bind('<<TreeviewSelect>>', self.show_full)

        foot = ttk.Frame(parent); foot.pack(fill='x', padx=6, pady=6)
        ttk.Button(foot, text='◀', width=3, command=lambda: self.go(-1)).pack(side='left')
        self.pager = ttk.Label(foot, width=26, anchor='center'); self.pager.pack(side='left')
        ttk.Button(foot, text='▶', width=3, command=lambda: self.go(1)).pack(side='left')
        self.full = ttk.Label(foot, wraplength=1100, justify='left'); self.full.pack(side='left', padx=12)
        self.editor = None
        self.filter()

    def later(self):   # wait for the typing to pause before re-filtering ~60k rows
        if self.after_id: self.tree.after_cancel(self.after_id)
        self.after_id = self.tree.after(300, self.filter)

    def match(self, i):
        k, v = self.keys[i], self.app.cache[self.keys[i]]
        mode = self.mode.get()
        if mode == MODES[1] and k not in self.app.edited: return False
        if mode == MODES[2] and k != v: return False
        if mode == MODES[3] and tags(k) == tags(v): return False
        q, scope = self.query.get().lower(), self.scope.get()
        if not q: return True
        return (scope != SCOPES[2] and q in k.lower()) or (scope != SCOPES[1] and q in v.lower())

    def filter(self):
        self.view, self.page = [i for i in range(len(self.keys)) if self.match(i)], 0
        self.render()

    def go(self, d):
        last = max(0, (len(self.view) - 1) // PAGE)
        self.page = min(max(self.page + d, 0), last); self.render()

    def values(self, i):
        k = self.keys[i]; v = self.app.cache[k]
        tg = ('edited',) if k in self.app.edited else ()
        return (i + 1, one_line(k), one_line(v)), tg + (('bad',) if tags(k) != tags(v) else ())

    def render(self):
        self.close_editor()
        self.tree.delete(*self.tree.get_children())
        for i in self.view[self.page * PAGE:(self.page + 1) * PAGE]:
            vals, tg = self.values(i); self.tree.insert('', 'end', iid=str(i), values=vals, tags=tg)
        pages = max(1, -(-len(self.view) // PAGE))
        self.pager.config(text=f'Hal {self.page + 1}/{pages}')
        self.info.config(text=f'{len(self.view)} dari {len(self.keys)} teks')
        self.full.config(text='')

    def refresh(self, k):
        i = self.app.index[self].get(k)
        if i is not None and self.tree.exists(str(i)):
            vals, tg = self.values(i); self.tree.item(str(i), values=vals, tags=tg)

    def show_full(self, _=None):
        sel = self.tree.selection()
        self.full.config(text=self.keys[int(sel[0])] if sel else '')

    def click(self, e):
        if self.tree.identify_region(e.x, e.y) != 'cell' or self.tree.identify_column(e.x) != '#3': return
        row = self.tree.identify_row(e.y)
        if row: self.edit(int(row))

    def edit(self, i):
        self.close_editor()
        x, y, w, h = self.tree.bbox(str(i), 'id')
        t = tk.Text(self.tree, wrap='word', font=('Segoe UI', 10), height=3, undo=True, relief='solid', borderwidth=1)
        t.insert('1.0', self.app.cache[self.keys[i]]); t.place(x=x, y=y, width=w, height=max(h * 3, 70)); t.focus_set()
        t.bind('<Return>', lambda e: (self.commit(i, t), 'break')[1])
        t.bind('<Shift-Return>', lambda e: None)
        t.bind('<Escape>', lambda e: self.close_editor())
        t.bind('<FocusOut>', lambda e: self.commit(i, t))
        self.editor = t

    def commit(self, i, t):
        if self.editor is not t: return
        k, new = self.keys[i], t.get('1.0', 'end-1c')
        self.close_editor()
        if new != self.app.cache[k]: self.app.set(k, new)

    def close_editor(self):
        if self.editor:
            t, self.editor = self.editor, None; t.destroy()


class App:
    def __init__(self, root):
        self.root = root
        self.cache, sets = datafile.load(datafile.find('cache.dat')), datafile.load(datafile.find('sets.dat'))
        seen = set(sets['ui']) | set(sets['story'])
        sets['ui'] += [k for k in self.cache if k not in seen]   # anything outside both lists goes under UI
        self.edited, self.unsaved = set(), set()
        root.title('CZN Translation Editor'); root.geometry('1300x760')
        style = ttk.Style(); style.configure('Treeview', rowheight=24)
        top = ttk.Frame(root); top.pack(fill='x', padx=6, pady=(6, 0))
        ttk.Button(top, text='Simpan (Ctrl+S)', command=self.save).pack(side='left')
        ttk.Button(top, text='Apply ke game', command=self.apply).pack(side='left', padx=(6, 0))
        self.status = ttk.Label(top); self.status.pack(side='left', padx=10)
        nb = ttk.Notebook(root); nb.pack(fill='both', expand=True)
        self.tabs = {}
        for name, label in (('ui', 'UI'), ('story', 'Story')):
            frame = ttk.Frame(nb); nb.add(frame, text=label)
            self.tabs[name] = Tab(self, frame, sets[name])
        self.index = {t: {k: i for i, k in enumerate(t.keys)} for t in self.tabs.values()}
        root.bind('<Control-s>', lambda e: self.save())
        root.protocol('WM_DELETE_WINDOW', self.quit)
        self.update_status()

    def set(self, k, v):
        self.cache[k] = v; self.edited.add(k); self.unsaved.add(k)
        for t in self.tabs.values(): t.refresh(k)   # the same english text can be listed in both tabs
        self.update_status()

    def update_status(self):
        self.status.config(text=f'{len(self.unsaved)} perubahan belum disimpan' if self.unsaved else 'Tersimpan')

    def save(self):
        bad = [k for k in self.unsaved if tags(k) != tags(self.cache[k])]
        if bad and not messagebox.askyesno('Tag berbeda', f'{len(bad)} teks yang diubah punya tag/placeholder berbeda dari aslinya '
                                           '(merah di tabel). Tag yang rusak bisa membuat game error. Tetap simpan?'): return False
        os.makedirs(datafile.DATA, exist_ok=True)
        if not os.path.exists(CACHE + '.bak'): shutil.copy2(datafile.find('cache.dat'), CACHE + '.bak')
        datafile.save(CACHE, self.cache)
        self.unsaved.clear(); self.update_status()
        return True

    def game_folder(self):
        try: folder = json.load(open(SETTINGS, encoding='utf-8')).get('game', '')
        except (OSError, ValueError): folder = ''
        if folder and messagebox.askyesno('Folder game', f'Pakai folder game ini?\n{folder}'): return folder
        folder = filedialog.askdirectory(title='Pilih folder game Chaos Zero Nightmare (yang berisi bin)')
        if folder:
            folder = os.path.normpath(folder)
            with open(SETTINGS, 'w', encoding='utf-8') as f: json.dump({'game': folder}, f)
        return folder

    def apply(self):
        if self.unsaved and not self.save(): return
        folder = self.game_folder()
        if not folder or not messagebox.askyesno('Apply', 'Tutup game dan launcher dulu. Pasang terjemahan ke game sekarang?'): return
        log, win = queue.Queue(), tk.Toplevel(self.root)
        win.title('Apply ke game'); text = tk.Text(win, width=90, height=14, state='disabled'); text.pack(padx=8, pady=8)

        class Out:   # patch.py prints its progress; route it into the log window
            def write(self, s): log.put(s)
            def flush(self): pass

        def work():
            sys.argv = ['patch.py', 'apply', folder]
            try:
                with contextlib.redirect_stdout(Out()): patch.main()
                log.put('\nSelesai. Buka game untuk mengecek.\n')
            except SystemExit as e: log.put(f'\nBerhenti: {e.code}\n')
            except Exception as e: log.put(f'\nGagal: {e!r}\n')
            log.put(None)

        def poll():
            done = False
            while not log.empty():
                s = log.get()
                if s is None: done = True; continue
                text.config(state='normal'); text.insert('end', s); text.see('end'); text.config(state='disabled')
            if not done: win.after(100, poll)
        threading.Thread(target=work, daemon=True).start(); poll()

    def quit(self):
        if self.unsaved:
            a = messagebox.askyesnocancel('Keluar', 'Ada perubahan yang belum disimpan. Simpan dulu?')
            if a is None: return
            if a: self.save()
        self.root.destroy()


if __name__ == '__main__':
    try:
        import ctypes; ctypes.windll.shcore.SetProcessDpiAwareness(1)   # sharp text on high-DPI screens
    except Exception: pass
    root = tk.Tk(); App(root); root.mainloop()
