"""Build unchanged native dedicated Quake as a private development UDP behavior oracle."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
# Standalone content validation permits pak0-only QA. Explicit LEGACY_PROTOCOL retains
# the unchanged legacy network implementation; no native engine source is patched.
source = root / '.tools/ioquake3-source'
build = root / '.tools/network-server-oracle-build'
if not (source / 'CMakeLists.txt').is_file():
    raise SystemExit('Supply the documented unchanged ioquake3 development checkout first')
subprocess.run(['cmake', '-S', str(source), '-B', str(build), '-G', 'Ninja',
                '-DBUILD_SERVER=ON', '-DBUILD_CLIENT=OFF', '-DBUILD_STANDALONE=ON', '-DCMAKE_C_FLAGS=-DLEGACY_PROTOCOL',
                '-DBUILD_GAME_LIBRARIES=OFF', '-DBUILD_GAME_QVMS=OFF',
                '-DBUILD_MACOS_APP=OFF', '-DUSE_CURL=OFF', '-DUSE_VOIP=OFF',
                '-DCMAKE_BUILD_TYPE=Release'], check=True)
subprocess.run(['cmake', '--build', str(build), '--parallel', '4'], check=True)
print(build / 'Release/ioq3ded')
