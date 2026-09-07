"""Capture native count bounds, short-input corruption and ignored padding with authored bytes."""
from pathlib import Path
import random
import subprocess
import sys

root = Path(__file__).resolve().parent.parent
executable = sys.argv[1] if len(sys.argv) == 2 else str(root / '.tools/connect-compression-oracle/probe')
process = subprocess.Popen([executable], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)


def ask(command):
    process.stdin.write(command + '\n')
    process.stdin.flush()
    result = process.stdout.readline().strip()
    if not result:
        raise RuntimeError('Native observer stopped')
    return result


try:
    for command in (b'connect aa', b'connect "\\name\\Player"'):
        encoded = ask('data ' + command.hex()).split()[1]
        decoded = ask('decompress 12 16384 ' + encoded)
        assert bytes.fromhex(decoded.split()[4]) != b'\xff' * 4 + command
        print('SHORT raw=' + command.hex(), 'packet=' + encoded, decoded)
    random = random.Random(42)
    packets = []
    for _ in range(20):
        prior = bytes(random.randrange(1, 128) for _ in range(20 + random.randrange(300)))
        ask('data ' + (b'connect ' + prior).hex())
        packet = bytes.fromhex(ask('data ' + b'connect a'.hex()).split()[1])
        assert packet[:-1].hex() == 'ffffffff636f6e6e65637420000186'
        packets.append(packet.hex())
    print('PADDING repeatedSameInput distinctOutputs=' + str(len(set(packets))))
    for packet in packets:
        print(packet)
    encoded = ask('data ' + (b'connect ' + b'a' * 32).hex()).split()[1]
    for capacity in (16, 24, 32, 44, 16384):
        result = ask(f'decompress 12 {capacity} ' + encoded)
        assert int(result.split()[1]) == min(capacity, 44)
        print('CAPACITY', capacity, result)
    zero = ask('decompress 12 16384 ffffffff636f6e6e656374200000')
    assert zero.split()[4] == 'ffffffff636f6e6e65637420'
    print('ZERO', zero)
finally:
    process.stdin.close()
    process.stdout.close()
    if process.wait() != 0:
        raise RuntimeError('Native observer failed')
