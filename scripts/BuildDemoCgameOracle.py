"""Reuse authored cgame command fixtures with observable isolated system-info effects."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
source = root/'.tools/ioquake3-source/code'
out = root/'.tools/demo-cgame-oracle'
out.mkdir(parents=True, exist_ok=True)
original = (root/'scripts/ServerMessageOracle.c').read_text().split('int main(void) {',1)[0]
# Only our authored callback stubs gain logging; no native source is changed.
original = original.replace('effectCount++;','printf("EFFECT %s\\n", __func__); effectCount++;')
stub = out/'parser-stubs.c'
stub.write_text('#define Com_Error UnusedInheritedComError\n'+original)
host = (root/'scripts/GetServerCommandOracle.c').read_text()
host = host.replace('} else if (!strcmp(operation, "store")) {',
                    '} else if (!strcmp(operation, "mode")) {\n'
                    'if(scanf("%d %d", &clc.demorecording, &clc.demowaiting)!=2)return 2;\n'
                    'puts("MODE");\n'
                    '} else if (!strcmp(operation, "store")) {')
host = host.replace('static void state(void) {',
                    'static void state(void) {\n'
                    'printf("DEMO recording%d waiting%d\\n",clc.demorecording,clc.demowaiting);')
host_path = out/'demo-cgame-observer.c'
host_path.write_text(host)
objects = [root/'.tools/get-server-command-oracle'/name for name in [
    'qcommon_net_chan.o','qcommon_msg.o','qcommon_huffman.o','qcommon_q_shared.o',
    'qcommon_cmd.o','client_cl_parse.o','client_cl_cgame.o']]
if not all(path.is_file() for path in objects):
    subprocess.run(['python3',str(root/'scripts/BuildGetServerCommandOracle.py')],check=True)
subprocess.run(['clang','-std=c11','-O2','-Wl,-dead_strip','-I'+str(source),
                '-I'+str(root/'scripts'),str(host_path),str(stub),
                *map(str,objects),'-o',str(out/'probe')],check=True)
print(out/'probe')
