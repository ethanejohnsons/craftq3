"""Compare direct native pure commands with a declared formatting contract, not engine recipes."""
from pathlib import Path
import itertools
import subprocess

root = Path(__file__).resolve().parent.parent
rows = []
expected = []
transcripts = ['', '@ 0 ', '111 222 @ 333 444 ', '-123 456 @ -123 456 -2147483648 ',
               'x'*1000, 'x'*1015, 'x'*1023, 'x'*1024, 'x'*4096, 'x'*16000]
for pure, state, demo, server_id, transcript in itertools.product(
        [0, 1], range(10), [0, 1], [-2147483648, -1, 0, 123, 2147483647], transcripts):
    rows.extend([f'seed {pure} {server_id} {state} {demo} '+transcript.encode('latin1').hex(),
                 'send', 'reset'])
    expected.extend([('cp '+str(server_id)+' '+transcript).encode('latin1')[:1023], b'vdr'])
result = subprocess.run([str(root/'.tools/pure-client-oracle/probe')],
                        input='\n'.join(rows)+'\n', text=True, capture_output=True, check=True)
commands = []
references = []
for line in result.stdout.splitlines():
    if line.startswith('RELIABLE '):
        fields = line.split()
        if fields[1] != '0':
            raise AssertionError('Pure operations must use the ordinary reliable-command flag')
        commands.append(bytes.fromhex(fields[2]))
    elif line.startswith('END '):
        references.append(int(line.split()[1]))
if commands != expected or references != [1, 0] * (len(expected)//2):
    raise AssertionError('Native pure command formatting/reference count differs')
print(f'PURE_CLIENT operations={len(commands)} exactCommands={len(commands)} stateCases={len(expected)//2} '
      'maxCommandBytes=1023 checksumCalls=onePerCp/zeroPerVdr')
