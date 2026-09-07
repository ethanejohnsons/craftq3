"""Compare authored pure-admission cases against unchanged SV_VerifyPaks_f."""
from pathlib import Path
import argparse,json,os,subprocess
root=Path(__file__).resolve().parent.parent
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--classpath',default=str(root/'craftq3-server/build/classes/java/main')+':'+str(root/'craftq3-core/build/classes/java/main'))
args=parser.parse_args()
cases=[]
for pure in (0,1):
 for feed in (0,12345,-1,-2147483648):
  for ident in (0,99,100,101,150,2147483647):
   for packs in ([],[333],[111,222,333],[444,333],[333,333],[999]):
    checksum=feed
    for value in packs:checksum^=value
    checksum^=len(packs)
    for alteration in (0,1):
     text='cp '+str(ident)+' 111 222 @ '+' '.join(map(str,[*packs,checksum^alteration]))
     cases.append((pure,feed,text,False))
for text in ('cp','cp 99 garbage','cp 100','cp 100 111 222 @','cp 100 112 222 @ 0','cp 100 111 223 @ 0','cp 100 111 222 ! 0','cp 100 111 222 @ bogus','cp 100 111 222 @ 0 extra'):
 cases.append((1,0,text,text.endswith('@ bogus')))
native_input=''.join(f'seed {pure} 120 100 {feed} 0 0 111 222\n{text}\n' for pure,feed,text,_ in cases)
native=subprocess.run([str(root/'.tools/pure-server-oracle/probe')],input=native_input,text=True,capture_output=True,check=True)
states=[line.split()[1:] for line in native.stdout.splitlines() if line.startswith('STATE ')][1::2]
java_input=''.join(f'{pure}|100|{feed}|{text}\n' for pure,feed,text,_ in cases)
java=Path(os.environ['JAVA_HOME'])/'bin/java'
result=subprocess.run([str(java),'-cp',args.classpath,str(root/'scripts/AuditPureServerPolicy.java')],input=java_input,text=True,capture_output=True,check=True)
actual=result.stdout.splitlines();assert len(states)==len(actual)==len(cases)
records=[]
for case,state,value in zip(cases,states,actual):
 pure,feed,text,strict=case
 expected='IGNORED' if state[0]=='0' else ('ACCEPTED' if state[1]=='1' else 'REJECTED')
 if strict:assert expected=='ACCEPTED' and value=='REJECTED',(case,state,value)
 else:assert value==expected,(case,state,value)
 records.append(dict(pure=pure,feed=feed,command=text,native=expected,java=value,intentionalStrictNumber=strict))
out=root/'.tools/pure-server-oracle';(out/'comparison.json').write_text(json.dumps(records,indent=2)+'\n')
(out/'transcript.log').write_text(native.stdout)
print(f'PASS {len(cases)} pure-policy cases; {sum(x[3] for x in cases)} documented strict-number difference')
