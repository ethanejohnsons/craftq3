"""Build the authored block checksum observer, leaving native algorithm source unchanged."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
source = root / '.tools/ioquake3-source/code'
out = root / '.tools/block-checksum-oracle'
out.mkdir(parents=True, exist_ok=True)
obj = out / 'md4.o'
subprocess.run(['clang', '-std=c11', '-O2', '-I' + str(source), '-c',
                str(source / 'qcommon/md4.c'), '-o', str(obj)], check=True)
subprocess.run(['clang', '-std=c11', '-O2', '-I' + str(source),
                str(root / 'scripts/BlockChecksumOracle.c'), str(obj),
                '-o', str(out / 'probe')], check=True)
print(out / 'probe')
