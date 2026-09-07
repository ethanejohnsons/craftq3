"""Relink unchanged native filesystem with an authored CPU-only initialization-boundary observer."""
from pathlib import Path
import shlex
import subprocess

root=Path(__file__).resolve().parent.parent
source=root/'.tools/ioquake3-source'
build=root/'.tools/network-server-oracle-build'
out=root/'.tools/pure-filesystem-oracle'
out.mkdir(parents=True,exist_ok=True)
commands=subprocess.run(['ninja','-C',str(build),'-t','commands','ioq3ded'],capture_output=True,text=True,check=True).stdout.splitlines()
compile_fs=shlex.split(next(row for row in commands if row.endswith('/code/qcommon/files.c')))
for flag in ('-MD','-MT','-MF'):
    index=compile_fs.index(flag);del compile_fs[index:index+(1 if flag=='-MD' else 2)]
compile_fs[compile_fs.index('-o')+1]=str(out/'files-observed.o')
compile_fs.insert(1,'-DFS_InitFilesystem=Observed_FS_InitFilesystem')
subprocess.run(compile_fs,cwd=build,check=True)
observer=compile_fs.copy();observer.remove('-DFS_InitFilesystem=Observed_FS_InitFilesystem')
observer[observer.index('-o')+1]=str(out/'observer.o');observer[-1]=str(root/'scripts/PureFilesystemOracle.c')
observer.insert(1,'-I'+str(source/'code'))
subprocess.run(observer,cwd=build,check=True)
link=shlex.split(next(row for row in commands if ' -o Release/ioq3ded ' in row).split(' && ')[1])
index=link.index('CMakeFiles/ioq3ded.dir/code/qcommon/files.c.o');link[index]=str(out/'files-observed.o')
link.insert(index,str(out/'observer.o'));link[link.index('-o')+1]=str(out/'probe')
subprocess.run(link,cwd=build,check=True)
print(out/'probe')
