"""Automatic PK3 installation in both original UI/cgame profiles, private UDP and authored fixtures only."""
from pathlib import Path
import argparse,os,subprocess,tempfile,zipfile,random
root=Path(__file__).resolve().parent.parent
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--profile',choices=['retail','modern','both'],default='both');parser.add_argument('--runtime',type=Path,help='Inspected engine and Fabric jars');args=parser.parse_args()
for profile in (['retail','modern'] if args.profile=='both' else [args.profile]):
    out=root/'.tools/download-application'/profile;out.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='fixture-',dir=out) as temporary:
        fixture=Path(temporary);games=fixture/'games';results=fixture/'results'
        for side in ['host','remote']:
            base=games/side/'baseq3';base.mkdir(parents=True)
            os.link(root/'.tools/pak0-audit/games/baseq3/pak0.pk3',base/'pak0.pk3')
            if profile=='modern':
                with zipfile.ZipFile(base/'zz-public-qa-vms.pk3','w',compression=zipfile.ZIP_DEFLATED) as pack:
                    for name in ['qagame','cgame','ui']:pack.write(root/'.tools/ioquake3-qvm-audit-build/Release/baseq3/vm'/(name+'.qvm'),'vm/'+name+'.qvm')
        for filename,member,size in [('zz_download_fixture','marker',65537),('za_download_second','second',17001)]:
            with zipfile.ZipFile(games/'host/baseq3'/(filename+'.pk3'),'w',compression=zipfile.ZIP_STORED) as pack:
                pack.writestr('models/qa-download/'+member+'.bin',random.Random(42).randbytes(size))
        command=[str(root/'gradlew'),':craftq3-fabric:auditRemoteApplication','-Pq3DownloadAudit=true','-Pq3RemoteAuditGames='+str(games),'-Pq3RemoteAuditOutput='+str(results),'--console=plain']
        if args.runtime:command.append('-Pq3AuditRuntime='+str(args.runtime.resolve()))
        with (out/'application.log').open('w') as log: result=subprocess.run(command,cwd=root,stdout=log,stderr=subprocess.STDOUT,timeout=180)
        if result.returncode:raise RuntimeError('Download application audit failed: '+str(out/'application.log'))
        report=(results/'download-application-result.txt').read_text();(out/'result.txt').write_text(report);print(profile+' '+report.strip(),flush=True)
