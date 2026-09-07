"""Check native-only guest-buffer, stale-row and master-command boundaries.

The observer prints outbound arguments; it never sends a packet or performs DNS.
Run BuildBrowserDiscoveryOracle.py first. Production memory writes are tested by
the UI bridge tests; these observations establish the boundary it implements.
"""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
probe = root / '.tools/browser-discovery-oracle/probe'


def run(commands, code=0):
    result = subprocess.run([str(probe)], input='\n'.join(commands) + '\n',
                            text=True, capture_output=True)
    assert result.returncode == code, (result.returncode, result.stderr)
    return result


address = b'127.0.0.1:27960'.hex()
body = b'\\hostname\\fixture\n3 9 "one"\n\n'
text = b'\\hostname\\fixture\\\\3 9 "one"\\'
ready = ['clear', 'tick 1000 750', f'status 32 {address}',
         'response 27960 ' + body.hex()]
for capacity in [1, 8, 8192, 8193, 65536]:
    result = run([*ready, f'status {capacity} {address}'])
    observed = [line for line in result.stdout.splitlines() if line.startswith('STATUS ')][-1]
    output = text[:capacity - 1].ljust(capacity, b'\0')
    assert observed == 'STATUS 1 ' + output.hex(), (capacity, observed[:100])

for capacity in [-1, 0]:
    result = run(['clear', 'tick 1000 750', f'status {capacity} {address}'])
    assert 'STATUS 0 \n' in result.stdout
    result = run([*ready, f'status {capacity} {address}'], code=2)
    assert 'Q_strncpyz: destsize < 1' in result.stderr

packet = b'\xff' * 4 + b'getserversResponse\\\x7f\0\0\1\x6d\x38\\EOT'
result = run(['reset 0 0 100', 'seedrow 0', 'recount -1 2',
              'master 0 0 0 ' + packet.hex(), 'row 0'])
assert 'COUNTS 1 0 0 0\n' in result.stdout
assert 'ROW 0 4 27960 0 7f000001 0 0 0 0 0 0 -1 108 0 0 0   \n' in result.stdout

for master, kind, command in [
    ('127.0.0.1:27950', 4, b'getservers 68 empty full'),
    ('[::1]:27950', 5, b'getserversExt Quake3Arena 68 empty full'),
]:
    result = run(['reset 0 2 100', 'masteraddr ' + master.encode().hex(),
                  'global 1 68 empty full'])
    assert f'SEND 1 {kind} 27950 ' + command.hex() + '\n' in result.stdout
    assert 'GLOBAL -1 2 2\n' in result.stdout

print('BROWSER native boundaries: five positive capacities, four nonpositive states, '
      'stale-row visibility and two master request families PASS')
