"""Build authored jump-pad observers against the ignored official development reference.

Only LLVM definition headers are renamed for transparent public-operation logging;
no operation instructions are inspected, changed or used as implementation recipes.
The resulting native executable is development-only and is never bundled.
"""
from pathlib import Path
import subprocess,re
source=Path('.tools/ioquake3-source');out=Path('.tools/suspended-item-oracle');base=source/'build/CMakeFiles/ioquake3.dir/code'
out.mkdir(parents=True,exist_ok=True)
objects=[p for p in (base/'botlib').glob('*.o') if p.name not in {'be_aas_sample.c.o','be_aas_move.c.o','be_ai_move.c.o','be_aas_bspq3.c.o','be_aas_reach.c.o'}]
for relative,names in [('botlib/be_aas_sample.c',['AAS_PointAreaNum','AAS_LinkEntityClientBBox','AAS_TraceClientBBox']),('botlib/be_aas_move.c',['AAS_ClientMovementHitBBox']),('botlib/be_aas_bspq3.c',[]),('botlib/be_ai_move.c',[]),('botlib/be_aas_reach.c',['AAS_GetJumpPadInfo','AAS_AreaVolume']),('qcommon/q_math.c',['VectorNormalize','VectorNormalize2'])]:
 stem=Path(relative).stem; ir=out/(stem+'-observed.ll');obj=out/(stem+'-observed.o')
 subprocess.run(['clang','-std=c11','-O2','-ffp-contract=off','-fno-inline','-I'+str(source/'code'),'-S','-emit-llvm',str(source/'code'/relative),'-o',str(ir)],check=True,capture_output=True)
 text=ir.read_text()
 for name in names:
  pattern=r'^define [^\n]*@'+name+r'\([^\n]*\)[^\n]*\{'
  matches=list(re.finditer(pattern,text,re.M));assert len(matches)==1,(name,len(matches))
  header=matches[0].group()
  declaration=header.replace('define ','declare ',1).replace(' local_unnamed_addr','').removesuffix('{').strip()
  text=text[:matches[0].start()]+header.replace('@'+name+'(','@Observed_'+name+'(')+text[matches[0].end():]
  text+='\n'+declaration+'\n'
 ir.write_text(text)
 subprocess.run(['clang','-O2','-ffp-contract=off','-c',str(ir),'-o',str(obj)],check=True,capture_output=True)
 objects.append(obj)
objects += [base/'qcommon'/name for name in ('q_shared.c.o','md4.c.o')]
subprocess.run(['clang','-std=c11','-O2','-ffp-contract=off','-Wl,-dead_strip','-I'+str(source/'code'),'-Iscripts','-I/opt/homebrew/opt/libarchive/include','scripts/JumpPadItemOracle.c','scripts/JumpPadItemObserver.c',*map(str,objects),'-L/opt/homebrew/opt/libarchive/lib','-larchive','-o',str(out/'probe-observed')],check=True)
