"""Verify both unchanged original Demos menus through the production file-list bridge.

The existing Java observer supplies inert demo filenames and captures commands;
it does not execute playback, start networking, show a GUI or extract media.
Compile the client first and select Java25 with JAVA_HOME.
"""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parent.parent
out = root / '.tools/demo-catalog-audit'
out.mkdir(parents=True, exist_ok=True)
java_home = os.environ.get('JAVA_HOME')
java = str(Path(java_home) / 'bin/java') if java_home else 'java'
classpath = os.pathsep.join(str(path) for path in sorted(root.glob('craftq3-*/build/classes/java/main')))
modern = root / '.tools/ioquake3-qvm-audit-build/Release/baseq3/vm/ui.qvm'
for name, vm, extension, command in [
    ('retail', '-', 'dm3', 'demo PROTO68.dm3'),
    ('modern', str(modern), '.dm_68', 'demo proto68.dm_68'),
]:
    path = out / (name + '-catalog-menu.log')
    result = subprocess.run([java, '-cp', classpath, str(root / 'scripts/AuditDemosMenu.java'),
        str(root / '.tools/pak0-audit/games'), vm, 'm580:445,178'], text=True, capture_output=True)
    path.write_text(result.stdout + result.stderr)
    assert result.returncode == 0, (name, result.returncode, str(path))
    assert 'FILELIST directory=demos extension=' + extension + ' ' in result.stdout, str(path)
    assert 'COMMAND ' + command + ' ARGV' in result.stdout, str(path)
    assert 'COMMAND demo LEGACY.dm3' not in result.stdout, str(path)
    print(f'DEMO CATALOG {name}: original menu emitted {command!r}; unsupported legacy entry omitted')
