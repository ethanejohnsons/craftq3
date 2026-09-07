"""Actual CPU application against a private pure server, including a disallowed local override."""
from pathlib import Path
import os
import subprocess
import tempfile
import threading
import time
from NativePureServer import NativePureServer
from PureFilesystemHarness import pack

root = Path(__file__).resolve().parent.parent
out = root / '.tools/pure-remote-application'
out.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='games-', dir=out) as temporary, NativePureServer('pure-application') as server:
    games = Path(temporary)
    base = games / 'baseq3'
    base.mkdir()
    os.link(root / '.tools/pak0-audit/games/baseq3/pak0.pk3', base / 'pak0.pk3')
    for archive in (Path(server.home.name) / 'baseq3').glob('*.pk3'):
        os.link(archive, base / archive.name)
    # This higher-priority client-only pack must disappear from the server's eligible view.
    # Only unchanged public source-built QVMs and authored invalid-map bytes are packaged here.
    modules = root / '.tools/ioquake3-qvm-audit-build/Release/baseq3/vm'
    pack(base, 'zz_client_override.pk3', [
        ('vm/ui.qvm', (modules / 'ui.qvm').read_bytes()),
        ('vm/cgame.qvm', (modules / 'cgame.qvm').read_bytes()),
        ('maps/q3dm17.bsp', b'Authored disallowed-map sentinel')])
    command = [str(root / 'gradlew'), ':craftq3-fabric:auditRemoteApplication',
               '-Pq3RemoteAuditPort=' + str(server.address[1]),
               '-Pq3RemoteAuditGames=' + str(games), '-Pq3RemoteAuditOutput=' + str(out),
               '-Pq3RemoteAuditPure=true', '--console=plain']
    child = subprocess.Popen(command, cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    timeout = threading.Timer(120, child.kill)
    timeout.start()
    try:
        for line in child.stdout:
            print(line, end='', flush=True)
            if line.strip() == 'RESTART_LEVEL':
                server.console('map_restart 0')
            elif line.strip() == 'CHANGE_LEVEL q3dm17':
                server.console('map q3dm17')
        if child.wait() != 0:
            raise RuntimeError('Pure application audit failed')
    finally:
        timeout.cancel()
        if child.poll() is None:
            child.kill()
            child.wait()
        child.stdout.close()
    deadline = time.monotonic() + 2
    while 'ClientDisconnect: 0' not in server.log_path.read_text() and time.monotonic() < deadline:
        time.sleep(.01)
    log = server.log_path.read_text()
    if 'ClientDisconnect: 0' not in log or log.count('ClientBegin: 0') < 2:
        raise AssertionError('Missing native pure admission/map/disconnect evidence')
    if 'Unpure client detected' in log:
        raise AssertionError('Native server rejected the pure response')
    print('PASS native pure admission on both maps, disallowed local override filtered and restored')
