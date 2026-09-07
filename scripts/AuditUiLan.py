"""Compare production UI LAN records to unchanged native calls, without sending packets."""
import argparse
import ipaddress
import json
import random
import subprocess
from pathlib import Path
from UiLanHarness import ROOT, UiLan, hextext


class JavaLan(UiLan):
    def __init__(self, classpath):
        self.process = subprocess.Popen(['java', '-cp', classpath, 'UiLanJavaOracle'],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE, text=True)
        self.history = []


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', default=str(ROOT / '.tools/ui-lan-oracle/classes'))
    parser.add_argument('--pairs', type=int, default=10000)
    parser.add_argument('--operations', type=int, default=10000)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    rng = random.Random(68327)
    comparisons = 0
    excluded_bad_addresses = 0
    transcript = []
    out = ROOT / '.tools/ui-lan-oracle'

    with UiLan() as native, JavaLan(args.classpath) as java:
        def run(command):
            nonlocal comparisons
            expected = native.command(command)
            actual = java.command(command)
            comparisons += 1
            transcript.append((command, expected))
            if expected != actual:
                (out / 'failure.json').write_text(json.dumps(transcript, indent=2) + '\n')
                raise AssertionError(f'query={comparisons} {command}\nnative={expected}\njava={actual}')
            return expected

        def compare_info(source, index):
            nonlocal excluded_bad_addresses
            if run(f'kind {source} {index}') == ['KIND 0']:
                # Native NA_BAD formatting reuses another endpoint's static display buffer.
                # The production placeholder deliberately has no address to connect to.
                excluded_bad_addresses += 1
                return
            run(f'info {source} {index} 1024')

        def text(maximum):
            alphabet = ['a', 'b', 'Z', ' ', '^', '1', '\\', '"', ';', '\n', '\t', '\x80', '\xff', '\xe0', '\xc0']
            return ''.join(rng.choices(alphabet, k=rng.randrange(maximum + 1)))

        def endpoint():
            port = rng.choice([0, 1, 27960, 65535, rng.randrange(65536)])
            if rng.randrange(3):
                return f'192.0.2.{rng.randrange(256)}:{port}'
            groups = [rng.randrange(65536) if rng.randrange(3) == 0 else 0 for _ in range(8)]
            groups[0], groups[1] = 0x2001, 0xdb8
            return '[' + ':'.join(f'{group:x}' for group in groups) + ']:' + str(port)

        def seed(source, index):
            numbers = [rng.choice([-2147483648, -1, 0, 1, 2, 2147483647, rng.randrange(-10000, 10001)]) for _ in range(11)]
            run('seed ' + ' '.join(map(str, [source, index, hextext(endpoint()), hextext(text(140)),
                                                hextext(text(70)), hextext(text(70)), *numbers])))

        run('reset')
        # Full-capacity getters and write extents, independent of active count.
        for source in [-1, 0, 1, 2, 3, 4]:
            capacity = 4096 if source in [1, 2] else 128
            for index in [-2, -1, 0, 1, capacity - 1, capacity, capacity + 1]:
                for method in ['ping', 'visible']:
                    run(f'{method} {source} {index}')
                for size in [-1, 0, 1, 2, 8, 256]:
                    run(f'address {source} {index} {size}')
                    run(f'info {source} {index} {size}')
        for address in ['[::1]:27960', '[::ffff:192.0.2.1]:27960', '[::192.0.2.1]:27960',
                        '[::0.0.2.3]:27960', '[fe80::1%1]:27960', '[2001:db8::1%1]:27960',
                        '[ff01::1%1]:27960', '[ff02::1%1]:27960', '[ff05::1%1]:27960',
                        '127.0.0.1', '127.0.0.1:0', '!fixture-failure!']:
            run('reset')
            run('add 3 ' + hextext('fixture') + ' ' + hextext(address))
            if address != '!fixture-failure!':
                run('address 3 0 128'); run('info 3 0 1024')
            else:
                compare_info(3, 0)

        # Independently seeded records exercise every scalar and string comparison key.
        run('reset')
        for _ in range(args.pairs):
            source = rng.choice([0, 1, 2, 3])
            seed(source, 0); seed(source, 1)
            key = rng.randrange(-1, 7)
            direction = rng.choice([-2147483648, -1, 0, 1, 2, 2147483647])
            run(f'compare {source} {key} {direction} 0 1')
            run(f'address {source} 0 128')
            run(f'info {source} 0 1024')

        # Sequence edits retain old metadata across count changes, removes and reuse.
        run('reset')
        addresses = [f'127.0.0.{index}:27960' for index in range(1, 33)] + ['!fixture-failure!']
        for _ in range(args.operations):
            source = rng.choice([-1, 0, 1, 2, 3, 4])
            index = rng.choice([-2, -1, 0, 1, 2, 63, 127, 128, 4095, 4096])
            choice = rng.randrange(8)
            if choice <= 1:
                run('add ' + str(source) + ' ' + hextext(text(120)) + ' ' + hextext(rng.choice(addresses)))
            elif choice == 2:
                run('remove ' + str(source) + ' ' + hextext(rng.choice(addresses)))
            elif choice == 3:
                run(f'mark {source} {index} {rng.choice([-2, 0, 1, 3])}')
            elif choice == 4:
                run(f'resetpings {source}')
            elif choice == 5:
                run(f'count {source}')
            elif choice == 6:
                source = rng.choice([0, 1, 2, 3]); index = rng.randrange(128)
                seed(source, index)
            else:
                local, global_, favorite = rng.randrange(129), rng.randrange(4097), rng.randrange(129)
                run(f'counts {local} {global_} {favorite}')
            run(f'ping {source} {index}')
            run(f'visible {source} {index}')
            compare_info(source, index)
        if args.output:
            args.output.write_text(json.dumps(transcript, indent=2) + '\n')
    print(f'PASS {comparisons} native/production LAN operation comparisons; {args.pairs} seeded pairs; {args.operations} state transitions; {excluded_bad_addresses} explicit NA_BAD display exclusions')


if __name__ == '__main__':
    main()
