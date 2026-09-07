"""Build the authored CPU clock host against unchanged native client operations."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
source = root / '.tools/ioquake3-source/code'
out = root / '.tools/demo-clock-oracle'
out.mkdir(parents=True, exist_ok=True)
target = out / 'cl_cgame.o'
subprocess.run([
    'clang', '-std=c11', '-O2', '-ffp-contract=off', '-ffunction-sections',
    '-fdata-sections', '-I' + str(source), '-c', str(source / 'client/cl_cgame.c'),
    '-o', str(target),
], check=True)
subprocess.run([
    'clang', '-std=c11', '-O2', '-Wl,-dead_strip', '-I' + str(source),
    str(root / 'scripts/DemoClockOracle.c'), str(target), '-o', str(out / 'probe'),
], check=True)
print(out / 'probe')
