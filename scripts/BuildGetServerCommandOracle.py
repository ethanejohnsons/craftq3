"""Relink unchanged client operations with already-authored parser stubs and a new observer."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
source = root / '.tools/ioquake3-source'
out = root / '.tools/get-server-command-oracle'
out.mkdir(parents=True, exist_ok=True)
objects = []
units = ['qcommon/net_chan', 'qcommon/msg', 'qcommon/huffman', 'qcommon/q_shared',
         'qcommon/cmd', 'client/cl_parse', 'client/cl_cgame']
for unit in units:
    target = out / (unit.replace('/', '_') + '.o')
    flags = ['-DCbuf_AddText=UnusedNativeCbufAddText'] if unit == 'qcommon/cmd' else []
    subprocess.run(['clang', '-std=c11', '-O2', '-ffunction-sections', '-fdata-sections',
                    '-I' + str(source / 'code'), *flags, '-c',
                    str(source / 'code' / (unit + '.c')), '-o', str(target)], check=True)
    objects.append(str(target))
# Reuse only the existing authored host prefix, not native source fragments.
stub_source = (root / 'scripts/ServerMessageOracle.c').read_text().split('int main(void) {', 1)[0]
stubs = out / 'parser-stubs.c'
stubs.write_text('#define Com_Error UnusedInheritedComError\n' + stub_source)
subprocess.run(['clang', '-std=c11', '-O2', '-Wl,-dead_strip',
                '-I' + str(source / 'code'), '-I' + str(root / 'scripts'),
                str(root / 'scripts/GetServerCommandOracle.c'), str(stubs), *objects,
                '-o', str(out / 'probe')], check=True)
print(out / 'probe')
