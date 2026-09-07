"""Link unchanged client wire/parser operations to authored filesystem and frame callbacks."""
from pathlib import Path
import subprocess
root=Path(__file__).resolve().parent.parent
source=root/'.tools/ioquake3-source/code';out=root/'.tools/hosted-native-client-oracle';out.mkdir(parents=True,exist_ok=True)
flags=['-std=c11','-O2','-ffunction-sections','-fdata-sections','-DLEGACY_PROTOCOL','-DSTANDALONE','-I'+str(source)]
objects=[]
for unit in ['qcommon/net_chan','qcommon/msg','qcommon/huffman','qcommon/q_shared','qcommon/cmd','client/cl_parse','client/cl_input','client/cl_net_chan','client/cl_cgame']:
 obj=out/(unit.replace('/','_')+'.o');objects.append(obj)
 extra=['-DCbuf_AddText=UnusedNativeCbufAddText'] if unit=='qcommon/cmd' else []
 subprocess.run(['clang',*flags,*extra,'-c',str(source/(unit+'.c')),'-o',str(obj)],check=True,capture_output=True)
prefix=(root/'scripts/ServerMessageOracle.c').read_text().split('int main(void) {',1)[0]
renames=['CL_WritePacket','CL_InitDownloads','CL_AddReliableCommand','Sys_Milliseconds']
stubs=out/'parser-stubs.c';stubs.write_text(''.join('#define '+name+' UnusedInherited'+name+'\n' for name in renames)+prefix)
subprocess.run(['clang',*flags,'-Wl,-dead_strip','-I'+str(root/'scripts'),str(root/'scripts/HostedNativeClientOracle.c'),str(stubs),*map(str,objects),'-o',str(out/'probe')],check=True)
print(out/'probe')
