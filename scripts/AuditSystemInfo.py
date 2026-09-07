"""Native effect-selection audit. Callback cvars do not emulate native storage internals."""
from pathlib import Path
import subprocess
root = Path(__file__).resolve().parent.parent
out = root/'.tools/system-info-oracle'
cases = [f'{flags} 0 \\sv_serverid\\91\\probe\\new' for flags in range(16384)]
cases += ['-2147483648 0 \\sv_serverid\\91\\probe\\new',
          '8 1 \\sv_serverid\\91\\probe\\new',
          '8 0 \\sv_serverid\\91\\sv_cheats\\1\\probe\\new',
          '8 0 \\sv_serverid\\91\\sv_cheats\\0\\probe\\new',
          '8 0 \\sv_serverid\\91\\fs_game\\../bad']
result = subprocess.run([str(out/'probe')], input='\n'.join(cases)+'\n', text=True,
                        capture_output=True, check=True)
(out/'effects.log').write_text(result.stdout)
blocks = []
lines = []
for line in result.stdout.splitlines():
    lines.append(line)
    if line.startswith('END '):
        blocks.append(lines)
        lines = []
assert len(blocks) == len(cases)
for flags, block in enumerate(blocks[:16384]):
    assert ('SAFE probe = new' in block) == bool(flags & (8 | 128 | 2048)), (flags, block)
    assert block[0] == 'CHEAT_RESET', (flags, block)
assert 'GET probe = new FLAGS 2112' in blocks[16384]
assert len(blocks[16385]) == 1 and 'id=91' in blocks[16385][0]
assert 'CHEAT_RESET' not in blocks[16386]
assert blocks[16387][0] == 'CHEAT_RESET'
assert not any(line.startswith('SAFE fs_game') for line in blocks[16388])
assert any('invalid fs_game' in line for line in blocks[16388])
print(f'PASS {len(blocks)} native systeminfo effect queries')
