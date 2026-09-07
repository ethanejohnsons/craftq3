"""Both original gameplay ABIs with delayed UDP, rate changes and measured status/player ping."""
from pathlib import Path
import argparse,os,subprocess,tempfile,zipfile
root=Path(__file__).resolve().parent.parent
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--classpath',required=True,help='Inspected engine runtime jars or compiled classes')
parser.add_argument('--profile',choices=['retail','modern','both'],default='both');args=parser.parse_args()
for profile in (['retail','modern'] if args.profile=='both' else [args.profile]):
 out=root/'.tools/hosted-timing'/profile;out.mkdir(parents=True,exist_ok=True)
 with tempfile.TemporaryDirectory(prefix='games-',dir=out) as temporary:
  games=Path(temporary);base=games/'baseq3';base.mkdir()
  os.link(root/'.tools/pak0-audit/games/baseq3/pak0.pk3',base/'pak0.pk3')
  if profile=='modern':
   with zipfile.ZipFile(base/'zz-public-qa-vms.pk3','w',compression=zipfile.ZIP_DEFLATED) as pack:
    for name in ['qagame','cgame','ui']:pack.write(root/'.tools/ioquake3-qvm-audit-build/Release/baseq3/vm'/(name+'.qvm'),'vm/'+name+'.qvm')
  command=[str(Path(os.environ['JAVA_HOME'])/'bin/java'),'-cp',args.classpath,str(root/'scripts/AuditHostedTiming.java'),str(games)]
  with (out/'audit.log').open('w') as log:result=subprocess.run(command,cwd=root,stdout=log,stderr=subprocess.STDOUT,timeout=90)
  if result.returncode:raise RuntimeError('Hosted timing audit failed: '+str(out/'audit.log'))
  print(profile+' '+next(line for line in (out/'audit.log').read_text().splitlines() if line.startswith('PASS ')),flush=True)
