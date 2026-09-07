"""Build unchanged native volume operations with an authored geometry-only stdin host."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
source = root / '.tools/ioquake3-source/code'
out = root / '.tools/area-volume-oracle'
out.mkdir(parents=True, exist_ok=True)
objects = []
for unit in ('botlib/be_aas_reach.c', 'qcommon/q_math.c'):
    target = out / (Path(unit).stem + '-strict.o')
    subprocess.run(['clang', '-std=c11', '-O2', '-ffp-contract=off',
                    '-I' + str(source), '-c', str(source / unit), '-o', str(target)], check=True)
    objects.append(str(target))
subprocess.run(['clang', '-std=c11', '-O2', '-ffp-contract=off', '-Wl,-dead_strip',
                '-I' + str(source), str(root / 'scripts/AreaVolumeOracle.c'), *objects,
                '-o', str(out / 'probe')], check=True)
print(out / 'probe')
