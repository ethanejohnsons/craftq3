"""Compile unchanged server message policy; definition-only hooks observe host callbacks."""
from pathlib import Path
import re
import subprocess
root=Path(__file__).resolve().parent.parent
source=root/'.tools/ioquake3-source/code';out=root/'.tools/server-session-oracle';out.mkdir(parents=True,exist_ok=True)
flags=['-std=c11','-O2','-fno-inline','-ffp-contract=off','-ffunction-sections','-fdata-sections','-DLEGACY_PROTOCOL','-DSTANDALONE','-I'+str(source)]
ir=out/'sv-client.ll'
subprocess.run(['clang',*flags,'-S','-emit-llvm',str(source/'server/sv_client.c'),'-o',str(ir)],check=True,capture_output=True)
text=ir.read_text();declarations=[]
for name in ['SV_ExecuteClientCommand','SV_ClientThink','SV_ClientEnterWorld','SV_DropClient','SV_SendClientGameState']:
    pattern=r'^define [^\n]*@'+name+r'\([^\n]*\)[^\n]*\{'
    matches=list(re.finditer(pattern,text,re.M));assert len(matches)==1,name
    match=matches[0];header=match.group()
    declaration=header.replace('define internal ','declare ',1).replace('define ','declare ',1).replace(' local_unnamed_addr','').replace(' unnamed_addr','').removesuffix('{').strip()
    declarations.append(declaration)
    text=text[:match.start()]+header.replace('@'+name+'(','@ObservedOriginal_'+name+'(')+text[match.end():]
ir.write_text(text+'\n'+'\n'.join(declarations)+'\n')
subprocess.run(['clang','-O2','-ffp-contract=off','-c',str(ir),'-o',str(out/'sv-client.o')],check=True,capture_output=True)
objects=[out/'sv-client.o']
for name in ['msg','huffman','q_shared']:
    obj=out/(name+'.o');objects.append(obj)
    subprocess.run(['clang',*flags,'-c',str(source/'qcommon'/(name+'.c')),'-o',str(obj)],check=True,capture_output=True)
subprocess.run(['clang',*flags,'-Wl,-dead_strip',str(root/'scripts/ServerSessionOracle.c'),*map(str,objects),'-o',str(out/'probe')],check=True)
print(out/'probe')
