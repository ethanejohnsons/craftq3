"""Bounded demo-clock replay against unchanged native operations and production Java.

BuildDemoClockOracle.py builds the native reference. Select Java25 using JAVA_HOME;
this driver compiles only its owned clock/auditor into an ignored class directory.
"""
from pathlib import Path
import os
import random
import subprocess

root = Path(__file__).resolve().parent.parent
out = root / '.tools/demo-clock-oracle'
classes = out / 'classes'
classes.mkdir(parents=True, exist_ok=True)
java_home = os.environ.get('JAVA_HOME')
java = str(Path(java_home) / 'bin/java') if java_home else 'java'
javac = str(Path(java_home) / 'bin/javac') if java_home else 'javac'
subprocess.run([javac, '-Xlint:all', '-Werror', '-d', str(classes),
    str(root / 'craftq3-client/src/main/java/dev/bluevista/craftq3/client/DemoServerClock.java'),
    str(root / 'scripts/DemoClockJavaOracle.java')], check=True)
rng = random.Random(680050)
rows = []
frames = 0
for trial in range(400):
    rows.append('reset')
    time = rng.randrange(100, 5000)
    for packet in range(24):
        kind = rng.choices([0, 1, 2], [2, 20, 1])[0]
        if kind == 2:
            time = rng.randrange(100, 5000)
        elif kind == 1:
            time += rng.randrange(1, 301)
        rows.append(f'step {kind} {time} {rng.choice([0, 0, 0, 1, 2, 4])}')
    rows.append('step 3 0 0')
    realtime = rng.randrange(1000, 10000)
    wall = rng.choice([0, 1, 1000])
    for frame in range(20):
        realtime += rng.randrange(201)
        wall += rng.randrange(501)
        rows.append(f'run {realtime} {wall} {rng.randrange(-100, 101)} '
                    f'{rng.randrange(5) == 0:d} {rng.randrange(2)} {rng.choice([0, .5, 1, 2])}')
        frames += 1
for trial in range(2000):
    snapshot = rng.randrange(1000, 1000000)
    realtime = rng.randrange(1000, 1000000)
    delta = rng.randrange(-500, 501)
    previous = snapshot - rng.randrange(1000)
    old = max(0, snapshot - rng.randrange(-200, 501))
    selected = max(0, old + rng.randrange(-200, 201))
    rows += ['reset', f'seed 8 {realtime} {snapshot} 1 {rng.randrange(8)} '
             f'{rng.randrange(2)} {rng.randrange(2)} {delta} {selected} {old} {previous}',
             f'demoseed 1 {rng.choice([0,1,2,16,4096,4097,9000])} '
             f'{rng.choice([0,1,1000])} {rng.randrange(1000)} {rng.randrange(2000)} 100 200',
             'step 1 2000000 0',
             f'run {realtime} {rng.randrange(-1, 3000)} {rng.randrange(-100,101)} '
             f'{rng.randrange(2)} {rng.randrange(2)} {rng.choice([0,.5,1,2])}']
    frames += 1
input_text = '\n'.join(rows) + '\n'
(out / 'corpus-input.txt').write_text(input_text)
paths = [out / 'native-output.txt', out / 'java-output.txt']
for command, path in zip([[str(out / 'probe')], [java, '-cp', str(classes), 'DemoClockJavaOracle']], paths):
    with path.open('w') as output:
        result = subprocess.run(command, input=input_text, text=True, stdout=output, stderr=subprocess.PIPE)
    if result.returncode:
        raise AssertionError((command, result.returncode, result.stderr[-2000:]))
with paths[0].open() as native, paths[1].open() as production:
    count = 0
    while True:
        first, second = native.readline(), production.readline()
        if first != second:
            raise AssertionError((count, first.rstrip(), second.rstrip()))
        if not first:
            break
        count += 1
print(f'DEMO CLOCK frames={frames} observedLines={count} exact; priming, read-ahead, '
      'map transitions, freeze, timedemo, timescale and duration ring')
