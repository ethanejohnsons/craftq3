"""Authored private-fixture driver for the unchanged native filesystem observer."""
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent

def hextext(value):
    return value.encode('latin1').hex() or '-'

def pack(directory, name, entries):
    target = Path(directory) / name
    with zipfile.ZipFile(target, 'w', compression=zipfile.ZIP_STORED) as archive:
        for entry, contents in entries:
            info = zipfile.ZipInfo(entry, (2020, 1, 1, 0, 0, 0))
            archive.writestr(info, contents)
    return target

class NativeFilesystem:
    def __init__(self, directory, game='baseq3'):
        directory = Path(directory).resolve()
        self.base = directory / 'base'
        self.home = directory / 'home'
        (self.base / 'baseq3').mkdir(parents=True, exist_ok=True)
        self.home.mkdir(parents=True, exist_ok=True)
        self.log = (directory / 'native.log').open('w')
        self.process = subprocess.Popen(
            [str(ROOT / '.tools/pure-filesystem-oracle/probe'),
             '+set', 'fs_basepath', str(self.base), '+set', 'fs_homepath', str(self.home),
             '+set', 'com_basegame', 'baseq3', '+set', 'com_gamename', 'Quake3Arena',
             '+set', 'fs_game', '' if game == 'baseq3' else game,
             '+set', 'dedicated', '1', '+set', 'net_enabled', '0'],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.log, text=True,
            cwd=self.home)
        self.history = []
        while True:
            line = self.process.stdout.readline().strip()
            if line == 'QA_READY':
                break
            if not line:
                raise RuntimeError('native filesystem failed to initialize: ' + str(directory / 'native.log'))

    def command(self, command):
        self.process.stdin.write(command + '\n')
        self.process.stdin.flush()
        values = {}
        while True:
            line = self.process.stdout.readline().strip()
            if line == 'QA_END':
                break
            if not line:
                raise RuntimeError('native stopped during ' + command)
            if line.startswith('QA_'):
                label, _, value = line[3:].partition(' ')
                if label in {'LOADED_NAMES', 'LOADED_CHECKSUMS', 'LOADED_PURE', 'REFERENCED_NAMES',
                             'REFERENCED_CHECKSUMS', 'REFERENCED_PURE', 'NEEDED', 'ENTRY'}:
                    value = '' if value == '-' else bytes.fromhex(value).decode('latin1')
                if label == "ENTRY":
                    values.setdefault(label, []).append(value)
                else:
                    values[label] = value
        self.history.append((command, values))
        return values

    def listing(self):
        return self.command('list')

    def open(self, path, read=False):
        return self.command(('read ' if read else 'open ') + hextext(path))

    def pure(self, checksums='', names=''):
        return self.command('pure ' + hextext(checksums) + ' ' + hextext(names))

    def close(self):
        if self.process.poll() is None:
            self.command('quit')
        self.process.wait(timeout=5)
        self.log.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()
