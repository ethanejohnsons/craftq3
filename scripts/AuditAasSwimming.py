"""Seeded native/Java predictor differential with authored fluid layers and collision planes."""
from pathlib import Path
import os
import random
import struct
import subprocess

f32=lambda value:struct.unpack('<f',struct.pack('<f',value))[0]
rng=random.Random(int(os.getenv('SEED','5535001')))
count=int(os.getenv('COUNT','10000'))
lines=[]
for index in range(count):
    contact=0 if os.getenv('OPEN_ONLY') else index%2
    presence=rng.choice([2,4]);ground=rng.randrange(2)
    cf=rng.randrange(6);mf=rng.randrange(1,16);dt=rng.choice([.025,.05,.1,.2,.5,1]);events=rng.randrange(128)
    position=[f32(rng.uniform(-1000,1000)),f32(rng.uniform(-1000,1000)),f32(rng.uniform(-10,1000))]
    velocity=[f32(rng.uniform(-1200,1200)) for _ in range(3)]
    command=[f32(rng.uniform(-600,600)),f32(rng.uniform(-600,600)),rng.choice([-400,-300.0000305175781,-300,-299,0,1,1.000000119,400])]
    normal=[0,0,1]
    if os.getenv('PLANES'):
        normal=rng.choice([[0,0,1],[0,0,-1],[1,0,0],[.6,0,.8],[0,.8,-.6],[.8,0,.6]])
    if os.getenv('PLANES') and not os.getenv('START_SOLID'):
        # Start in the permitted half-space; starting inside authored ceilings is a
        # separate pre-existing dry contact domain, not a water transition.
        position=[f32((abs(value)+100)*(-1 if axis<0 else 1)) for value,axis in zip(position,normal)]
    bits0=rng.choice([0,2,4,6]);bits1=rng.choice([0,2,4,6]);threshold=f32(position[0]+rng.uniform(-100,100))
    liquid=rng.choice([0,8,16,32,56])
    lines.append(' '.join(map(str,[bits0,bits1,threshold,contact,presence,ground,cf,mf,dt,events,*position,*velocity,*command,liquid,*normal,0])))
data='\n'.join(lines)+'\n'
java=os.getenv('AUDIT_JAVA','/Users/ethan/Library/Java/JavaVirtualMachines/jbr-25.0.3/Contents/Home/bin/java')
cp=os.getenv('AUDIT_CP','.tools/swimming-oracle:craftq3-core/build/classes/java/main:craftq3-botlib/build/classes/java/main')
class_name=os.getenv('AUDIT_CLASS','AasSwimmingProbe')
native=subprocess.run(['.tools/swimming-oracle/probe'],input=data,text=True,capture_output=True,check=True,timeout=90)
production=subprocess.run([java,'-cp',cp,class_name],input=data,text=True,capture_output=True,check=True,timeout=90)
ns=[line for line in native.stdout.splitlines() if line.startswith(('MOVE ','RAW '))]
js=production.stdout.splitlines()
if len(ns)!=count*2 or len(js)!=count*2:
    Path('.tools/swimming-oracle/last-java.log').write_text(production.stdout+production.stderr)
    raise AssertionError(('row count',len(ns),len(js),count*2))
bad=[]
for index in range(count):
    n,j=ns[index*2].split(),js[index*2].split()
    # The raw record covers all numeric result fields; separate diagnostics cover query order.
    if n[1]!=j[1] or n[15:]!=j[15:] or (n[1]!="0" and ns[index*2+1]!=js[index*2+1]):
        bad.append((index,lines[index],ns[index*2:index*2+2],js[index*2:index*2+2]))
Path('.tools/swimming-oracle/differences.txt').write_text('\n'.join(map(str,bad)))
print('CASES',count,'MISMATCHES',len(bad))
for value in bad[:5]:print(value)
if bad:raise SystemExit(1)
