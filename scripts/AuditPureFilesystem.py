"""Private authored PK3 fixtures and native/production filesystem differential.

No installed game files are opened, modified or extracted. Optional Java classpath
must contain compiled PureFilesystemJavaOracle plus the core/assets main classes.
"""
import argparse
import json
from pathlib import Path
import random
import subprocess
import tempfile
from PureFilesystemHarness import ROOT, NativeFilesystem, hextext, pack


class JavaFilesystem(NativeFilesystem):
    def __init__(self, directory, game, classpath, java):
        directory = Path(directory).resolve()
        self.log = (directory / 'java.log').open('w')
        self.process = subprocess.Popen(
            [java, '-cp', classpath, 'PureFilesystemJavaOracle', str(directory / 'base'), game],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.log, text=True)
        self.history = []
        if self.process.stdout.readline().strip() != 'QA_READY':
            raise RuntimeError('Java adapter failed: ' + str(directory / 'java.log'))


def normalized(command, response):
    response = response.copy()
    if command.startswith('exists '):
        response['EXISTS'] = str(int(int(response['EXISTS']) != 0))
    if command.startswith('files '):
        response['ENTRY'] = sorted(set(x.lower() for x in response.get('ENTRY', []) if not x.endswith('/')))
        response['COUNT'] = str(len(response['ENTRY']))
    return response


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-classpath')
    parser.add_argument('--java', default='java')
    parser.add_argument('--operations', type=int, default=2000)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    rng = random.Random(680912)
    count = 0
    transcript = []
    workspace = ROOT / '.tools/pure-filesystem-oracle'
    workspace.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='authored-', dir=workspace) as temporary:
        top = Path(temporary)
        for scenario in range(3):
            directory = top / str(scenario)
            base = directory / 'base/baseq3'
            base.mkdir(parents=True)
            (base / 'default.cfg').write_text('// Authored initialization fixture.\n')
            paths = ['same.dat', 'x.cfg', 'x.txt', 'x.shader', 'x.arena', 'x.bot', 'x.menu',
                     'x.config', 'x.game', 'x.jpg', 'x.dat', 'x.wav', 'empty.dat',
                     'vm/cgame.qvm', 'vm/ui.qvm', 'vm/qagame.qvm', 'levelshots/a.tga',
                     'xlevelshots.tga', 'nested/cgame.qvm.foo', 'nested/ui.qvm.foo',
                     'd.dm_66', 'd.dm_67', 'd.dm_68', 'd.dm_71', 'd.dm_69', 'd.dm_70',
                     'd.dm_68a', 'd.dm_068', 'd.dm_68.bak', 'd.dm_+68', 'd.dm_4294967364',
                     'd.dm_ 68', 'd.dm_-68', 'd.dm_0x44', 'dm_68']
            pack(base, 'a.pk3', [(p, b'' if p == 'empty.dat' else ('a:' + p).encode()) for p in paths])
            pack(base, 'z.pk3', [('same.dat', b'z'), ('z.dat', b'z-data'), ('default.cfg', b'// packed cfg')])
            pack(base, 'b.pk3', [('b.dat', b'duplicate checksum bytes')])
            pack(base, 'c.pk3', [('c.dat', b'duplicate checksum bytes')])
            pack(base, 'e.pk3', [('empty', b'')])
            pack(base, 'empty-zip.pk3', [])
            pack(base, 'f.pk3', [('data-directory/', b'CRC metadata only')])
            loose = ['same.dat', 'x.cfg', 'x.menu', 'x.game', 'x.dat', 'x.jpg', 'loose.cfg']
            loose += [p for p in paths if '.dm_' in p or p == 'dm_68']
            for path in loose:
                (base / path).write_bytes(('loose:' + path).encode())
            game = 'baseq3'
            if scenario == 2:
                game = 'mymod'
                mod = directory / 'base/mymod'
                mod.mkdir()
                pack(mod, 'MixQ.pk3', [('mod.dat', b'mod'), ('same.dat', b'mod same')])
                (mod / 'mod.cfg').write_text('// authored mod cfg')
            with NativeFilesystem(directory, game) as native:
                java = JavaFilesystem(directory, game, args.java_classpath, args.java) if args.java_classpath else None
                try:
                    def run(command):
                        nonlocal count
                        expected = native.command(command)
                        transcript.append({'scenario': scenario, 'command': command, 'native': expected})
                        if java:
                            actual = java.command(command)
                            if normalized(command, expected) != normalized(command, actual):
                                (workspace / 'failure.json').write_text(json.dumps(transcript, indent=2) + '\n')
                                raise AssertionError(f'scenario={scenario}, operation={count}, {command!r}\nnative={expected}\njava={actual}\nReplay prefix: {workspace / "failure.json"}')
                        count += 1
                        return expected
                    initial = run('list')
                    checksums = initial['LOADED_CHECKSUMS'].split()
                    requested = paths + ['b.dat', 'c.dat', 'z.dat', 'missing.dat', 'loose.cfg', 'mod.dat', 'mod.cfg',
                                         'VM/CGAME.QVM', 'VM/UI.QVM', 'VM/QAGAME.QVM',
                                         'vm\\qagame.qvm', 'vm\\cgame.qvm', 'Levelshots/a.tga']
                    # Every exclusion and special reference is observed independently.
                    for path in requested:
                        run('clear 0'); run('read ' + hextext(path)); run('list')
                    # Immediate filters, deferred ordering, equal sums, missing sums and clearing.
                    for allowed in ([checksums[-1]], checksums[::-1], [checksums[0]],
                                    [checksums[2], checksums[2]], ['123456789'], []):
                        run('pure ' + hextext(' '.join(allowed)) + ' ' + hextext('deliberately/wrong names'))
                        run('read ' + hextext('same.dat')); run('files - -'); run('list')
                        run('feed 42'); run('list'); run('read ' + hextext('same.dat'))
                        run('pure - -'); run('list')
                    for _ in range(args.operations):
                        choice = rng.randrange(10)
                        if choice < 5:
                            run(('read ' if choice < 4 else 'open ') + hextext(rng.choice(requested)))
                        elif choice == 5:
                            run('clear ' + str(rng.choice([0, 1, 2, 3, 4, 5, 6, 7, 8])))
                        elif choice == 6:
                            run('feed ' + str(rng.randrange(-2**31, 2**31)))
                        elif choice < 9:
                            selected = rng.choices(checksums + ['123456789', '0'], k=rng.randrange(8))
                            run('pure ' + hextext(' '.join(selected)) + ' -')
                        else:
                            run('files - -')
                        run('list')
                finally:
                    if java:
                        java.close()
        if args.output:
            args.output.write_text(json.dumps(transcript, indent=2) + '\n')
    print(f'PASS {count} filesystem operation comparisons; 3 authored installations; '
          + ('native/production exact' if args.java_classpath else 'native observations only'))


if __name__ == '__main__':
    main()
