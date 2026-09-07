"""Compare captured trap558 requests with unchanged native weapon selection.

Capture files are produced by ObserveBotInventory.java. This oracle reads PK3s and
an optional matching public inventory header; it never modifies either source.
"""
import argparse
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--oracle', type=Path, required=True)
parser.add_argument('--pk3', type=Path, required=True)
parser.add_argument('--header', type=Path)
parser.add_argument('captures', type=Path, nargs='+')
args = parser.parse_args()
cases = []
for path in args.captures:
    for line in path.read_text().splitlines():
        match = re.fullmatch(r'WEAPON time=(\d+) selected=(\d+) inventory=\{(.*)\}', line)
        if not match:
            continue
        inventory = [0] * 256
        if match[3]:
            for pair in match[3].split(', '):
                index, value = map(int, pair.split('='))
                if index < 0 or index >= 256 or value < -2**31 or value >= 2**31:
                    raise ValueError('Invalid inventory capture')
                inventory[index] = value
        cases.append((path.name, int(match[1]), int(match[2]), inventory))
if not cases:
    raise ValueError('No captured weapon requests')
commands = 'alloc\nload 1 bots/sarge_w.c\n'
for _, _, _, inventory in cases:
    commands += 'inventory ' + ' '.join(map(str, inventory)) + '\nchoose 1\n'
argv = [str(args.oracle.resolve()), str(args.pk3.resolve()), 'weapons.c']
if args.header:
    argv.append(str(args.header.resolve()))
result = subprocess.run(argv, input=commands, text=True, capture_output=True, timeout=60, check=True)
if not result.stdout.startswith('SETUP 0\nHANDLE 1\nLOAD 0\n'):
    raise AssertionError('Native weapon setup failed: ' + result.stdout[:1000])
choices = [int(line.split()[1]) for line in result.stdout.splitlines() if line.startswith('CHOOSE ')]
if len(choices) != len(cases):
    raise AssertionError('Incomplete native response')
for case, native in zip(cases, choices):
    name, time, expected, _ = case
    if native != expected:
        raise AssertionError(f'{name} time={time}: Java={expected}, native={native}')
print(f'Native inventory captures={len(cases)} differences=0 header={args.header or "PK3"}')
