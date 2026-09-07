"""Build an authored CPU-only browser ping observer against unchanged native objects.

The numerical-only NET_StringToAdr host boundary uses inet_pton; the queue,
reply handling, visible-list updates, message decoding and address comparison
remain native. No networking, native function-body inspection or source rewrites.
"""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
source = root / '.tools/ioquake3-source/code'
out = root / '.tools/browser-ping-oracle'
out.mkdir(parents=True, exist_ok=True)
flags = ['-std=c11', '-O2', '-fno-inline', '-ffp-contract=off', '-ffunction-sections',
         '-fdata-sections', '-DSTANDALONE', '-DLEGACY_PROTOCOL', '-DUSE_INTERNAL_SDL_HEADERS',
         '-I' + str(source), '-I' + str(source / 'thirdparty/SDL2-2.32.8/include')]
objects = []
for unit in ['client/cl_main', 'qcommon/q_shared', 'qcommon/msg', 'qcommon/huffman', 'qcommon/net_ip']:
    target = out / (unit.replace('/', '_') + '.o')
    subprocess.run(['clang', *flags, '-c', str(source / (unit + '.c')), '-o', str(target)],
                   check=True, capture_output=True)
    objects.append(str(target))
subprocess.run(['clang', *flags, '-Wl,-dead_strip', str(root / 'scripts/BrowserPingOracle.c'),
                *objects, '-o', str(out / 'probe')], check=True)
print(out / 'probe')
