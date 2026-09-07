"""Relink untouched GL1 objects with a CPU-only, authored MD3/axis fixture."""
from pathlib import Path
import shlex
import subprocess

root = Path(__file__).resolve().parent.parent
source = root / '.tools/ioquake3-source'
build = source / 'build'
out = root / '.tools/model-transform-oracle'
out.mkdir(parents=True, exist_ok=True)
observer = root / 'scripts/ModelTransformOracle.c'
includes = ['-I' + str(source / 'code'), '-I/opt/homebrew/include/SDL2']
subprocess.run(['clang', '-arch', 'arm64', '-arch', 'x86_64',
                '-mmacosx-version-min=11.0', *includes, '-DORACLE_METADATA_ADAPTER',
                '-c', str(observer), '-o', str(out / 'metadata.o')], check=True)
commands = subprocess.run(['ninja', '-C', str(build), '-t', 'commands', 'renderer_opengl1'],
                         check=True, capture_output=True, text=True).stdout.splitlines()
row = next(line for line in commands if ' -o Release/renderer_opengl1.dylib ' in line)
link = shlex.split(row.split(' && ')[1])
link[link.index('-o') + 1] = str(out / 'renderer_observed.dylib')
link.insert(link.index('-o'), str(out / 'metadata.o'))
subprocess.run(link, cwd=build, check=True)
subprocess.run(['clang', *includes, str(observer), '-o', str(out / 'probe')], check=True)
runtime = out / 'libSDL2-2.0.0.dylib'
if not runtime.exists():
    runtime.symlink_to((build / 'Release/libSDL2-2.0.0.dylib').resolve())
print(out / 'probe')
