"""Bounded call observations for demo completion, recording gates and cgame effects."""
from pathlib import Path
import json
import struct
import subprocess

root = Path(__file__).resolve().parent.parent
rows = []

def observe(commands, cgame=False):
    binary = root / ('.tools/demo-cgame-oracle/probe' if cgame else '.tools/demo-playback-oracle/probe')
    result = subprocess.run([str(binary)], input='\n'.join(commands)+'\n', text=True,
                            capture_output=True, check=True)
    rows.append(dict(commands=commands, cgame=cgame, output=result.stdout))
    return result.stdout

def read(data):
    return observe(['reset', 'input '+(data.hex() or '-'), 'read'])

for sequence in [-2147483648, -1, 0, 42, 2147483647]:
    assert 'COMPLETED' in read(struct.pack('<ii', sequence, -1))
for length in range(8):
    assert 'COMPLETED' in read(bytes(length))
for length in [-2147483648, -2]:
    assert 'UNSAFE_READ_REQUEST' in read(struct.pack('<ii', 42, length))
for length in [16385, 2147483647]:
    assert 'ERROR 1 ' in read(struct.pack('<ii', 42, length))
for length in [0, 1, 16384]:
    data = bytes(length)
    assert f'PARSE 42 {length} 0 0 ' in read(struct.pack('<ii', 42, length)+data)
for length in [1, 8, 16384]:
    for actual in sorted({0, length//2, length-1}):
        result = read(struct.pack('<ii', 42, length)+bytes(actual))
        assert 'COMPLETED' in result and 'PARSE ' not in result
for state in range(10):
    result = observe(['reset', f'record {state} 1 42 7 9 3 12345', 'state'])
    assert ('recording1' in result) == (state == 8)
result = observe(['reset','record 8 1 42 7 9 3 12345',
                  'snapshot 43 1 0 1000 0','snapshot 44 0 0 1050 0','snapshot 45 1 0 1100 0'])
assert 'SNAPSHOT valid0 number0 delta0 waiting1 recording1' in result
assert 'SNAPSHOT valid1 number44 delta-1 waiting0 recording1' in result
assert 'SNAPSHOT valid1 number45 delta44 waiting0 recording1' in result
result = observe(['reset','record 8 1 42 7 9 3 12345',
                  'packet 1 2b000000aabbcc','packet 0 2c000000ddeeff','stop','state'])
assert '2b00000003000000aabbcc' not in result
assert result.split('OUTPUT ')[-1].strip().startswith('290000000a000000bf8aa4a995adc63b69052c00000003000000ddeeffffffffffffffffff')
result = observe(['reset','baseline 0 7 123.5','record 8 1 42 7 9 3 12345','state'])
assert 'OUTPUT 290000000a000000bf8aa4a995adc63b6905' in result

for demo in [0,1]:
    for number in [-1,0,36,37,99,100,101]:
        result = observe([f'reset {demo}','store 100 7072696e742078',f'get {number}'],True)
        if number <= 36:
            assert ('RETURN 0 ' if demo else 'ERROR 1 ') in result
            assert 'executed=0' in result
        elif number == 101:
            assert 'ERROR 1 ' in result
        elif number < 100:
            assert 'RETURN 1 ARGC 0 RAW -' in result and f'executed={number}' in result
        else:
            assert 'RETURN 1 ARGC 2 ' in result
text = 'cs 1 "\\sv_serverid\\42\\sv_pure\\1\\sv_paks\\123 456\\sv_pakNames\\a b\\sv_referencedPaks\\123\\sv_referencedPakNames\\a\\fs_game\\other\\foo\\bar"'
for demo in [0,1]:
    result = observe([f'reset {demo}','store 1 '+text.encode().hex(),'get 1','string 1'],True)
    assert 'serverId=42' in result and text[6:-1].encode().hex() in result
    if demo:
        assert 'EFFECT ' not in result
    else:
        assert 'EFFECT FS_PureServerSetLoadedPaks' in result and 'EFFECT Cvar_SetSafe' in result
for waiting in [0,1]:
    result = observe(['reset 0',f'mode 1 {waiting}','parse 41 bf8aa4a995adc63b6905'],True)
    assert result.count(f'DEMO recording1 waiting{waiting}') == 2

target = root/'.tools/demo-playback-oracle/boundaries.json'
target.write_text(json.dumps(rows,indent=2))
print(f'Demo native boundaries PASS: {len(rows)} isolated call transcripts; {target}')
