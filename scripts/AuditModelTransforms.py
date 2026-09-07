"""Check authored affine fixtures against unchanged native CPU MD3 submission."""
from pathlib import Path
import math
import struct
import subprocess

root = Path(__file__).resolve().parent.parent
out = root / '.tools/model-transform-oracle'
identity = [1, 0, 0, 0, 1, 0, 0, 0, 1]
cases = {
    'identity': identity,
    'zero': [0] * 9,
    'signed-zero': [-0., 0., -0., -0., -0., 0., 0., 0., 0.],
    'rank-two': [1, 0, 0, 0, 1, 0, 0, 0, 0],
    'rank-one': [1, 0, 0, 1, 0, 0, 1, 0, 0],
    'dependent-third': [1, 0, 0, 0, 1, 0, 1, 1, 0],
    'tiny': [value * 1e-5 for value in identity],
    'mirrored': [-1, 0, 0, 0, 1, 0, 0, 0, 1],
}
origins = [[0, 0, 0], [294.5761413574219, 664.5830078125, 350.9553527832031],
           [-8192, 32768, -512.125], [0.25, -0.5, 1.125]]


def f32(value):
    return struct.unpack('<f', struct.pack('<f', value))[0]


requests = [(name, list(map(f32, axes)), list(map(f32, origin)), scaled)
            for name, axes in cases.items() for origin in origins for scaled in (0, 1)]
commands = '\n'.join(str(scaled) + ' ' + ' '.join(map(str, axes + origin))
                     for _, axes, origin, scaled in requests) + '\n'
(out / 'audit.commands').write_text(commands)
result = subprocess.run([str(out / 'probe'), str(out / 'renderer_observed.dylib')],
                        input=commands, text=True, capture_output=True, timeout=30, check=True)
(out / 'audit.raw.log').write_text(result.stdout + result.stderr)
lines = result.stdout.splitlines()
assert len(lines) == len(requests), (len(lines), len(requests), result.stderr)
for (name, axes, origin, scaled), line in zip(requests, lines):
    fields = dict(part.split('=', 1) for part in line.split()[1:])
    assert fields['vertices'] == fields['indexes'] == '3', line
    matrix = list(map(f32, map(float, fields['matrix'].split(','))))
    expected_matrix = [*axes[:3], 0, *axes[3:6], 0, *axes[6:], 0, *origin, 1]
    assert matrix == expected_matrix, (name, scaled, matrix, expected_matrix)
    points = [list(map(f32, map(float, point.split(','))))
              for point in fields['points'].split(';')]
    expected = [origin, [f32(axes[i] + origin[i]) for i in range(3)],
                [f32(f32(axes[3+i] + axes[6+i]) + origin[i]) for i in range(3)]]
    assert points == expected, (name, scaled, points, expected)
    assert all(math.isfinite(value) for value in matrix), (name, scaled)
    if name in ('zero', 'signed-zero'):
        assert points == [origin] * 3
    if name == 'rank-two':
        assert points[0] != points[1] and points[0] != points[2]
    if name == 'tiny' and origin == [0, 0, 0]:
        assert points[1][0] > 0 and points[2][1] > 0 and points[2][2] > 0
report = ('Native CPU model transforms PASS: ' + str(len(requests))
          + ' authored cases; zero/signed-zero collapse, rank-two area, rank-one line,'
          + ' tiny nonzero scale, reflection and both scale flags. No GPU was initialized.\n')
(out / 'report.log').write_text(report)
print(report, end='')
