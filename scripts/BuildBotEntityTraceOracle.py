"""Build authored retained-slot observer around unchanged native server entry points."""
from pathlib import Path
import subprocess

source=Path('.tools/ioquake3-source/code')
output=Path('.tools/entity-trace-oracle')
output.mkdir(parents=True,exist_ok=True)
objects=[]
for relative,renames in [
    ('server/sv_game.c',['-DSV_inPVS=UnusedNativeSVinPVS']),
    ('server/sv_world.c',['-DSV_Trace=UnusedNativeSVTrace','-DSV_PointContents=UnusedNativeSVPointContents']),
    ('server/sv_bot.c',[]),('qcommon/q_math.c',[])]:
    target=output/(Path(relative).stem+'.o')
    subprocess.run(['clang','-std=c11','-O2','-ffp-contract=off','-I'+str(source),*renames,
                    '-c',str(source/relative),'-o',str(target)],check=True)
    objects.append(str(target))
subprocess.run(['clang','-std=c11','-O2','-ffp-contract=off','-I'+str(source),'-Iscripts',
                '-Wl,-dead_strip','scripts/BotEntityTraceOracle.c',*objects,
                '-o',str(output/'probe')],check=True)
