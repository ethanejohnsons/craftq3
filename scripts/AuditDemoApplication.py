"""Private CPU record/playback and original Demos-menu audit, with direct original PK3 reads."""
from pathlib import Path
import argparse
import os
import subprocess
import tempfile
import threading
import zipfile
from NativeNetworkServer import NativeNetworkServer
root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--profile', choices=['retail','modern','both'], default='both')
args = parser.parse_args()
for profile in (['retail','modern'] if args.profile == 'both' else [args.profile]):
    out = root / '.tools/demo-application' / profile
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='games-', dir=out) as temporary, NativeNetworkServer('demo-'+profile) as server:
        games = Path(temporary); base=games/'baseq3'; base.mkdir()
        os.link(root/'.tools/pak0-audit/games/baseq3/pak0.pk3', base/'pak0.pk3')
        if profile == 'modern':
            with zipfile.ZipFile(base/'zz-public-qa-vms.pk3','w',compression=zipfile.ZIP_DEFLATED) as pack:
                for name in ['qagame','cgame','ui']:
                    pack.write(root/'.tools/ioquake3-qvm-audit-build/Release/baseq3/vm'/(name+'.qvm'),'vm/'+name+'.qvm')
        command=[str(root/'gradlew'), ':craftq3-fabric:auditRemoteApplication', '-Pq3RemoteAuditDemo=true',
                 '-Pq3RemoteAuditPort='+str(server.address[1]), '-Pq3RemoteAuditGames='+str(games),
                 '-Pq3RemoteAuditOutput='+str(out), '--console=plain']
        child=subprocess.Popen(command,cwd=root,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
        timeout=threading.Timer(120,child.kill);timeout.start()
        try:
            with (out/'application.log').open('w') as log:
                for line in child.stdout:
                    log.write(line);log.flush()
                    if line.strip()=='RESTART_LEVEL':
                        server.process.stdin.write(b'map_restart 0\n');server.process.stdin.flush()
                    if line.strip()=='CHANGE_LEVEL q3dm17':
                        server.process.stdin.write(b'map q3dm17\n');server.process.stdin.flush()
            if child.wait(): raise RuntimeError('Demo audit failed: '+str(out/'application.log'))
        finally:
            timeout.cancel()
            if child.poll() is None: child.kill();child.wait()
            child.stdout.close()
        print(profile+' '+(out/'demo-application-result.json').read_text().strip(),flush=True)
