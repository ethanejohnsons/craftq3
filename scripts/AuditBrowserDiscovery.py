"""Replay seeded master packets and status operations against unchanged native and Java.

No actual socket or DNS operation is performed. Build the native observer and the
core/client Java classes first; JAVA_HOME must select Java 25 for the driver.
"""
from pathlib import Path
import os, subprocess, random, struct, argparse
ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--native', type=Path, default=ROOT / '.tools/browser-discovery-oracle/probe')
args = parser.parse_args()
out = ROOT / '.tools/browser-discovery-oracle'
out.mkdir(parents=True, exist_ok=True)
java_home = os.environ.get('JAVA_HOME')
java = str(Path(java_home) / 'bin/java') if java_home else 'java'
javac = str(Path(java_home) / 'bin/javac') if java_home else 'javac'
classes = out / 'java-classes'
classes.mkdir(exist_ok=True)
classpath = os.pathsep.join(str(ROOT / p) for p in [
    'craftq3-core/build/classes/java/main', 'craftq3-client/build/classes/java/main'])
subprocess.run([javac, '-cp', classpath, '-d', str(classes),
                str(ROOT / 'scripts/BrowserDiscoveryJavaOracle.java')], check=True)
rng = random.Random(190268)
rows=[]
for trial in range(3000):
 ext=trial%2;header=b'getserversExtResponse' if ext else b'getserversResponse'
 packet=b'\xff'*4+header+rng.choice([b'',b' ',b'\n',b' ignored '])
 entries=[]
 for i in range(rng.randrange(351)):
  ipv6=rng.randrange(3)==0
  address=rng.randbytes(16 if ipv6 else 4)
  entry=(b'/' if ipv6 else b'\\')+address+struct.pack('!H',rng.randrange(65536))
  if entries and rng.randrange(8)==0:entry=rng.choice(entries)
  entries.append(entry)
 packet+=b''.join(entries)+rng.choice([b'\\EOT',b'\\',b'/',b'X',b'',b'\\EOTabc\\'])
 if rng.randrange(3)==0:packet=packet[:rng.randrange(4+len(header),len(packet)+1)]
 rows.append(f'raw {ext} {trial%2} {trial%37} '+packet.hex())
rows+=['clear','tick 1000 750']
addr=lambda i:f'127.0.0.1:{28000+i}'.encode().hex()
for trial in range(15000):
 op=rng.randrange(100);index=rng.randrange(24)
 if op<50:
  cap=rng.choice([1,2,8,32,64,127,1024])
  rows.append(f'status {cap} '+addr(index))
 elif op<70:
  lines=[rng.randbytes(rng.randrange(80)).replace(b'\n',b'x') for _ in range(rng.randrange(5))]
  if trial%29==0:lines=[rng.choice([b'a',b'x',b'%',b'\xff'])*rng.randrange(900,3000),b'after']
  payload=b'\n'.join(lines)+rng.choice([b'',b'\n',b'\0'])
  rows.append(f'response {28000+index} '+(payload.hex() or '-'))
 elif op<80:rows.append('cancel '+addr(index))
 elif op<95:
  now=rng.choice([rng.randrange(100000),rng.randrange(-2147483648,-2147482648),rng.randrange(2147482648,2147483648)])
  rows.append(f'tick {now} '+str(rng.choice([0,1,750,1000,2147483647])))
 else:rows.append('clear')
text='\n'.join(rows)+'\n'
(out / 'corpus-input.txt').write_text(text)
commands = [[str(args.native)], [java, '-cp', str(classes) + os.pathsep + classpath, 'BrowserDiscoveryJavaOracle']]
results=[]
for cmd in commands:
 p=subprocess.run(cmd,input=text,text=True,capture_output=True)
 if p.returncode:raise AssertionError((cmd,p.returncode,p.stderr[-3000:]))
 lines=[l for l in p.stdout.splitlines() if l.strip()]
 results.append(lines)
print('LINES',*[len(x) for x in results])
if results[0]!=results[1]:
 for i,(n,j) in enumerate(zip(*results)):
  if n!=j:
   print('MISMATCH',i,'NATIVE',n[:300],'JAVA',j[:300])
   print('PRECEDING',results[0][max(0,i-5):i]);break
 (out / 'native-corpus-output.txt').write_text('\n'.join(results[0]))
 (out / 'java-corpus-output.txt').write_text('\n'.join(results[1]))
 raise SystemExit(1)
print('BROWSER masterPackets=3000 statusOperations=15000 allObservedOutputsExact')
