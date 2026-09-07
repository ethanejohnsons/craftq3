"""Isolated calls to original PlayDemo/Disconnect/Completed with authored external effects."""
from pathlib import Path
import re
import subprocess

root=Path(__file__).resolve().parent.parent
source=root/'.tools/ioquake3-source/code'
out=root/'.tools/demo-lifecycle-oracle'
out.mkdir(parents=True,exist_ok=True)
native=(source/'client/cl_main.c').read_text()
# Exact declaration rewrites only. The original bodies are retained without inspection.
definitions={
 r'^void CL_DemoCompleted\( void \)':'void DemoOracleOriginalCompleted( void )',
 r'^void CL_ConnectionlessPacket\( netadr_t from, msg_t \*msg \)':'void DemoOracleOriginalConnectionlessPacket( netadr_t from, msg_t *msg )',
 r'^void CL_Disconnect\( qboolean showMainMenu \)':'void DemoLifecycleNativeDisconnect( qboolean showMainMenu )',
 r'^void CL_FlushMemory\(void\)':'void DemoLifecycleNativeFlushMemory(void)',
 r'^void CL_StartHunkUsers\( qboolean rendererOnly \)':'void DemoLifecycleNativeStartHunkUsers( qboolean rendererOnly )',
 r'^void CL_InitDownloads\(void\)':'void DemoLifecycleUnusedInitDownloads(void)',
 r'^void CL_NextDownload\(void\)':'void DemoLifecycleUnusedNextDownload(void)',
}
for pattern,replacement in definitions.items():
    native,count=re.subn(pattern,replacement,native,flags=re.M)
    if count!=1:raise RuntimeError('Exact declaration changed: '+pattern)
native=native.replace('#include "client.h"','#include "client.h"\nvoid CL_DemoCompleted(void);\nvoid CL_ConnectionlessPacket(netadr_t from,msg_t *msg);',1)
unit=out/'cl_main-observed.c';unit.write_text(native)
parser=(source/'client/cl_parse.c').read_text()
parser,count=re.subn(r'^void CL_ParseServerMessage\( msg_t \*msg \)',
                     'void DemoLifecycleNativeParseServerMessage( msg_t *msg )',parser,flags=re.M)
if count!=1:raise RuntimeError('Exact parser declaration changed')
parse_unit=out/'cl_parse-observed.c';parse_unit.write_text(parser)
keys=(source/'client/cl_keys.c').read_text()
for pattern,replacement in {
 r'^void Console_Key \(int key\)':'void DemoLifecycleUnusedConsoleKey(int key)',
 r'^void Message_Key\( int key \)':'void DemoLifecycleUnusedMessageKey( int key )',
 r'^void CL_ParseBinding\( int key, qboolean down, unsigned time \)':'void DemoLifecycleUnusedBinding( int key, qboolean down, unsigned time )',
}.items():
    keys,count=re.subn(pattern,replacement,keys,flags=re.M)
    if count!=1:raise RuntimeError('Exact key helper declaration changed: '+pattern)
keys=keys.replace('#include "client.h"','#include "client.h"\nvoid Console_Key(int key);\nvoid Message_Key(int key);\nvoid CL_ParseBinding(int key,qboolean down,unsigned time);',1)
key_unit=out/'cl_keys-observed.c';key_unit.write_text(keys)
objects=[]
for name,path in [('cl_main',unit),('cl_parse',parse_unit),('cl_keys',key_unit),('q_shared',source/'qcommon/q_shared.c'),('msg',source/'qcommon/msg.c'),('huffman',source/'qcommon/huffman.c')]:
    obj=out/(name+'.o')
    subprocess.run(['clang','-std=c11','-O2','-fno-caret-diagnostics','-ffp-contract=off','-ffunction-sections','-fdata-sections',
                    '-I'+str(source),'-I'+str(source/'client'),'-I'+str(source/'thirdparty/SDL2-2.32.8/include'),'-c',str(path),'-o',str(obj)],check=True)
    objects.append(str(obj))
subprocess.run(['clang','-std=c11','-O2','-fno-caret-diagnostics','-Wl,-dead_strip','-I'+str(source),'-I'+str(root/'scripts'),
                str(root/'scripts/DemoLifecycleOracle.c'),*objects,'-o',str(out/'probe')],check=True)
print(out/'probe')
