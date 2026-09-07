"""Build a QA-only public SV_LinkEntity observer against the existing native server build."""
from pathlib import Path
import shlex
import subprocess

root = Path(__file__).resolve().parent.parent
source = root / '.tools/ioquake3-source'
build = root / '.tools/server-startup-build'
out = root / '.tools/entity-link-oracle'
out.mkdir(parents=True, exist_ok=True)
commands = subprocess.run(['ninja', '-C', str(build), '-t', 'commands', 'ioq3ded'],
                          capture_output=True, text=True, check=True).stdout.splitlines()


def compile_source(name, destination, replacement=None, define=None):
    command = shlex.split(next(row for row in commands if row.endswith('/code/qcommon/' + name)))
    for flag in ('-MD', '-MT', '-MF'):
        index = command.index(flag)
        del command[index:index + (1 if flag == '-MD' else 2)]
    command[command.index('-o') + 1] = str(destination)
    command.insert(1, '-ffp-contract=off')
    if define:
        command.insert(1, '-D' + define)
    if replacement:
        command[-1] = str(replacement)
        command.insert(1, '-I' + str(source / 'code'))
    subprocess.run(command, cwd=build, check=True)


compile_source('cm_load.c', out / 'cm-load-observed.o', define='CM_LoadMap=ObservedEntityLinkLoadMap')
compile_source('cm_test.c', out / 'cm-test-unfused.o', define='CM_BoxLeafnums=ObservedEntityBoxLeafnums')
compile_source('q_math.c', out / 'q-math-unfused.o')
compile_source('cm_load.c', out / 'observer.o', replacement=root / 'scripts/EntityLinkOracle.c')
link_line = next(row for row in commands if ' -o Release/ioq3ded ' in row)
link = shlex.split(link_line.split(' && ')[1])
for name, replacement in [('cm_load.c', 'cm-load-observed.o'),
                          ('cm_test.c', 'cm-test-unfused.o'), ('q_math.c', 'q-math-unfused.o')]:
    link[link.index('CMakeFiles/ioq3ded.dir/code/qcommon/' + name + '.o')] = str(out / replacement)
link.insert(link.index('-o'), str(out / 'observer.o'))
link[link.index('-o') + 1] = str(out / 'probe')
subprocess.run(link, cwd=build, check=True)
print(out / 'probe')
