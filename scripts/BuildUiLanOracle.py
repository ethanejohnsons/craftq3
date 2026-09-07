"""Compile an authored adapter that calls unchanged static UI LAN operations.

Only public headers and exact function signatures were inspected. Including the
original translation unit lets the adapter call its static operations directly;
no native routine body is read, copied, renamed, or translated.
"""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
source = root / '.tools/ioquake3-source/code'
out = root / '.tools/ui-lan-oracle'
out.mkdir(parents=True, exist_ok=True)
flags = ['-std=c11', '-O2', '-ffp-contract=off', '-ffunction-sections', '-fdata-sections',
         '-DSTANDALONE', '-DLEGACY_PROTOCOL', '-DUSE_INTERNAL_SDL_HEADERS',
         '-I' + str(source), '-I' + str(source / 'thirdparty/SDL2-2.32.8/include')]
objects = []
for unit in ['qcommon/q_shared', 'qcommon/net_chan']:
    target = out / (unit.replace('/', '_') + '.o')
    extra = ['-DNET_StringToAdr=UiLanOriginalStringToAdr'] if unit == 'qcommon/net_chan' else []
    subprocess.run(['clang', *flags, *extra, '-c', str(source / (unit + '.c')), '-o', str(target)], check=True)
    objects.append(str(target))
subprocess.run(['clang', *flags, '-Wl,-dead_strip', str(root / 'scripts/UiLanOracle.c'), str(root / 'scripts/UiLanAddressOracle.c'),
                *objects, '-o', str(out / 'probe')], check=True)
print(out / 'probe')
