"""Missing required pure package: actual CPU application recovers, native never admits it."""
from pathlib import Path
import json
import os
import subprocess
import tempfile
import threading
from NativePureServer import NativePureServer
from PureFilesystemHarness import pack


root = Path(__file__).resolve().parent.parent
out = root / '.tools/pure-connection-failure'
out.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='games-', dir=out) as temporary, NativePureServer('pure-failure') as server:
    games = Path(temporary)
    base = games / 'baseq3'
    base.mkdir()
    os.link(root / '.tools/pak0-audit/games/baseq3/pak0.pk3', base / 'pak0.pk3')
    missing = 'z_qa_cgame.pk3'
    for archive in (Path(server.home.name) / 'baseq3').glob('*.pk3'):
        if archive.name != missing:
            os.link(archive, base / archive.name)
    if (base / missing).exists():
        raise AssertionError('Required server cgame package must be absent')
    modules = root / '.tools/ioquake3-qvm-audit-build/Release/baseq3/vm'
    # Matching module bytes under a different pack checksum must not satisfy the requirement.
    # Original pak0 and these unchanged public QVMs are never extracted or modified.
    pack(base, 'zz_client_override.pk3', [
        ('vm/ui.qvm', (modules / 'ui.qvm').read_bytes()),
        ('vm/cgame.qvm', (modules / 'cgame.qvm').read_bytes()),
        ('data/client-only.txt', b'Authored client-only pure-failure checksum sentinel')])
    command = [str(root / 'gradlew'), ':craftq3-fabric:auditRemoteApplication',
               '-Pq3RemoteAuditPort=' + str(server.address[1]),
               '-Pq3RemoteAuditGames=' + str(games), '-Pq3RemoteAuditOutput=' + str(out),
               '-Pq3RemoteAuditPure=true', '-Pq3RemoteAuditFailure=true', '--console=plain']
    child = subprocess.Popen(command, cwd=root, text=True,
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    timeout = threading.Timer(120, child.kill)
    timeout.start()
    try:
        for line in child.stdout:
            print(line, end='', flush=True)
        if child.wait() != 0:
            raise RuntimeError('Missing-package application recovery audit failed')
    finally:
        timeout.cancel()
        if child.poll() is None:
            child.kill()
            child.wait()
        child.stdout.close()
    log = server.log_path.read_text()
    connects = log.count('ClientConnect: 0')
    begins = log.count('ClientBegin: 0')
    if connects != 1 or begins != 0:
        raise AssertionError(f'Expected one native connection and no admission, got {connects}/{begins}')
    if 'Unpure client detected' in log:
        raise AssertionError('Client sent an invalid pure response instead of rejecting missing content')
    result = json.loads((out / 'failure-result.json').read_text())
    result.update(nativeClientConnects=connects, nativeClientBegins=begins,
                  missingPackage=missing, matchingModuleBytesInDifferentPackage=True)
    (out / 'failure-result.json').write_text(json.dumps(result, indent=2) + '\n')
    print('PASS nativeClientConnects=1 nativeClientBegins=0 originalMenuAndLocalGameRecovered', flush=True)
