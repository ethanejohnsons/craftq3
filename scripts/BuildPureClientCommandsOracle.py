"""Isolate unchanged exported pure-client operations with a reliable-command argument observer.

Only the CL_AddReliableCommand definition header is renamed for interposition;
native operation instructions are not inspected or changed.
"""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parent.parent
source = root / '.tools/ioquake3-source/code'
out = root / '.tools/pure-client-oracle'
out.mkdir(parents=True, exist_ok=True)
flags = ['-std=c11', '-O2', '-fno-inline', '-ffp-contract=off', '-ffunction-sections',
         '-fdata-sections', '-DSTANDALONE', '-DLEGACY_PROTOCOL', '-DUSE_INTERNAL_SDL_HEADERS',
         '-I'+str(source), '-I'+str(source/'thirdparty/SDL2-2.32.8/include')]
ir = out / 'cl-main.ll'
subprocess.run(['clang', *flags, '-S', '-emit-llvm', str(source/'client/cl_main.c'),
                '-o', str(ir)], check=True, capture_output=True)
text = ir.read_text()
pattern = r'^define [^\n]*@CL_AddReliableCommand\([^\n]*\)[^\n]*\{'
matches = list(re.finditer(pattern, text, re.M))
if len(matches) != 1:
    raise RuntimeError('Expected exactly one reliable-command definition header')
match = matches[0]
header = match.group()
declaration = header.replace('define ', 'declare ', 1).replace(' local_unnamed_addr', '').removesuffix('{').strip()
text = text[:match.start()] + header.replace('@CL_AddReliableCommand(', '@Observed_CL_AddReliableCommand(') + text[match.end():]
ir.write_text(text + '\n' + declaration + '\n')
subprocess.run(['clang', '-O2', '-ffp-contract=off', '-c', str(ir), '-o', str(out/'cl-main.o')], check=True)
subprocess.run(['clang', *flags, '-c', str(source/'qcommon/q_shared.c'),
                '-o', str(out/'q-shared.o')], check=True, capture_output=True)
subprocess.run(['clang', '-std=c11', '-O2', '-Wl,-dead_strip', '-I'+str(source),
                str(root/'scripts/PureClientCommandsOracle.c'), str(out/'cl-main.o'),
                str(out/'q-shared.o'), '-o', str(out/'probe')], check=True)
print(out/'probe')
