"""Observe unchanged CL_ParseDownload through authored file/command callbacks."""
from pathlib import Path
import subprocess
root=Path(__file__).resolve().parent.parent;source=root/'.tools/ioquake3-source/code';out=root/'.tools/download-client-oracle';out.mkdir(parents=True,exist_ok=True)
flags=['-std=c11','-O2','-ffunction-sections','-fdata-sections','-DLEGACY_PROTOCOL','-DSTANDALONE','-I'+str(source)]
objects=[]
for unit in ['qcommon/net_chan','qcommon/msg','qcommon/huffman','qcommon/q_shared','client/cl_parse']:
 obj=out/(unit.replace('/','_')+'.o');objects.append(obj);subprocess.run(['clang',*flags,'-c',str(source/(unit+'.c')),'-o',str(obj)],check=True,capture_output=True)
prefix=(root/'scripts/ServerMessageOracle.c').read_text().split('int main(void) {',1)[0]
renames=['Com_Error','CL_NextDownload','CL_AddReliableCommand','CL_WritePacket','FS_BaseDir_FOpenFileWrite_HomeData','FS_BaseDir_Rename_HomeData','FS_Write','FS_FCloseFile','Cvar_Set','Cvar_SetValue']
stubs=out/'parser-stubs.c';stubs.write_text(''.join('#define '+name+' UnusedInherited'+name+'\n' for name in renames)+prefix)
subprocess.run(['clang',*flags,'-Wl,-dead_strip','-I'+str(root/'scripts'),str(root/'scripts/DownloadClientOracle.c'),str(stubs),*map(str,objects),'-o',str(out/'probe')],check=True)
print(out/'probe')
