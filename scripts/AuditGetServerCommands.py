"""Authored command-fetch, configstring, snapshot-tail and restart boundary assertions."""
from pathlib import Path
import random
import subprocess
import sys

root = Path(__file__).resolve().parent.parent
executable = sys.argv[1] if len(sys.argv) == 2 else str(root / '.tools/get-server-command-oracle/probe')
process = subprocess.Popen([executable], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
queries = 0


def ask(command):
    global queries
    queries += 1
    process.stdin.write(command + '\n')
    process.stdin.flush()
    rows = []
    while True:
        line = process.stdout.readline().strip()
        if line == 'END':
            return rows
        if not line:
            raise RuntimeError('Native observer stopped at ' + command)
        rows.append(line)


def store(number, text):
    return ask(f'store {number} ' + (text.encode('latin1').hex() or '-'))


def result(number, expected):
    rows = ask(f'get {number}')
    assert rows[0].startswith(f'RETURN {expected} '), rows
    return rows


def string(index, expected):
    rows = ask(f'string {index}')
    assert rows[0] == f'STRING {index} ' + (expected.encode('latin1').hex() or '-'), rows


def assemble(index, count, final):
    ask('reset 0')
    number = 1
    remaining = count
    while remaining > 900:
        store(number, ('bcs0' if number == 1 else 'bcs1') + f' {index} "' + 'a' * 900 + '"')
        rows = ask(f'get {number}')
        if rows[0].startswith('ERROR'):
            return rows
        remaining -= 900
        number += 1
    name = 'bcs2' if final else ('bcs0' if number == 1 else 'bcs1')
    store(number, name + f' {index} "' + 'a' * remaining + '"')
    return ask(f'get {number}')


try:
    # Synthetic payloads emitted by the already-verified ServerMessageCodec: initial gamestate;
    # [cs command1, snapshot2, print command2]; then snapshot3. No original data is used.
    ask('reset 0')
    ask('parse 1 aa4855f6f658de05761eece802768472e958bc61d9979cc5bc64551d43ad00')
    ask('parse 2 aa115b7d9c770f9f4e371106580611ff4baaaac908a45a7884c290bd3179b2b0232b')
    string(100, 'old')
    for fill in (0, 0x33, 0x7f):
        rows = ask(f'snapshot 2 {fill}')
        preserved = int.from_bytes(bytes([fill]) * 4, 'little')
        assert rows[0].startswith(f'SNAPSHOT 1 count={preserved} sequence=1 time=100 '), rows
    result(1, 1)
    string(100, 'new')
    result(2, 1)
    ask('parse 3 aaff1aaaaac90a')
    assert 'sequence=2 time=150 ' in ask('snapshot 3 127')[0]
    assert 'sequence=1 time=100 ' in ask('snapshot 2 127')[0]
    assert ask('snapshot 4 127')[0].startswith('ERROR 1 ')
    print('PASS parsed-message configstring timing and untouched snapshot command count')

    for demo in (0, 1):
        ask(f'reset {demo}')
        store(65, 'print latest')
        old = ask('get 1')
        assert old[0].startswith('RETURN 0 ' if demo else 'ERROR 1 '), old
        assert 'executed=0 ' in old[-1]
        result(65, 1)
        result(65, 1)
        assert 'executed=64 ' in result(64, 1)[-1]
        assert ask('get 66')[0].startswith('ERROR 1 ')
    print('PASS retained/duplicate/old/future command requests in live and demo modes')

    ask('reset 0')
    store(1, 'cs 100 a b')
    result(1, 1)
    string(100, 'a b')
    store(2, 'cs 100')
    result(2, 1)
    string(100, '')
    for number, text in enumerate(('bcs0 00101 a b', 'bcs1 999 c d', 'bcs2 777 e f'), 3):
        store(number, text)
        rows = result(number, int(number == 5))
    expected = 'cs 00101 "ace"'.encode().hex()
    assert ' RAW ' + expected + ' ' in rows[0]
    string(101, 'ace')
    print('PASS cs ArgsFrom2, empty cs, bcs argv2 and preserved initial index token')

    ask('reset 0')
    for number, text in enumerate(('bcs0 100 a', 'bcs2 100 b', 'bcs2 100 c'), 1):
        store(number, text)
        rows = result(number, int(number != 1))
    assert rows[0] == ('RETURN 1 ARGC 5 RAW 63732031303020226162226322 '
                       '6373 313030 6162 63 -'), rows
    string(100, 'ab c ')
    print('PASS repeated final fragment retains closing quote and exact tokenized trailing space')

    rng = random.Random(68064)
    for sample in range(500):
        ask('reset 0')
        index = str(2 + rng.randrange(1022)).zfill(rng.randrange(1, 7))
        fragments = [''.join(rng.choice('abc012 _-;:') for _ in range(rng.randrange(850)))
                     for _ in range(3)]
        for number, fragment in enumerate(fragments, 1):
            target = index if number == 1 else str(rng.randrange(1024))
            store(number, f'bcs{number - 1} {target} "{fragment}"')
        for number in (1, 2):
            rows = result(number, 0)
            assert f'executed={number} ' in rows[-1]
            string(int(index), '')
        rows = result(3, 1)
        text = ''.join(fragments)
        rewritten = f'cs {index} "{text}"'.encode().hex()
        assert ' RAW ' + rewritten + ' ' in rows[0], rows
        string(int(index), text)
    print('PASS 500 authored ordered fragment chains, exact rewritten text and state')

    for index in ('50', '100', '1023', '000000100'):
        prefix = len(f'cs {index} "')
        for total in range(8192 - prefix - 3, 8192 - prefix + 2):
            rows = assemble(index, total, True)
            fits = prefix + total + 1 < 8192
            assert rows[0].startswith('RETURN 1 ' if fits else 'ERROR 1 '), rows
    for total in (8182, 8183, 8184):
        rows = assemble('100', total, False)
        assert rows[0].startswith('RETURN 0 ' if 8 + total < 8192 else 'ERROR 1 '), rows
    print('PASS 23 assembly bounds including preserved prefix and final closing quote')

    ask('reset 0')
    ask('seed')
    for number in (60, 90, 123):
        assert ask(f'usercmd {number} 127')[0] == 'USERCMD 1 ' + 'a5' * 24
    store(1, 'map_restart')
    rows = result(1, 1)
    assert 'cmdNumber=123 cmdBytes=0 sound=0 notify=1 ' in rows[-1]
    for number in (60, 90, 123):
        assert ask(f'usercmd {number} 127')[0] == 'USERCMD 1 ' + '00' * 24
    assert ask('usercmd 59 127')[0] == 'USERCMD 0 ' + '7f' * 24
    assert ask('usercmd 124 127')[0].startswith('ERROR 1 ')
    store(2, 'disconnect "authored reason"')
    rows = ask('get 2')
    assert rows[0] == 'ERROR 2 ' + 'Server disconnected - authored reason'.encode().hex()
    assert 'executed=2 ' in rows[-1]
    print('PASS restart retained zero usercmds, unchanged numbering and disconnect error effect')
    print(f'PASS nativeQueries={queries}')
finally:
    process.stdin.close()
    process.stdout.close()
    if process.wait() != 0:
        raise RuntimeError('Native observer failed')
