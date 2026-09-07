"""Build adaptive connect observations using unchanged native objects and authored stubs."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
source = root / '.tools/ioquake3-source'
out = root / '.tools/connect-compression-oracle'
out.mkdir(parents=True, exist_ok=True)
objects = []
for unit in ('net_chan', 'msg', 'huffman', 'q_shared'):
    target = out / (unit + '.o')
    subprocess.run(['clang', '-std=c11', '-O2', '-ffunction-sections', '-fdata-sections',
                    '-I' + str(source / 'code'), '-c',
                    str(source / 'code/qcommon' / (unit + '.c')), '-o', str(target)], check=True)
    objects.append(str(target))
subprocess.run(['clang', '-std=c11', '-O2', '-Wl,-dead_strip',
                '-I' + str(source / 'code'), '-I' + str(root / 'scripts'),
                str(root / 'scripts/ConnectCompressionOracle.c'), *objects,
                '-o', str(out / 'probe')], check=True)
print(out / 'probe')
