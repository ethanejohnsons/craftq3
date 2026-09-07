"""Compile original demo exports unchanged apart from a definition-only completion observer hook."""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parent.parent
source = root / '.tools/ioquake3-source/code'
out = root / '.tools/demo-playback-oracle'
out.mkdir(parents=True, exist_ok=True)
# Only the exact exported declaration is rewritten; no function body is inspected or changed.
original = (source / 'client/cl_main.c').read_text()
observed, count = re.subn(r'^void CL_DemoCompleted\( void \)',
                         'void DemoOracleOriginalCompleted( void )', original, flags=re.M)
if count != 1:
    raise RuntimeError('Expected the known exact CL_DemoCompleted declaration')
observed, count = re.subn(r'^void CL_ConnectionlessPacket\( netadr_t from, msg_t \*msg \)',
                         'void DemoOracleOriginalConnectionlessPacket( netadr_t from, msg_t *msg )',
                         observed, flags=re.M)
if count != 1:
    raise RuntimeError('Expected the known exact CL_ConnectionlessPacket declaration')
unit = out / 'cl_main-observed.c'
observed = observed.replace('#include "client.h"', '#include "client.h"\n'
                            'void CL_DemoCompleted(void);\n'
                            'void CL_ConnectionlessPacket(netadr_t from,msg_t *msg);', 1)
unit.write_text(observed)
parser = (source/'client/cl_parse.c').read_text()
parser, count = re.subn(r'^void CL_ParseServerMessage\( msg_t \*msg \)',
                       'void DemoOracleOriginalParseServerMessage( msg_t *msg )', parser, flags=re.M)
if count != 1:
    raise RuntimeError('Expected the known exact CL_ParseServerMessage declaration')
parser_unit = out/'cl_parse-observed.c'
parser_unit.write_text(parser)
objects = []
for name, path in [('cl_main', unit), ('cl_parse', parser_unit), ('q_shared', source/'qcommon/q_shared.c'),
                   ('msg', source/'qcommon/msg.c'), ('huffman', source/'qcommon/huffman.c')]:
    obj = out / (name+'.o')
    subprocess.run(['clang','-std=c11','-O2','-fno-caret-diagnostics','-ffp-contract=off','-ffunction-sections',
                    '-fdata-sections','-I'+str(source),'-I'+str(source/'client'),
                    '-I'+str(source/'thirdparty/SDL2-2.32.8/include'),
                    '-c',str(path),'-o',str(obj)],check=True)
    objects.append(str(obj))
subprocess.run(['clang','-std=c11','-O2','-Wl,-dead_strip','-I'+str(source),
                str(root/'scripts/DemoPlaybackOracle.c'),*objects,'-o',str(out/'probe')],check=True)
print(out/'probe')
