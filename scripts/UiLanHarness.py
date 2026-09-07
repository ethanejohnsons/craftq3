"""Authored stdin driver for the CPU-only native UI LAN observer."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent


def hextext(text):
    return text.encode('latin1').hex() or '-'


class UiLan:
    def __init__(self):
        self.process = subprocess.Popen([str(ROOT / '.tools/ui-lan-oracle/probe')],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE, text=True)
        self.history = []

    def command(self, command):
        self.process.stdin.write(command + '\n')
        self.process.stdin.flush()
        rows = []
        while True:
            line = self.process.stdout.readline().strip()
            if line == 'END':
                self.history.append((command, rows))
                return rows
            if not line:
                raise RuntimeError('Native observer stopped during ' + command)
            rows.append(line)

    def seed(self, source, index, address='127.0.0.1:27960', host='fixture', mapname='q3dm1', game='baseq3',
             nettype=1, gametype=0, clients=3, maximum=8, minping=0, maxping=0,
             ping=50, visible=1, punkbuster=0, humans=2, needpass=0):
        return self.command('seed ' + ' '.join(map(str, [source, index, hextext(address), hextext(host),
                            hextext(mapname), hextext(game), nettype, gametype, clients, maximum,
                            minping, maxping, ping, visible, punkbuster, humans, needpass])))

    def text(self, operation, source, index, capacity=1024):
        rows = self.command(f'{operation} {source} {index} {capacity}')
        return bytes.fromhex(rows[0].split()[1]).split(b'\0')[0].decode('latin1')

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.process.stdin.close()
        self.process.wait(timeout=3)
        self.process.stdout.close()
        self.process.stderr.close()
