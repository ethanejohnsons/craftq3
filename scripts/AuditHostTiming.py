"""Compare rate delay, acknowledgement averages and ordinary userinfo bounds to native exports."""
from pathlib import Path
import argparse,os,random,subprocess,json
root=Path(__file__).resolve().parent.parent
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--classpath',default=str(root/'craftq3-server/build/classes/java/main')+':'+str(root/'craftq3-core/build/classes/java/main'));args=p.parse_args()
cases=[]
for family in (4,6):
 for rate in (1000,3000,25000,90000):
  for size in (4,100,1300,16384):
   for lo,hi in ((0,0),(1,0),(0,1),(2000,1000),(3000,25000)):
    for scale in (.5,1,1.3,2):
     for elapsed in (0,17,1000):cases.append(f'rate {rate} {lo} {hi} {size} 100 {100+elapsed} {scale} {family}')
rng=random.Random(42)
for state,entity,bot in ((0,0,0),(3,1,0),(4,0,0),(4,1,0),(4,1,1)):
 for count in (0,1,2,16,32):
  for _ in range(12):
   frames=[]
   for i in range(count):
    sent=i*100;ack=sent+rng.choice((0,10,100,133,999,1200,10000));frames.extend((sent,-1 if rng.random()<.2 else ack))
   cases.append(f'ping {state} {entity} {bot} {count} '+ ' '.join(map(str,frames)))
for fps in (10,20,30,125):
 for rate in ('','0','500','1000','3000','25000','90000','999999','bad'):
  for snaps in ('','0','1','20','100'):
   info='\\name\\QA'+('\\rate\\'+rate if rate else '')+('\\snaps\\'+snaps if snaps else '')
   cases.append(f'info 0 0 0 {fps} '+info)
source='\n'.join(cases)+'\n'
a=subprocess.run([str(root/'.tools/host-timing-oracle/probe')],input=source,text=True,capture_output=True,check=True).stdout.splitlines()
b=subprocess.run([str(Path(os.environ['JAVA_HOME'])/'bin/java'),'-cp',args.classpath,str(root/'scripts/AuditHostTiming.java')],input=source,text=True,capture_output=True,check=True).stdout.splitlines()
assert len(a)==len(b)==len(cases),(len(a),len(b),len(cases))
for case,left,right in zip(cases,a,b):assert left==right,(case,left,right)
(root/'.tools/host-timing-oracle/comparison.json').write_text(json.dumps([dict(input=x,native=y,java=z) for x,y,z in zip(cases,a,b)],indent=2)+'\n')
print(f'PASS {len(cases)} native rate/ping/userinfo comparisons')
