"""Exercise Java protocol sessions against a temporary private native dedicated server."""
from pathlib import Path
import os
import subprocess
import threading
from NativeNetworkServer import NativeNetworkServer
root = Path(__file__).resolve().parent.parent
java = str(Path(os.environ['JAVA_HOME'])/'bin/java') if 'JAVA_HOME' in os.environ else 'java'
with NativeNetworkServer('session') as server:
    classpath = os.environ.get('CRAFTQ3_AUDIT_CLASSPATH') or os.pathsep.join(
        str(root/(module+'/build/classes/java/main')) for module in ('craftq3-core', 'craftq3-platform'))
    command = [java, '-cp', classpath,
               str(root/'scripts/AuditNativeNetworkSession.java'), str(server.address[1]), str(server.out)]
    child = subprocess.Popen(command, cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    timeout = threading.Timer(45, child.kill)
    timeout.start()
    try:
        for line in child.stdout:
            print(line, end='', flush=True)
            if line.strip() == 'CHANGE_LEVEL q3dm17':
                server.process.stdin.write(b'map q3dm17\n')
                server.process.stdin.flush()
        if child.wait() != 0:
            raise RuntimeError('Java native-network audit failed')
    finally:
        timeout.cancel()
        if child.poll() is None:
            child.kill()
            child.wait()
        child.stdout.close()
    server.log.flush()
    # The explicit disconnect command must reach native game lifecycle before oracle shutdown.
    import time
    deadline = time.monotonic()+2
    while 'ClientDisconnect: 0' not in server.log_path.read_text() and time.monotonic()<deadline:
        time.sleep(.01)
    if 'ClientDisconnect: 0' not in server.log_path.read_text():
        raise AssertionError('Native server did not process the final disconnect')
