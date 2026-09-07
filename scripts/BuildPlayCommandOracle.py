"""Compile unchanged native sound dispatch with an authored silent callback observer."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
source = root / '.tools/ioquake3-source'
out = root / '.tools/play-command-oracle'
out.mkdir(parents=True, exist_ok=True)
includes = ['-I' + str(source / 'code'), '-I/opt/homebrew/include/SDL2']
subprocess.run(['clang', *includes, '-c', str(source / 'code/client/snd_main.c'),
                '-o', str(out / 'snd_main.o')], check=True)
subprocess.run(['clang', *includes, str(root / 'scripts/PlayCommandOracle.c'),
                str(out / 'snd_main.o'), '-o', str(out / 'probe')], check=True)
print(out / 'probe')
for name, path in [('snd_codec', 'client/snd_codec.c'), ('q_shared', 'qcommon/q_shared.c')]:
    subprocess.run(['clang', *includes, '-c', str(source / 'code' / path),
                    '-o', str(out / (name + '.o'))], check=True)
subprocess.run(['clang', *includes, str(root / 'scripts/SoundPathOracle.c'),
                str(out / 'snd_codec.o'), str(out / 'q_shared.o'),
                '-o', str(out / 'codec-probe')], check=True)
print(out / 'codec-probe')
