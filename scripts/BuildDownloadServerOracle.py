"""Observe unchanged native pure validation using public layouts and definition-only linkage hooks."""
from pathlib import Path
import re, subprocess
root=Path(__file__).resolve().parent.parent
source=root/'.tools/ioquake3-source/code';out=root/'.tools/download-server-oracle';out.mkdir(parents=True,exist_ok=True)
flags=['-std=c11','-O2','-fno-inline','-ffp-contract=off','-ffunction-sections','-fdata-sections','-DLEGACY_PROTOCOL','-DSTANDALONE','-I'+str(source)]
ir=out/'sv-client.ll'
subprocess.run(['clang',*flags,'-S','-emit-llvm',str(source/'server/sv_client.c'),'-o',str(ir)],check=True,capture_output=True)
text=ir.read_text();declarations=[]
for name in ['SV_BeginDownload_f','SV_NextDownload_f','SV_StopDownload_f','SV_DoneDownload_f','SV_DropClient','SV_SendClientGameState']:
    matches=list(re.finditer(r'^define [^\n]*@'+name+r'\([^\n]*\)[^\n]*\{',text,re.M));assert len(matches)==1,name
    m=matches[0];header=m.group()
    if name in ['SV_DropClient','SV_SendClientGameState']:
        declarations.append(header.replace('define internal ','declare ',1).replace('define ','declare ',1).replace(' local_unnamed_addr','').removesuffix('{').strip())
        replacement=header.replace('@'+name+'(','@ObservedOriginal_'+name+'(')
    else:replacement=header.replace('define internal ','define ',1)
    text=text[:m.start()]+replacement+text[m.end():]
ir.write_text(text+'\n'+'\n'.join(declarations)+'\n')
subprocess.run(['clang','-O2','-c',str(ir),'-o',str(out/'sv-client.o')],check=True,capture_output=True)
objects=[out/'sv-client.o']
for name in ['cmd','q_shared','msg','huffman']:
    obj=out/(name+'.o');objects.append(obj)
    subprocess.run(['clang',*flags,'-c',str(source/'qcommon'/(name+'.c')),'-o',str(obj)],check=True,capture_output=True)
subprocess.run(['clang',*flags,'-Wl,-dead_strip',str(root/'scripts/DownloadServerOracle.c'),*map(str,objects),'-o',str(out/'probe')],check=True)
print(out/'probe')
