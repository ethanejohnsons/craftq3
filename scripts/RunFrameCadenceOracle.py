"""Run a short native frame observer with private settings and internal loopback only."""
from pathlib import Path
import argparse
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--mode', choices=('dedicated', 'local'), required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
out = root / '.tools/frame-cadence-oracle'
home = out / 'homes' / args.mode
vm = home / 'baseq3/vm'
vm.mkdir(parents=True, exist_ok=True)
for name in ('qagame', 'cgame', 'ui'):
    target = vm / (name + '.qvm')
    source = root / '.tools/ioquake3-qvm-audit-build/Release/baseq3/vm' / (name + '.qvm')
    if not source.is_file():
        raise SystemExit('Build the documented unchanged native QA QVMs first')
    if not target.exists():
        target.symlink_to(source)
settings = {
    'fs_basepath': str(root / '.tools/pak0-audit/games'),
    'fs_homepath': str(home), 'fs_game': '', 'com_basegame': 'baseq3',
    'dedicated': '1' if args.mode == 'dedicated' else '0',
    'net_enabled': '0', 'cl_motd': '0', 'cl_allowDownload': '0',
    'sv_master1': '', 'sv_master2': '', 'sv_master3': '', 'sv_master4': '',
    'sv_master5': '', 'sv_pure': '0', 'sv_maxclients': '4',
    'bot_enable': '1', 'g_gametype': '0', 's_initsound': '0',
    'cl_renderer': 'opengl1', 'r_fullscreen': '0', 'r_mode': '3',
    'r_displayRefresh': '0', 'r_swapInterval': '0',
    'com_maxfps': '60', 'com_fixedtime': '16',
}
command = [str(out / 'probe-client')]
for name, value in settings.items():
    command += ['+set', name, value]
command += ['+map', 'q3dm1']
log = out / (args.mode + '.log')
with log.open('w') as stream:
    try:
        result = subprocess.run(command, cwd=out, stdout=stream, stderr=subprocess.STDOUT,
                                timeout=45, check=False)
    except subprocess.TimeoutExpired:
        raise SystemExit(f'Observer stopped at its 45-second limit; inspect {log}') from None
text = log.read_text(errors='replace')
if result.returncode or 'event=FIXTURE_QUIT ' not in text:
    raise SystemExit(f'Observer did not finish normally ({result.returncode}); inspect {log}')
print(f'{args.mode}: complete, {sum(line.startswith("CADENCE ") for line in text.splitlines())} observations: {log}')
