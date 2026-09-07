"""Observe native client cvar registration without renderer initialization.

Only the exact LLVM definition header for CL_InitRef is renamed. Its declaration
is supplied by an authored no-op; native CL_Init instructions remain unchanged.
No engine body is read, inspected or translated. The observer aborts on file I/O
and stops at the cl_maxPing callback before those paths execute.
"""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parent.parent
source = root / '.tools/ioquake3-source/code'
out = root / '.tools/browser-init-oracle'
out.mkdir(parents=True, exist_ok=True)
flags = ['-std=c11', '-O2', '-fno-inline', '-flto', '-DSTANDALONE', '-DLEGACY_PROTOCOL',
         '-DUSE_INTERNAL_SDL_HEADERS', '-I' + str(source),
         '-I' + str(source / 'thirdparty/SDL2-2.32.8/include')]
ir = out / 'cl-main.ll'
subprocess.run(['clang', *flags, '-S', '-emit-llvm', str(source / 'client/cl_main.c'),
                '-o', str(ir)], check=True, capture_output=True)
content = ir.read_text()
headers = re.findall(r'^define [^\n]*@CL_InitRef\([^\n]*\)[^\n]*\{', content, re.MULTILINE)
if len(headers) != 1:
    raise RuntimeError('Expected exactly one CL_InitRef definition header')
header = headers[0]
declaration = header.replace('define ', 'declare ', 1).replace(' local_unnamed_addr', '')
declaration = declaration.removesuffix('{').strip()
content = content.replace(header, header.replace('@CL_InitRef(', '@Observed_CL_InitRef('), 1)
ir.write_text(content + '\n' + declaration + '\n')
objects = [out / 'client_cl_main.o', out / 'qcommon_q_shared.o']
subprocess.run(['clang', '-O2', '-flto', '-c', str(ir), '-o', str(objects[0])], check=True)
subprocess.run(['clang', *flags, '-c', str(source / 'qcommon/q_shared.c'), '-o', str(objects[1])],
               check=True, capture_output=True)
subprocess.run(['clang', *flags, '-Wl,-dead_strip', str(root / 'scripts/BrowserPingDefaultsOracle.c'),
                *map(str, objects), '-o', str(out / 'probe')], check=True)
print(out / 'probe')
