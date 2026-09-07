"""CPU-only actual Fabric session, original UI/cgame, and a private unchanged native server."""
from pathlib import Path
import os
import subprocess
import tempfile
import threading
import time
import zipfile
from NativeNetworkServer import NativeNetworkServer
root=Path(__file__).resolve().parent.parent
out=root/'.tools/remote-application'
out.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory(prefix='games-',dir=out) as temporary, NativeNetworkServer('application') as server:
    games=Path(temporary)
    base=games/'baseq3';base.mkdir()
    # Read original media in place; only source-built public QA modules go in the generated QA pack.
    os.link(root/'.tools/pak0-audit/games/baseq3/pak0.pk3',base/'pak0.pk3')
    with zipfile.ZipFile(base/'zz-craftq3-public-qvms.pk3','w',compression=zipfile.ZIP_DEFLATED) as archive:
        for name in ('qagame','cgame','ui'):
            archive.write(root/'.tools/ioquake3-qvm-audit-build/Release/baseq3/vm'/(name+'.qvm'),'vm/'+name+'.qvm')
    command=[str(root/'gradlew'),':craftq3-fabric:auditRemoteApplication',
             '-Pq3RemoteAuditPort='+str(server.address[1]),'-Pq3RemoteAuditGames='+str(games),
             '-Pq3RemoteAuditOutput='+str(out),'--console=plain']
    child=subprocess.Popen(command,cwd=root,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    timeout=threading.Timer(90,child.kill);timeout.start()
    try:
        for line in child.stdout:
            print(line,end='',flush=True)
            if line.strip()=='RESTART_LEVEL':
                server.process.stdin.write(b'map_restart 0\n');server.process.stdin.flush()
            if line.strip()=='CHANGE_LEVEL q3dm17':
                server.process.stdin.write(b'map q3dm17\n');server.process.stdin.flush()
        if child.wait()!=0: raise RuntimeError('Remote application audit failed')
    finally:
        timeout.cancel()
        if child.poll() is None: child.kill();child.wait()
        child.stdout.close()
    deadline=time.monotonic()+2
    while 'ClientDisconnect: 0' not in server.log_path.read_text() and time.monotonic()<deadline:time.sleep(.01)
    if 'ClientDisconnect: 0' not in server.log_path.read_text():raise AssertionError('Native disconnect missing')
