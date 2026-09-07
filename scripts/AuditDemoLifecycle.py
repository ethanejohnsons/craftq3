"""Small native demo lifecycle transcript; no real files, sockets or GUI in the C fixture."""

from pathlib import Path
import argparse
import json
import re
import subprocess
import time


root = Path(__file__).resolve().parent.parent
out = root / '.tools/demo-lifecycle-oracle'
rows = []


def call(name, commands):
    result = subprocess.run([str(out / 'probe')], input=commands, text=True,
                            capture_output=True, check=True, timeout=10)
    rows.append(dict(name=name, input=commands, output=result.stdout))
    return result.stdout


def contains(text, *expected):
    for value in expected:
        assert value in text, (value, text)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--timescale', action='store_true',
                        help='also query original dedicated registration on private loopback')
    args = parser.parse_args()
    system = (r'\sv_serverid\42\sv_pure\1\sv_paks\123 456'
              r'\sv_pakNames\other/a other/b\sv_referencedPaks\789'
              r'\sv_referencedPakNames\other/c\fs_game\othergame'
              r'\sv_cheats\0\timescale\9')
    recorded = subprocess.run([str(root / '.tools/demo-playback-oracle/probe')],
                              input='reset\ncs 1 ' + system.encode().hex()
                              + '\nrecord 8 1 42 7 9 3 12345\nstate\n',
                              text=True, capture_output=True, check=True).stdout
    record = re.findall(r'^OUTPUT (\w+)$', recorded, re.M)[-1]
    base = ('reset\nset fs_game 626173657133\nset sv_cheats 30\nset timescale 32\n'
            + 'input ' + record + 'ffffffffffffffff\nplay fixture.dm_68\n')
    started = call('systeminfo', base + 'state\n')
    contains(started, 'SET sv_killserver 2\nDISCONNECT 1\n',
             'PURE_LOADED  | \nPURE_REFERENCED  | \nSET sv_cheats 1\n',
             'FS_RESTART 12345 0\nINIT_DOWNLOADS\nSET cl_paused 0\n',
             'playing1 recording0 file17 compat1 serverId42',
             'DEMONAME ' + b'fixture.dm_68'.hex(),
             'VAR fs_game baseq3 flags0', 'VAR timescale 2 flags0')
    assert 'SET_SAFE ' not in started
    for name in ('cl_demoplaying', 'cl_demorecording', 'cl_demoName'):
        assert name not in started
    for show in (0, 1):
        ended = call('disconnect' + str(show), base + f'disconnect {show}\nstate\n')
        tail = ended.split(f'DISCONNECT {show}\n')[-1]
        contains(tail, 'CLOSE 17\n', 'playing0 recording0 file0 compat0', 'VAR timescale 2 flags0')
        assert ('VM_CALL 7 arg0 down-1' in tail) == bool(show)
        assert tail.count('WRITE_PACKET\n') == 3
    ended = call('complete_empty', base + 'complete\nstate\n')
    contains(ended, 'STRING nextdemo\n', 'playing0 recording0 file0')
    assert 'CBUF_' not in ended
    ended = call('complete_next', base + 'set nextdemo ' + b'demo next'.hex()
                 + '\ncomplete\nstate\n')
    contains(ended, 'STRING nextdemo\nSET nextdemo \nCBUF_ADD demo next\nCBUF_ADD \n\nCBUF_EXECUTE')
    # Values come from the public enum, printed by this independently compiled host.
    keys = call('keys', 'keys\n')
    values = dict((name, int(value)) for name, value in
                  re.findall(r'([a-z]+)(\d+)', keys.splitlines()[0]))
    active = base + 'active\ncatcher 0\n'
    exits = {'escape', 'letter', 'space', 'mouse', 'tab', 'enter'}
    # The public label mouse1 embeds a digit; retain its exact separately printed value.
    values['mouse'] = int(re.search(r'mouse1(\d+)', keys).group(1))
    values['f'] = int(re.search(r'f1(\d+)', keys).group(1))
    for name, key in values.items():
        for down in (0, 1):
            text = call(f'key_{name}_{down}', active + f'key {key} {down}\nstate\n')
            assert ('ERROR 3 ' in text) == (bool(down) and name in exits), (name, down, text)
    for name in ('escape', 'letter', 'space', 'mouse'):
        text = call('camera_' + name, active + 'set com_cameraMode 31\n'
                    + f'key {values[name]} 1\nstate\n')
        assert ('ERROR 3 ' in text) == (name == 'escape')
        assert 'SET nextdemo ' not in text
    text = call('escape_ui', base + 'active\ncatcher 2\nkey 27 1\nstate\n')
    contains(text, 'VM_CALL 3 arg27 down1', 'playing1')
    assert 'ERROR ' not in text
    text = call('escape_console', base + 'active\ncatcher 1\nkey 27 1\nstate\n')
    contains(text, 'ERROR 3 ')
    for key in range(256):
        text = call(f'key_domain_{key}', active + f'key {key} 1\n')
        assert ('ERROR 3 ' in text) == (key <= 127 or key == 178), (key, text)
    (out / 'lifecycle.json').write_text(json.dumps(rows, indent=2) + '\n')
    print(f'PASS {len(rows)} native lifecycle/key transcripts')
    if args.timescale:
        from NativeNetworkServer import NativeNetworkServer
        with NativeNetworkServer('demo-timescale-registration') as server:
            server.process.stdin.write(
                b'timescale\ncvarlist timescale\nset timescale 2\ntimescale\n'
                b'com_cameraMode\ncvarlist com_cameraMode\nset com_cameraMode 1\ncom_cameraMode\n')
            server.process.stdin.flush()
            deadline = time.monotonic() + 3
            while 'com_cameraMode is cheat protected.' not in server.log_path.read_text():
                if time.monotonic() > deadline:
                    raise TimeoutError('Native console query did not finish')
                time.sleep(.02)
        log = server.log_path.read_text()
        contains(log, '"timescale" is:"1^7", the default',
                 ' s     C  timescale "1"', 'timescale is cheat protected.',
                 '"com_cameraMode" is:"0^7", the default',
                 '       C  com_cameraMode "0"', 'com_cameraMode is cheat protected.')
        print('PASS native timescale/camera defaults and registration flags')


if __name__ == '__main__':
    main()
