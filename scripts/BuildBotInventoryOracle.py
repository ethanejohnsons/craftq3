"""Build an authored inventory oracle against unchanged local official botlib exports."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
source = root / '.tools/ioquake3-source'
out = root / '.tools/bot-inventory-oracle'
out.mkdir(parents=True, exist_ok=True)
objects = []
for name in ['be_ai_weap', 'l_struct', 'be_ai_weight', 'l_script', 'l_precomp']:
    target = out / (name + '.o')
    subprocess.run(['clang', '-O2', '-ffp-contract=off', '-DBOTLIB',
                    '-I' + str(source / 'code'), '-c',
                    str(source / 'code/botlib' / (name + '.c')),
                    '-o', str(target)], check=True)
    objects.append(target)
subprocess.run(['clang', '-O2', '-ffp-contract=off',
                '-I' + str(source / 'code'), '-I/opt/homebrew/opt/libarchive/include',
                str(root / 'scripts/BotInventoryOracle.c'), *map(str, objects),
                '-L/opt/homebrew/opt/libarchive/lib', '-larchive', '-o', str(out / 'probe')],
               check=True)
print(out / 'probe')
