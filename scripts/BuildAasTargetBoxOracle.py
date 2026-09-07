"""Build an authored fluid world against unchanged native prediction; reads no routine bodies."""
from pathlib import Path
import subprocess

source=Path('.tools/ioquake3-source/code')
output=Path('.tools/target-box-oracle')
output.mkdir(parents=True,exist_ok=True)
objects=[]
for relative in ('botlib/be_aas_move.c','qcommon/q_math.c'):
    target=output/(Path(relative).stem+'-unfused.o')
    subprocess.run(['clang','-std=c11','-O2','-ffp-contract=off','-I'+str(source),'-c',str(source/relative),'-o',str(target)],check=True)
    objects.append(str(target))
subprocess.run(['clang','-std=c11','-O2','-ffp-contract=off','-Wl,-dead_strip','-I'+str(source),'scripts/AasTargetBoxOracle.c',*objects,'-o',str(output/'probe')],check=True)
