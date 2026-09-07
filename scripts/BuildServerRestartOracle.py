"""Build a QA-only export-call observer around unchanged local ioquake3 sources."""
from pathlib import Path
import shlex
import subprocess

root = Path(__file__).resolve().parent.parent
source = root / '.tools/ioquake3-source'
build = root / '.tools/server-startup-build'
out = root / '.tools/server-restart-oracle'
if not (source / 'CMakeLists.txt').is_file():
    raise SystemExit('Supply the documented unchanged ioquake3 checkout under .tools/ioquake3-source')
out.mkdir(parents=True, exist_ok=True)
subprocess.run(['cmake', '-S', str(source), '-B', str(build), '-G', 'Ninja',
                '-DBUILD_SERVER=ON', '-DBUILD_CLIENT=OFF', '-DBUILD_STANDALONE=ON',
                '-DBUILD_GAME_LIBRARIES=OFF', '-DBUILD_GAME_QVMS=OFF',
                '-DBUILD_MACOS_APP=OFF', '-DUSE_CURL=OFF', '-DUSE_VOIP=OFF',
                '-DCMAKE_BUILD_TYPE=Release'], check=True)
subprocess.run(['cmake', '--build', str(build), '--parallel', '4'], check=True)
commands = subprocess.run(['ninja', '-C', str(build), '-t', 'commands', 'ioq3ded'],
                          capture_output=True, text=True, check=True).stdout.splitlines()
compile_vm = shlex.split(next(row for row in commands if row.endswith('/code/qcommon/vm.c')))
for flag in ('-MD', '-MT', '-MF'):
    index = compile_vm.index(flag)
    del compile_vm[index:index + (1 if flag == '-MD' else 2)]
compile_vm[compile_vm.index('-o') + 1] = str(out / 'vm-ded-observed.o')
compile_vm.insert(1, '-DVM_Call=ObservedNativeVmCall')
subprocess.run(compile_vm, cwd=build, check=True)
compile_observer = compile_vm.copy()
compile_observer.remove('-DVM_Call=ObservedNativeVmCall')
compile_observer[compile_observer.index('-o') + 1] = str(out / 'ded-observer.o')
compile_observer[-1] = str(root / 'scripts/ServerRestartOracle.c')
compile_observer.insert(1, '-I' + str(source / 'code'))
subprocess.run(compile_observer, cwd=build, check=True)
link_line = next(row for row in commands if ' -o Release/ioq3ded ' in row)
link = shlex.split(link_line.split(' && ')[1])
index = link.index('CMakeFiles/ioq3ded.dir/code/qcommon/vm.c.o')
link[index] = str(out / 'vm-ded-observed.o')
link.insert(index, str(out / 'ded-observer.o'))
link[link.index('-o') + 1] = str(out / 'probe-ded')
subprocess.run(link, cwd=build, check=True)
print(out / 'probe-ded')
