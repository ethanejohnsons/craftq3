"""Authored native observations of type-19 endpoint metadata, never bundled into the mod."""
from pathlib import Path
import random,struct,subprocess,time,json
import argparse
parser=argparse.ArgumentParser(description="Compare authored bobbing-platform geometry fixtures with unchanged native behavior; no Java runtime parity claimed.")
parser.add_argument("pk3", type=Path)
parser.add_argument("oracle", type=Path)
parser.add_argument("--count", type=int, default=10000)
parser.add_argument("--output", type=Path, default=Path(".tools/platform-travel-oracle"))
args=parser.parse_args()
if args.count < 1 or args.count > 100000: parser.error("count must be 1..100000")
out=args.output;out.mkdir(parents=True,exist_ok=True)
def f(v):return struct.unpack('<f',struct.pack('<f',v))[0]
def bits(v):return struct.pack('<f',v).hex()
r=random.Random(83);lines=[];expected=[]
for i in range(args.count):
 lo=[f(r.uniform(-4000,2000)) for _ in range(3)];hi=[f(v+r.uniform(0,2000)) for v in lo];origin=[f(r.uniform(-1000,1000)) for _ in range(3)]
 face=11|[0,65536,131072,196608,262144,327680][i%6];axis=0 if face&65536 else 1 if face&131072 else 2
 a=r.randrange(-32768,32768);b=r.randrange(-32768,32768);edge=((a&65535)<<16)|(b&65535);edge=edge-2**32 if edge>=2**31 else edge
 center=[f(f(lo[j]+hi[j])*.5) for j in range(3)];start=center.copy();end=center.copy();current=center.copy();start[axis]=a;end[axis]=b;current[axis]=f(current[axis]+origin[axis])
 lines+=['box '+' '.join(map(str,lo+hi)),'entity 180 11 '+' '.join(map(str,origin)),f'bobfixture {face} {edge}']
 expected.append((face,edge,[bits(x) for x in start+end+current]))
(out/'geometry-input.txt').write_text('\n'.join(lines)+'\n');begin=time.monotonic()
with (out/'geometry.log').open('w') as log:
 result=subprocess.run([str(args.oracle.resolve()),str(args.pk3.resolve()),'q3dm19'],input='\n'.join(lines)+'\n',text=True,stdout=log,stderr=subprocess.STDOUT,timeout=120)
rows=[s.split() for s in (out/'geometry.log').read_text().splitlines() if s.startswith('BOBFIXTURE ')];errors=[]
for i,(actual,exp) in enumerate(zip(rows,expected)):
 found=(int(actual[1]),int(actual[2]),[bits(float(x)) for x in actual[3:]])
 if found!=exp:errors.append(dict(index=i,expected=exp,actual=found))
report=dict(exit=result.returncode,requests=len(expected),responses=len(rows),mismatches=len(errors),seconds=time.monotonic()-begin,examples=errors[:3]);print(json.dumps(report,indent=2));(out/'geometry-report.json').write_text(json.dumps(report,indent=2)+'\n')
if result.returncode or len(rows)!=len(expected) or errors:raise SystemExit(1)
