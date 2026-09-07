"""Original retail/QA UI discovery and Join through the actual Fabric CPU session.

Only the LAN target selection is confined to the private native loopback server.
No public master, broadcast, GUI, fabricated browser row or injected connect is used.
"""
from pathlib import Path
import argparse
import os
import subprocess
import tempfile
import zipfile
from NativeNetworkServer import NativeNetworkServer

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--profile', choices=['retail', 'modern', 'both'], default='both')
args = parser.parse_args()
out = root / '.tools/browser-application'
out.mkdir(parents=True, exist_ok=True)
for profile in (['retail', 'modern'] if args.profile == 'both' else [args.profile]):
    result = out / profile
    result.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='games-', dir=out) as directory, NativeNetworkServer('browser-' + profile) as server:
        games = Path(directory)
        base = games / 'baseq3'
        base.mkdir()
        os.link(root / '.tools/pak0-audit/games/baseq3/pak0.pk3', base / 'pak0.pk3')
        with zipfile.ZipFile(base / 'zz-craftq3-public-qvms.pk3', 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            modules = ['qagame', 'cgame'] + (['ui'] if profile == 'modern' else [])
            for module in modules:
                archive.write(root / '.tools/ioquake3-qvm-audit-build/Release/baseq3/vm' / (module + '.qvm'), 'vm/' + module + '.qvm')
        command = [str(root / 'gradlew'), ':craftq3-fabric:auditRemoteApplication',
                   '-Pq3RemoteAuditBrowser=true', '-Pq3RemoteAuditPort=' + str(server.address[1]),
                   '-Pq3RemoteAuditGames=' + str(games), '-Pq3RemoteAuditOutput=' + str(result),
                   '--console=plain']
        with (result / 'application.log').open('w') as log:
            completed = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, timeout=90)
        if completed.returncode:
            raise RuntimeError('Browser application failed: ' + str(result / 'application.log'))
        print(profile + ' ' + (result / 'browser-application-result.json').read_text().strip(), flush=True)
