"""Authored wire/filesystem controls for private pure-server observations."""
from pathlib import Path
import base64
import os
import queue
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parent.parent


def native_checksums(server, feed, opens=None):
    if opens is None:
        opens = ['vm/cgame.qvm', 'vm/ui.qvm', 'maps/q3dm1.bsp', 'data/pure-wire.bin']
    requests = ['feed '+str(feed), *('open '+name.encode('latin1').hex() for name in opens),
                'list', 'quit']
    result = subprocess.run([
        str(ROOT/'.tools/pure-filesystem-oracle/probe'),
        '+set', 'fs_basepath', str(ROOT/'.tools/pak0-audit/games'),
        '+set', 'fs_homepath', server.home.name, '+set', 'com_basegame', 'baseq3',
        '+set', 'com_gamename', 'Quake3Arena', '+set', 'fs_game', '',
        '+set', 'dedicated', '1', '+set', 'net_enabled', '0'],
        input='\n'.join(requests)+'\n', text=True, capture_output=True, timeout=10, check=True)
    values = {}
    for line in result.stdout.splitlines():
        if line.startswith('QA_LOADED_') or line.startswith('QA_REFERENCED_'):
            name, hex_value = line[3:].split(' ', 1)
            values[name] = '' if hex_value == '-' else bytes.fromhex(hex_value).decode('latin1')
    if 'REFERENCED_PURE' not in values:
        raise AssertionError('Native FS transcript missing')
    return values


class PureWireClient:
    def __init__(self, server):
        self.server = server
        java = str(Path(os.environ['JAVA_HOME'])/'bin/java') if 'JAVA_HOME' in os.environ else 'java'
        classpath = os.environ.get('CRAFTQ3_AUDIT_CLASSPATH') or os.pathsep.join(
            str(ROOT/(module+'/build/classes/java/main')) for module in ('craftq3-core', 'craftq3-platform'))
        self.process = subprocess.Popen([java, '-cp', classpath, str(ROOT/'scripts/PureWireProbe.java'),
                                         str(server.address[1])], text=True, stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, cwd=ROOT)
        self.pending = queue.Queue()
        self.records = []
        self.games = []
        self.frames = []
        self.commands = []

        def reader():
            for line in self.process.stdout:
                self.pending.put(line.strip())
        self.reader = threading.Thread(target=reader, daemon=True)
        self.reader.start()

    def control(self, text):
        self.process.stdin.write(text+'\n')
        self.process.stdin.flush()

    def command(self, text):
        self.control('CMD '+text)

    def batch(self, commands):
        self.control('BATCH '+base64.b64encode('\n'.join(commands).encode('latin1')).decode('ascii'))

    def pump(self, duration):
        deadline = time.monotonic()+duration
        while time.monotonic() < deadline:
            try:
                line = self.pending.get(timeout=min(.02, max(.001, deadline-time.monotonic())))
            except queue.Empty:
                if self.process.poll() is not None:
                    raise AssertionError('Java wire observer stopped: '+repr(self.records[-10:]))
                continue
            self.records.append(line)
            fields = line.split()
            if line.startswith('GAMESTATE '):
                self.games.append({'number': int(fields[1]), 'serverId': int(fields[2]),
                                   'feed': int(fields[3]),
                                   'serverInfo': base64.b64decode(fields[4]).decode('latin1'),
                                   'systemInfo': base64.b64decode(fields[5]).decode('latin1')})
            elif line.startswith('FRAME '):
                self.frames.append({'number': int(fields[1]), 'time': int(fields[2]),
                                    'serverId': int(fields[3]), 'ack': int(fields[4]),
                                    'commandTime': int(fields[5]),
                                    'position': list(map(float, fields[6:9]))})
            elif line.startswith('SERVER_COMMAND '):
                self.commands.append(base64.b64decode(fields[2]).decode('latin1'))
            elif line.startswith('REJECTED '):
                diagnostic = base64.b64decode(fields[1]).decode('latin1')
                if diagnostic != 'Snapshot precedes gamestate':
                    raise AssertionError('Unexpected wire rejection: '+diagnostic)

    def wait_game(self, count=1):
        deadline = time.monotonic()+8
        while len(self.games) < count and time.monotonic() < deadline:
            self.pump(.05)
        if len(self.games) < count:
            raise AssertionError('Private gamestate timed out')
        return self.games[-1]

    def summary(self):
        log = self.server.log_path.read_text()
        return {'games': len(self.games), 'frames': len(self.frames),
                'begins': log.count('ClientBegin: 0'), 'disconnects': log.count('ClientDisconnect: 0'),
                'last': self.frames[-1] if self.frames else None}

    def close(self):
        if self.process.poll() is None:
            self.control('QUIT')
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
        self.process.stdin.close()
        self.reader.join(timeout=1)
        self.process.stdout.close()

    def __enter__(self): return self
    def __exit__(self, *ignored): self.close()
