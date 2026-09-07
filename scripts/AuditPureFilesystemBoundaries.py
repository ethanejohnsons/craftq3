"""Assert focused native filesystem contracts using only private authored archives."""
import argparse
import json
import tempfile
from pathlib import Path
from PureFilesystemHarness import ROOT, NativeFilesystem, hextext, pack


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    rows = []
    checks = 0

    def expect(label, actual, expected):
        nonlocal checks
        rows.append({'case': label, 'actual': actual, 'expected': expected})
        if actual != expected:
            raise AssertionError(f'{label}: {actual!r} != {expected!r}')
        checks += 1

    with tempfile.TemporaryDirectory(prefix='boundaries-', dir=ROOT / '.tools/pure-filesystem-oracle') as tmp:
        root = Path(tmp)
        base = root / 'checksums/base/baseq3'
        base.mkdir(parents=True)
        (base / 'default.cfg').write_text('// authored')
        for name, entries in {
            'a.pk3': [('one', b'abc'), ('two', b'xyz')],
            'b.pk3': [('one', b'abc'), ('empty', b''), ('two', b'xyz')],
            'c.pk3': [('two', b'xyz'), ('one', b'abc')],
            'd.pk3': [('renamed', b'abc'), ('other', b'xyz')],
            'e.pk3': [('one', b'abc'), ('two', b'xyz'), ('folder/', b'')],
            'f.pk3': [('folder/', b'xyz')],
            'g.pk3': [('regular', b'xyz')],
            'h.pk3': [('empty', b'')],
            'i.pk3': [],
        }.items():
            pack(base, name, entries)
        with NativeFilesystem(root / 'checksums') as fs:
            expect('normal CRC order/empty/directory inputs', fs.listing()['LOADED_CHECKSUMS'],
                   '-1737972033 1613847021 1613847021 1225466421 1225466421 1603507484 1225466421 1225466421 ')
            expect('zero-entry ZIP omitted', fs.listing()['LOADED_NAMES'], 'h g f e d c b a')
            for feed, expected in [(0, 1290185885), (42, -1864024385)]:
                fs.command('feed ' + str(feed))
                expect('empty pure feed ' + str(feed), int(fs.listing()['LOADED_PURE'].split()[0]), expected)

        base = root / 'references/base/baseq3'
        base.mkdir(parents=True)
        (base / 'default.cfg').write_text('// authored')
        names = ['vm/cgame.qvm', 'vm/ui.qvm', 'vm/qagame.qvm', 'levelshots/a.tga',
                 'x/levelshots/a.tga', 'nested/cgame.qvm.foo', 'nested/ui.qvm.foo',
                 'x.cfg', 'x.txt', 'x.shader', 'x.arena', 'x.bot', 'x.menu', 'x.config',
                 'x.dat', 'empty.dat']
        pack(base, 'a.pk3', [(p, b'' if p == 'empty.dat' else b'fixture') for p in names])
        pack(base, 'Zed.pk3', [('z.dat', b'z')])
        for name in ['loose.cfg', 'loose.dat', 'loose.game', 'loose.jpg', 'dm_68',
                     'd.dm_68.bak', 'd.dm_ 68', 'd.dm_+68', 'd.dm_-68',
                     'd.dm_4294967364', 'd.dm_66', 'd.dm_67', 'd.dm_68', 'd.dm_69',
                     'd.dm_70', 'd.dm_71', 'd.dm_0x44', 'd.dm_68a']:
            (base / name).write_text('loose')
        with NativeFilesystem(root / 'references') as fs:
            listing = fs.listing()
            expect('case-preserving names', listing['LOADED_NAMES'], 'Zed a')
            pure = int(listing['LOADED_PURE'].split()[1])
            zsum = listing['LOADED_CHECKSUMS'].split()[0]
            general = f'@ {pure} {pure ^ 1} '
            for path, flags in [('vm/cgame.qvm', 5), ('vm/ui.qvm', 3), ('vm/qagame.qvm', 0),
                                ('VM/CGAME.QVM', 1), ('VM/UI.QVM', 1), ('VM/QAGAME.QVM', 0),
                                ('vm\\qagame.qvm', 1), ('vm\\cgame.qvm', 5),
                                ('levelshots/a.tga', 0), ('Levelshots/a.tga', 1),
                                ('x/levelshots/a.tga', 0), ('nested/cgame.qvm.foo', 5),
                                ('nested/ui.qvm.foo', 3), ('x.dat', 1), ('empty.dat', 1)]:
                fs.command('clear 0'); fs.open(path)
                expected = (f'{pure} ' if flags & 6 else '') + (general if flags & 1 else '@ 0 ')
                expect('reference ' + path, fs.listing()['REFERENCED_PURE'], expected)
            for extension in ['cfg', 'txt', 'shader', 'arena', 'bot', 'menu', 'config']:
                fs.command('clear 0'); fs.open('x.' + extension)
                expect('reference exemption ' + extension, fs.listing()['REFERENCED_PURE'], '@ 0 ')
            fs.open('vm/cgame.qvm'); fs.open('vm/ui.qvm'); fs.command('clear 1')
            expect('clear general retains both specials', fs.listing()['REFERENCED_PURE'], f'{pure} {pure} @ 0 ')
            fs.command('clear 0')
            for path in ['loose.jpg', 'loose.dat', 'loose.game']:
                fs.open(path, True)
                expect('nonpure loose no fake checksum ' + path, fs.listing()['REFERENCED_PURE'], '@ 0 ')
            fs.pure(zsum)
            expect('ordinary open filters qagame', fs.open('vm/qagame.qvm')['FILE'], '-1 0')
            expect('NULL-handle existence bypasses pure', fs.command('exists ' + hextext('vm/qagame.qvm'))['EXISTS'], '7')
            expect('inpack respects pure', fs.command('inpack ' + hextext('vm/qagame.qvm'))['INPACK'], '-1 305419896')
            for path, allowed in [('loose.cfg', True), ('loose.dat', True), ('loose.game', True),
                                  ('loose.jpg', False), ('dm_68', False), ('d.dm_68.bak', False),
                                  ('d.dm_ 68', True), ('d.dm_+68', True), ('d.dm_-68', False),
                                  ('d.dm_4294967364', True), ('d.dm_66', True), ('d.dm_67', True),
                                  ('d.dm_68', True), ('d.dm_69', False), ('d.dm_70', False),
                                  ('d.dm_71', True), ('d.dm_0x44', False), ('d.dm_68a', True)]:
                expect('pure loose ' + path, fs.open(path)['FILE'] != '-1 0', allowed)
            expect('pure list excludes all loose', fs.command('files - -').get('ENTRY'), ['z.dat'])
            fs.command('referenced ' + hextext('1234') + ' ' + hextext('baseq3/missing'))
            expect('missing display', fs.command('compare 0'), {'MISSING': '1', 'NEEDED': 'baseq3/missing.pk3\n'})
            expect('missing download description', fs.command('compare 1'), {'MISSING': '1', 'NEEDED': '@baseq3/missing.pk3@baseq3/missing.pk3'})
            fs.command('referenced ' + hextext(zsum) + ' ' + hextext('baseq3/wrong-name'))
            expect('present checksum ignores name', fs.command('compare 1'), {'MISSING': '0', 'NEEDED': ''})
            fs.command('referenced ' + hextext('1234') + ' ' + hextext('../escape'))
            expect('traversal reference ignored', fs.command('compare 1'), {'MISSING': '0', 'NEEDED': ''})
    if args.output:
        args.output.write_text(json.dumps(rows, indent=2) + '\n')
    print(f'PASS {checks} focused native filesystem boundary assertions')


if __name__ == '__main__':
    main()
