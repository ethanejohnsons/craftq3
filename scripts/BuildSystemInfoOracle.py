"""Observe unchanged native systeminfo effects using authored external callbacks."""
from pathlib import Path
import subprocess
root = Path(__file__).resolve().parent.parent
source = root / '.tools/ioquake3-source/code'
out = root / '.tools/system-info-oracle'
out.mkdir(parents=True, exist_ok=True)
objects = []
for unit in ('client/cl_parse', 'qcommon/q_shared'):
    target = out / (unit.replace('/', '_')+'.o')
    subprocess.run(['clang', '-std=c11', '-O2', '-ffunction-sections', '-fdata-sections',
                    '-I'+str(source), '-c', str(source/(unit+'.c')), '-o', str(target)], check=True)
    objects.append(str(target))
subprocess.run(['clang', '-std=c11', '-O2', '-Wl,-dead_strip', '-I'+str(source),
                str(root/'scripts/SystemInfoOracle.c'), *objects, '-o', str(out/'probe')], check=True)
print(out/'probe')
