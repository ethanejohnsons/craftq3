"""Both original local QVM profiles exporting a live loadout into an external-world session."""
from pathlib import Path
import argparse,os,subprocess,tempfile,zipfile
root=Path(__file__).resolve().parent.parent
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--profile',choices=['retail','modern','both'],default='both');parser.add_argument('--runtime',type=Path,help='Inspected engine and Fabric jars');args=parser.parse_args()
for profile in (['retail','modern'] if args.profile=='both' else [args.profile]):
    out=root/'.tools/bridge-transfer-application'/profile;out.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='games-',dir=out) as temporary:
        games=Path(temporary);base=games/'baseq3';base.mkdir()
        os.link(root/'.tools/pak0-audit/games/baseq3/pak0.pk3',base/'pak0.pk3')
        if profile=='modern':
            with zipfile.ZipFile(base/'zz-public-qa-vms.pk3','w',compression=zipfile.ZIP_DEFLATED) as pack:
                for name in ['qagame','cgame','ui']:pack.write(root/'.tools/ioquake3-qvm-audit-build/Release/baseq3/vm'/(name+'.qvm'),'vm/'+name+'.qvm')
        command=[str(root/'gradlew'),':craftq3-fabric:auditRemoteApplication','-Pq3BridgeTransferAudit=true','-Pq3RemoteAuditGames='+str(games),'-Pq3RemoteAuditOutput='+str(out),'--console=plain']
        if args.runtime:command.append('-Pq3AuditRuntime='+str(args.runtime.resolve()))
        with (out/'application.log').open('w') as log: result=subprocess.run(command,cwd=root,stdout=log,stderr=subprocess.STDOUT,timeout=120)
        if result.returncode:raise RuntimeError('Bridge transfer audit failed: '+str(out/'application.log'))
        print(profile+' '+(out/'bridge-transfer-result.txt').read_text().strip(),flush=True)
