"""Compare authored ping sequences on production Java and unchanged native browser operations.

Uses private numerical endpoint identities with captured sends; no socket, DNS
request, original assets or GUI. Reflection in the Java adapter only seeds slot
metadata for controlled capacity tests, not any native implementation recipe.
"""
import argparse
import json
from pathlib import Path
import random
import subprocess

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--cases', type=int, default=1000)
parser.add_argument('--java', default='/Users/ethan/Library/Java/JavaVirtualMachines/jbr-25.0.3/Contents/Home/bin/java')
args = parser.parse_args()
if not 1 <= args.cases <= 10000:
    raise ValueError('Case budget must be 1..10000')

class Host:
    def __init__(self, command):
        self.process = subprocess.Popen(command, cwd=root, text=True, stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE)
        self.ready = self.process.stdout.readline().rstrip()
    def command(self, text):
        self.process.stdin.write(text + '\n')
        self.process.stdin.flush()
        result = []
        while True:
            line = self.process.stdout.readline()
            if not line:
                raise RuntimeError('Oracle ended on ' + text)
            line = line.rstrip()
            if line == 'END':
                return result
            if not line.startswith(('CVAR ', 'PRINT ')):
                result.append(line)
    def close(self):
        self.process.stdin.close()
        if self.process.wait(timeout=10):
            raise RuntimeError('Oracle failed')

native = Host([str(root / '.tools/browser-ping-oracle/probe')])
java = Host([args.java, '-cp', str(root / 'craftq3-client/build/classes/java/main'),
             str(root / 'scripts/BrowserPingJavaOracle.java')])
if native.ready != java.ready:
    raise AssertionError((native.ready, java.ready))
recent = []
queries = 0

def command(text):
    global queries
    first, second = native.command(text), java.command(text)
    queries += 1
    recent.append(text)
    del recent[:-40]
    if first != second:
        failure = {'query': queries, 'commands': recent, 'native': first, 'java': second}
        (root / '.tools/browser-ping-oracle/mismatch.json').write_text(json.dumps(failure, indent=2))
        raise AssertionError(failure)

def encoded(info):
    return info.encode('latin1').hex() or '-'

rng = random.Random(930744)
# Controlled occupied-slot selection: holes, exact 500ms edges, completed/negative results.
for case in range(args.cases):
    command('reset'); command('time 3000')
    for slot in range(32):
        if case % 2 == 0 or rng.random() < .9:
            start = rng.randrange(1500, 3001)
            completed = rng.choice([0, 1, 499, 500, 501, 777, -1])
            command(f'slot {slot} 127.0.0.1:{27000 + slot} {start} {completed} 6f6c64')
    command('ping 127.0.0.1:29000')
    command('get ' + str(rng.randrange(32)))

# Ordinary request/reply/clear/info/timeout sequences, including bounded unusual info values.
for case in range(args.cases):
    command('reset')
    now = 1000
    command('time 1000')
    command('max ' + str(rng.choice([-1, 1, 100, 101, 800, 1200])))
    for source in (0, 2, 3):
        for row in range(5):
            command(f'server {source} {row} 127.0.0.1:{27000 + row} -1 {rng.choice([0, 1, 7])}')
    for operation in range(24):
        now += rng.choice([0, 1, 99, 100, 499, 500, 801])
        command(f'time {now}')
        choice = rng.randrange(6)
        if choice == 0:
            command(f'ping 127.0.0.1:{27000 + rng.randrange(6)}')
        elif choice == 1:
            command(f'clear {rng.randrange(-1, 34)}')
        elif choice == 2:
            command(f'get {rng.randrange(-1, 34)} {rng.choice([1, 5, 24, 1024])}')
        elif choice == 3:
            command(f'info {rng.randrange(-1, 34)} {rng.choice([1, 5, 64, 1024])}')
        elif choice == 4:
            info = ('\\protocol\\' + rng.choice(['68', '69', '71', '071x'])
                    + '\\gamename\\' + rng.choice(['', 'Quake3Arena', 'wrong'])
                    + '\\hostname\\' + rng.choice(['Authored', 'a%b\xc8', 'a\nb;"c', 'x' * 1050])
                    + '\\mapname\\room\\nettype\\9\\challenge\\wrong')
            command(f'reply 127.0.0.1:{27000 + rng.randrange(6)} ' + encoded(info))
        else:
            command(f'update {rng.choice([-1, 0, 1, 2, 3, 4])}')
        for source in (0, 2, 3):
            command('servers ' + str(source))

# Saturated visible queue, immediate response, delayed reuse and global overflow.
for case in range(max(1, args.cases // 10)):
    command('reset'); command('time 1000')
    for row in range(34):
        command(f'server 2 {row} 127.0.0.1:{27000 + row} -1 {rng.choice([0, 1, 7])}')
    for row in range(5):
        command(f'overflow {row} 127.0.0.2:{27000 + row}')
    for step in range(10):
        command(f'time {1000 + step * 100}')
        if step in (1, 4):
            command(f'reply 127.0.0.1:{27000 + rng.randrange(34)} ' + encoded('\\protocol\\71\\hostname\\Reply'))
        command('update 2'); command('servers 2')

native.close(); java.close()
result = {'cases': args.cases, 'queries': queries, 'differences': 0}
(root / '.tools/browser-ping-oracle/result.json').write_text(json.dumps(result, indent=2) + '\n')
print('PASS ' + json.dumps(result))
