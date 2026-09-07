"""Build the CPU-only master/status observer from unchanged native operations.

Only the native outbound formatter and OS address lookup definitions are renamed
at compile time. The authored host captures sends and accepts numeric addresses;
native packet parsing, status state, message reading and comparison are unchanged.
No real socket, GUI, public master request, or external DNS lookup is performed.
"""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
source = root / '.tools/ioquake3-source/code'
out = root / '.tools/browser-discovery-oracle'
out.mkdir(parents=True, exist_ok=True)
flags = ['-std=c11', '-O2', '-fno-inline', '-ffp-contract=off', '-ffunction-sections',
         '-fdata-sections', '-DSTANDALONE', '-DLEGACY_PROTOCOL', '-DUSE_INTERNAL_SDL_HEADERS',
         '-I' + str(source), '-I' + str(source / 'thirdparty/SDL2-2.32.8/include')]
units = {
    'client/cl_main': [],
    'qcommon/q_shared': [],
    'qcommon/msg': [],
    'qcommon/huffman': [],
    'qcommon/net_ip': ['-DSys_StringToAdr=Observed_Sys_StringToAdr'],
    'qcommon/net_chan': ['-DNET_OutOfBandPrint=Observed_NET_OutOfBandPrint'],
}
objects = []
for unit, overrides in units.items():
    target = out / (unit.replace('/', '_') + '.o')
    subprocess.run(['clang', *flags, *overrides, '-c', str(source / (unit + '.c')),
                    '-o', str(target)], check=True, capture_output=True)
    objects.append(str(target))
subprocess.run(['clang', *flags, '-Wl,-dead_strip', str(root / 'scripts/BrowserDiscoveryOracle.c'),
                *objects, '-o', str(out / 'probe')], check=True)
print(out / 'probe')
