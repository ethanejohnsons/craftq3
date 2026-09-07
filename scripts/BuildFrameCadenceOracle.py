"""Relink an existing unchanged full client with transparent frame/export observers."""
from pathlib import Path
import shlex
import subprocess

root = Path(__file__).resolve().parent.parent
source = root / '.tools/ioquake3-source'
build = source / 'build'
out = root / '.tools/frame-cadence-oracle'
out.mkdir(parents=True, exist_ok=True)
commands = subprocess.run(['ninja', '-C', str(build), '-t', 'commands', 'ioquake3'],
                          capture_output=True, text=True, check=True).stdout.splitlines()
units = {
    'qcommon/common.c': ['Com_Frame=ObservedNativeComFrame'],
    'client/cl_main.c': ['CL_Frame=ObservedNativeClientFrame'],
    'server/sv_ccmds.c': ['VM_Call=ObservedGameCall'],
    'server/sv_main.c': ['VM_Call=ObservedGameCall', 'SV_Frame=ObservedNativeServerFrame'],
    'server/sv_init.c': ['VM_Call=ObservedGameCall'],
    'server/sv_game.c': ['VM_Call=ObservedGameCall'],
    'server/sv_client.c': ['VM_Call=ObservedGameCall'],
    'server/sv_bot.c': ['VM_Call=ObservedGameCall', 'SV_BotFrame=ObservedNativeBotFrame'],
}
replacements = {}
base_compile = None
for unit, defines in units.items():
    command = shlex.split(next(row for row in commands if row.endswith('/code/' + unit)))
    for flag in ('-MD', '-MT', '-MF'):
        index = command.index(flag)
        del command[index:index + (1 if flag == '-MD' else 2)]
    original = command[command.index('-o') + 1]
    target = str(out / (unit.replace('/', '_') + '.o'))
    command[command.index('-o') + 1] = target
    if base_compile is None:
        base_compile = command.copy()
    command[1:1] = ['-D' + define for define in defines]
    subprocess.run(command, cwd=build, check=True)
    replacements[original] = target
base_compile[-1] = str(root / 'scripts/FrameCadenceOracle.c')
base_compile[base_compile.index('-o') + 1] = str(out / 'observer.o')
base_compile.insert(1, '-I' + str(source / 'code'))
subprocess.run(base_compile, cwd=build, check=True)
client_compile = base_compile.copy()
client_compile.insert(1, '-DORACLE_CLIENT_METADATA')
client_compile[client_compile.index('-o') + 1] = str(out / 'client-metadata.o')
subprocess.run(client_compile, cwd=build, check=True)
row = next(row for row in commands if ' -o Release/ioquake3 ' in row)
link = shlex.split(row.split(' && ')[1])
link = [replacements.get(argument, argument) for argument in link]
link[link.index('-o') + 1] = str(out / 'probe-client')
link.insert(link.index('-o'), str(out / 'observer.o'))
link.insert(link.index('-o'), str(out / 'client-metadata.o'))
subprocess.run(link, cwd=build, check=True)
for name in ('libSDL2-2.0.0.dylib', 'renderer_opengl1.dylib'):
    target = out / name
    if not target.exists():
        target.symlink_to(build / 'Release' / name)
print(out / 'probe-client')
