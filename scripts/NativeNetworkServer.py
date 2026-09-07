"""Private loopback native server for wire interoperability checks, never a mod dependency."""
from pathlib import Path
import json
import socket
import subprocess
import tempfile
import time


class NativeNetworkServer:
    def __init__(self, name='wire', protocol=68, pure=False):
        self.root = Path(__file__).resolve().parent.parent
        self.out = self.root / '.tools/network-server-oracle'
        self.out.mkdir(exist_ok=True)
        self.home = tempfile.TemporaryDirectory(prefix=name+'-', dir=self.out)
        self.log_path = self.out / (name+'.log')
        self.log = self.log_path.open('w')
        self.process = None
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.socket.bind(('127.0.0.1', 0))
        self.socket.settimeout(.25)
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as reserved:
            reserved.bind(('127.0.0.1', 0))
            self.address = ('127.0.0.1', reserved.getsockname()[1])
        vm = Path(self.home.name) / 'baseq3/vm'
        vm.mkdir(parents=True)
        for module in ('qagame', 'cgame', 'ui'):
            original = self.root / '.tools/ioquake3-qvm-audit-build/Release/baseq3/vm' / (module+'.qvm')
            if not original.is_file():
                self.close()
                raise FileNotFoundError('Build the unchanged QA QVMs first: '+str(original))
            (vm/(module+'.qvm')).symlink_to(original)
        settings = {
            'fs_basepath': str(self.root/'.tools/pak0-audit/games'),
            'fs_homepath': self.home.name, 'fs_game': '', 'dedicated': '1',
            'com_basegame': 'baseq3', 'com_gamename': 'Quake3Arena',
            'net_enabled': '1', 'net_ip': '127.0.0.1', 'net_port': str(self.address[1]),
            'sv_master1': '', 'sv_master2': '', 'sv_master3': '', 'sv_master4': '', 'sv_master5': '',
            'sv_pure': '1' if pure else '0', 'sv_maxclients': '4', 'sv_fps': '20', 'bot_enable': '0',
            'g_gametype': '0', 'g_log': '', 'sv_hostname': 'CraftQ3 private native oracle',
            'sv_strictAuth': '0', 'sv_floodProtect': '0', 'com_legacyprotocol': '68', 'com_protocol': str(protocol),
        }
        command = [str(self.root/'.tools/network-server-oracle-build/Release/ioq3ded')]
        for key,value in settings.items():
            command += ['+set', key, value]
        command += ['+map', 'q3dm1']
        try:
            self.process = subprocess.Popen(command, cwd=self.home.name, stdin=subprocess.PIPE,
                                            stdout=self.log, stderr=subprocess.STDOUT)
            deadline = time.monotonic()+15
            while time.monotonic()<deadline:
                if self.process.poll() is not None:
                    raise RuntimeError('Native server stopped; inspect '+str(self.log_path))
                self.send(b'getinfo CraftQ3Ready')
                try:
                    data = self.receive()
                    if data.startswith(b'\xff'*4+b'infoResponse\n'):
                        self.info = data
                        return
                except TimeoutError:
                    pass
            raise TimeoutError('Native loopback server not ready; inspect '+str(self.log_path))
        except BaseException:
            self.close()
            raise

    def send(self, payload):
        self.socket.sendto(b'\xff'*4+payload, self.address)

    def receive(self):
        data, peer = self.socket.recvfrom(65536)
        if peer != self.address:
            raise AssertionError('Unexpected peer')
        return data

    def close(self):
        if self.process is not None and self.process.poll() is None:
            try:
                self.process.stdin.write(b'quit\n'); self.process.stdin.flush()
                self.process.wait(timeout=3)
            except (OSError, subprocess.TimeoutExpired):
                self.process.terminate()
                try: self.process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    self.process.kill(); self.process.wait()
        if self.process is not None and self.process.stdin is not None:
            self.process.stdin.close()
        self.socket.close()
        self.log.close()
        self.home.cleanup()

    def __enter__(self): return self
    def __exit__(self, *ignored): self.close()


if __name__ == '__main__':
    records = []
    with NativeNetworkServer('challenge') as server:
        print('INFO', server.info, flush=True)
        for query in (b'getchallenge', b'getchallenge 12345', b'getchallenge 12345 Quake3Arena',
                      b'getchallenge -1 Quake3Arena', b'getchallenge 0 Quake3Arena'):
            server.send(query)
            try: response = server.receive()
            except TimeoutError: response = b''
            row = {'query': query.decode('ascii'), 'responseHex': response.hex(),
                   'response': response[4:].decode('latin1')}
            records.append(row)
            print(json.dumps(row), flush=True)
            time.sleep(.2)
        (server.out/'challenge.json').write_text(json.dumps(records,indent=2)+'\n')
