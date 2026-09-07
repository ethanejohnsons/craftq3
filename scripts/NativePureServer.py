"""Private pure-server fixture with distinct packages made only from public QA QVMs/authored data."""
from pathlib import Path
import time
from NativeNetworkServer import NativeNetworkServer
from PureFilesystemHarness import pack


class NativePureServer(NativeNetworkServer):
    def __init__(self, name='pure-wire'):
        super().__init__(name=name, pure=True)
        try:
            directory = Path(self.home.name) / 'baseq3'
            for module, filename in [('cgame', 'z_qa_cgame.pk3'), ('ui', 'y_qa_ui.pk3'),
                                     ('qagame', 'w_qa_game.pk3')]:
                source = self.root / '.tools/ioquake3-qvm-audit-build/Release/baseq3/vm' / (module+'.qvm')
                pack(directory, filename, [('vm/'+module+'.qvm', source.read_bytes())])
                (directory / 'vm' / (module+'.qvm')).unlink()
            pack(directory, 'x_qa_data.pk3', [('data/pure-wire.bin', b'CraftQ3 authored pure-wire fixture')])
            self.console('map q3dm1')
            self.console('echo CRAFTQ3_PURE_FIXTURE_READY')
            deadline = time.monotonic()+10
            while 'CRAFTQ3_PURE_FIXTURE_READY' not in self.log_path.read_text():
                if self.process.poll() is not None or time.monotonic() >= deadline:
                    raise RuntimeError('Native pure fixture failed; inspect '+str(self.log_path))
                time.sleep(.01)
        except BaseException:
            self.close()
            raise

    def console(self, command):
        self.process.stdin.write((command+'\n').encode('ascii'))
        self.process.stdin.flush()
