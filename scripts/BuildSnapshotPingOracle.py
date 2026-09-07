"""Compile unchanged native parser operations for authored snapshot-ping metadata fixtures."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
source = root / '.tools/ioquake3-source/code'
out = root / '.tools/snapshot-ping-oracle'
out.mkdir(parents=True, exist_ok=True)
objects = []
for unit in ('client/cl_parse.c','client/cl_input.c','qcommon/net_chan.c','qcommon/msg.c','qcommon/huffman.c'):
    target = out / (Path(unit).stem + '.o')
    subprocess.run(['clang','-std=c11','-O2','-I'+str(source),'-c',str(source/unit),'-o',str(target)],check=True)
    objects.append(str(target))
subprocess.run(['clang','-std=c11','-O2','-Wl,-dead_strip','-I'+str(source),
                '-I'+str(root/'scripts'),str(root/'scripts/SnapshotPingOracle.c'),*objects,
                '-o',str(out/'probe')],check=True)
print(out/'probe')
