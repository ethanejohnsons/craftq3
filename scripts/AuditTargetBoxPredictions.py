"""Compare production Java target prediction with unchanged native operation outputs.
Build both Java classes and the native observer; javac TargetBoxPredictionProbe.java into
.tools/target-box-oracle before running. COUNT defaults to 10000; verified run uses50000.
"""
from pathlib import Path
import random,struct,subprocess,os
f=lambda x:struct.unpack('<f',struct.pack('<f',x))[0]
r=random.Random(76429);count=int(os.getenv('COUNT','10000'));rows=[]
for i in range(count):
 origin=[f(r.uniform(-300,300)) for k in range(3)];v=[f(r.uniform(-1500,1500)) for k in range(3)];c=[f(r.uniform(-450,450)) for k in range(3)]
 low=[f(origin[k]+r.uniform(-250,250)) for k in range(3)];high=[f(low[k]+r.uniform(0,150)) for k in range(3)]
 a=[r.choice([0,2,4,6]),r.choice([0,2,4,6]),f(origin[0]+r.uniform(-50,50)),r.randrange(2),r.choice([2,4]),r.randrange(2),r.randrange(6),r.randrange(31),r.choice([.025,.05,.1,.2,.5,1]),0,*origin,*v,*c,r.choice([0,8,16,32,56]),*r.choice([[0,0,1],[0,0,-1],[1,0,0],[.6,0,.8],[0,.8,-.6]]),0,*low,*high];rows.append(' '.join(map(str,a)))
ns=subprocess.run([os.getenv('NATIVE_BOX','.tools/target-box-oracle/probe')],input='\n'.join('hit '+r for r in rows)+'\n',text=True,capture_output=True,check=True).stdout.splitlines()
cp='.tools/target-box-oracle:craftq3-core/build/classes/java/main:craftq3-botlib/build/classes/java/main'
js=subprocess.run([str(Path(os.getenv('JAVA_HOME',''))/'bin/java') if os.getenv('JAVA_HOME') else 'java','-cp',cp,'TargetBoxPredictionProbe'],input='\n'.join(rows)+'\n',text=True,capture_output=True,check=True).stdout.splitlines()
if len(ns)!=count or len(js)!=count*2:print('ROWS',len(ns),len(js),js[:20]);raise SystemExit(1)
bad=[]
for i in range(count):
 n=ns[i].split();j=js[i*2].split();raw=js[i*2+1].split()
 if n[1]!=j[1] or n[2:5]!=[j[-3].removeprefix('count'),j[-2].removeprefix('presence'),j[-1].removeprefix('hash')] or n[-1]!=raw[-1]:bad.append((i,rows[i],ns[i],js[i*2:i*2+2]))
print('COUNT',count,'MISMATCHES',len(bad));print(*bad[:5],sep='\n');Path('.tools/target-box-oracle/prediction-differences.txt').write_text('\n'.join(map(str,bad)));raise SystemExit(bool(bad))
