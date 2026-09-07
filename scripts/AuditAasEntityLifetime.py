"""Authored native-only regression for frame validity and retained AAS collision links."""
from pathlib import Path
import random
import subprocess
import sys

binary = sys.argv[1] if len(sys.argv) > 1 else '.tools/aas-entity-lifetime-oracle/probe'
pak = sys.argv[2] if len(sys.argv) > 2 else '.tools/pak0-audit/games/baseq3/pak0.pk3'
rng = random.Random(55350)
commands, expected = [], []
valid, linked, previous, time = False, False, None, 1.0
for index in range(10000):
    choice = rng.randrange(7)
    if choice < 3 or previous is None:
        origin = 1109 if choice == 0 else 1110 if choice == 1 else (previous or 1109)
        commands.append(f'entity 209 {origin} 1175 16 -15 -15 -15 15 15 15 2 12 32')
        if origin != previous:
            linked = True
        previous, valid = origin, True
    elif choice == 3:
        commands.append('unentity 209')
        linked = False
    else:
        time += rng.choice([0, .05, .1, 1, 10])
        commands.append(f'frame {time:.8f}')
        if not valid:
            linked = False
        valid = False
    commands.append('trace 1048.3479 1059.18762 48.25 1048.3479 1059.18762 24.125 4 1')
    commands.append('entityinfo 209')
    expected.append((valid, linked))
result = subprocess.run([binary, pak, 'q3dm1'], input='\n'.join(commands)+'\n', text=True,
                        capture_output=True, check=True, timeout=60)
rows = [line for line in result.stdout.splitlines() if line.startswith(('CALLS ', 'INFO '))]
assert len(rows) == 2*len(expected), (len(rows), len(expected))
for i, (valid, linked) in enumerate(expected):
    calls = int(rows[2*i].split()[1])
    info = rows[2*i+1].split()
    assert calls == int(linked), (i, expected[i], rows[2*i:2*i+2])
    assert int(info[1]) == int(valid) and int(info[4]) == 12, (i, info)
print(f'AAS entity lifetime: {len(expected)} update/null/frame sequences exact; validity, retained model and collision callbacks checked')
