"""Original qagame/cgame on mutable external block collision, with no bridge BSP file."""
from pathlib import Path
import argparse,os,subprocess,tempfile,zipfile
root=Path(__file__).resolve().parent.parent
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--classpath',required=True);parser.add_argument('--combat',action='store_true');parser.add_argument('--loadout',action='store_true');parser.add_argument('--fluids',action='store_true');args=parser.parse_args()
if sum([args.combat,args.loadout,args.fluids])>1:parser.error('Select one of combat, loadout or fluids')
for profile in ['retail','modern']:
 out=root/('.tools/bridge-fluids' if args.fluids else '.tools/bridge-loadout' if args.loadout else '.tools/bridge-combat' if args.combat else '.tools/bridge-gameplay')/profile;out.mkdir(parents=True,exist_ok=True)
 with tempfile.TemporaryDirectory(prefix='games-',dir=out) as temporary:
  games=Path(temporary);base=games/'baseq3';base.mkdir();os.link(root/'.tools/pak0-audit/games/baseq3/pak0.pk3',base/'pak0.pk3')
  if profile=='modern':
   with zipfile.ZipFile(base/'zz-public-qa-vms.pk3','w',compression=zipfile.ZIP_DEFLATED) as pack:
    for name in ['qagame','cgame','ui']:pack.write(root/'.tools/ioquake3-qvm-audit-build/Release/baseq3/vm'/(name+'.qvm'),'vm/'+name+'.qvm')
  command=[str(Path(os.environ['JAVA_HOME'])/'bin/java'),'-cp',args.classpath,str(root/('scripts/AuditBridgeFluids.java' if args.fluids else 'scripts/AuditBridgeLoadout.java' if args.loadout else 'scripts/AuditBridgeCombat.java' if args.combat else 'scripts/AuditBridgeGameplay.java')),str(games)]
  with (out/'audit.log').open('w') as log:result=subprocess.run(command,cwd=root,stdout=log,stderr=subprocess.STDOUT,timeout=90)
  if result.returncode:raise RuntimeError('Bridge audit failed: '+str(out/'audit.log'))
  print(profile+' '+next(line for line in (out/'audit.log').read_text().splitlines() if line.startswith('PASS ')),flush=True)
