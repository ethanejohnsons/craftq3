"""Expose only native ping-calculation linkage; observe unchanged server timing routines."""
from pathlib import Path
import re,subprocess
root=Path(__file__).resolve().parent.parent;source=root/'.tools/ioquake3-source/code';out=root/'.tools/host-timing-oracle';out.mkdir(parents=True,exist_ok=True)
flags=['-std=c11','-O2','-fno-inline','-ffunction-sections','-fdata-sections','-DLEGACY_PROTOCOL','-DSTANDALONE','-I'+str(source)]
ir=out/'sv-main.ll';subprocess.run(['clang',*flags,'-S','-emit-llvm',str(source/'server/sv_main.c'),'-o',str(ir)],check=True,capture_output=True)
s=ir.read_text();matches=list(re.finditer(r'^define [^\n]*@SV_CalcPings\([^\n]*\)[^\n]*\{',s,re.M));assert len(matches)==1
m=matches[0];s=s[:m.start()]+m.group().replace('define internal ','define ',1)+s[m.end():];ir.write_text(s)
subprocess.run(['clang','-O2','-c',str(ir),'-o',str(out/'sv-main.o')],check=True,capture_output=True)
subprocess.run(['python3',str(root/'scripts/BuildPureServerOracle.py')],check=True,capture_output=True)
objects=[out/'sv-main.o',root/'.tools/pure-server-oracle/sv-client.o']
for unit in ['qcommon/q_shared']:
 obj=out/(unit.replace('/','_')+'.o');objects.append(obj)
 subprocess.run(['clang',*flags,'-c',str(source/(unit+'.c')),'-o',str(obj)],check=True,capture_output=True)
subprocess.run(['clang',*flags,'-Wl,-dead_strip',str(root/'scripts/HostTimingOracle.c'),*map(str,objects),'-o',str(out/'probe')],check=True)
print(out/'probe')
