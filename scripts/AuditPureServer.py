"""Private native sv_pure wire acceptance and lifecycle observations; no public server contact."""
from pathlib import Path
import concurrent.futures
import json
import sys
from NativePureServer import NativePureServer
from PureWireHarness import PureWireClient, native_checksums

OUT = Path(__file__).resolve().parent.parent / '.tools/pure-wire'
OUT.mkdir(parents=True, exist_ok=True)
EXPECTED = {
    'native': (1, 0), 'none': (0, 0), 'prior-id': (0, 0), 'future-id': (1, 0),
    'swapped-modules': (0, 1), 'reverse-general': (1, 0), 'duplicate-general': (0, 1),
    'missing-general': (1, 0), 'empty-general': (1, 0), 'unknown-general': (0, 1),
    'wrong-terminal': (0, 1), 'wrong-feed': (0, 1), 'wrong-cgame': (0, 1),
    'wrong-ui': (0, 1), 'bad-separator': (0, 1), 'extra-argument': (0, 1),
}


def signed(value):
    return (value + 2**31) % 2**32 - 2**31


def candidate(server, game, mode):
    sid, feed = game['serverId'], game['feed']
    if mode == 'none':
        return None
    listing = native_checksums(server, feed ^ (1 if mode == 'wrong-feed' else 0))
    transcript = listing['REFERENCED_PURE']
    if mode == 'prior-id':
        sid -= 1
    elif mode == 'future-id':
        sid += 100000
    if mode in {'native', 'prior-id', 'future-id', 'wrong-feed'}:
        return 'cp '+str(sid)+' '+transcript
    parts = transcript.split()
    cgame, ui = parts[:2]
    general = list(map(int, parts[3:-1]))
    if cgame == ui or len(general) < 4:
        raise AssertionError('Fixture must have distinct module packages and four general references')
    if mode == 'swapped-modules': cgame, ui = ui, cgame
    if mode == 'reverse-general': general.reverse()
    if mode == 'duplicate-general': general.append(general[0])
    if mode == 'missing-general': general.pop()
    if mode == 'empty-general': general = []
    if mode == 'unknown-general': general.append(123456789)
    if mode == 'wrong-cgame': cgame = '0'
    if mode == 'wrong-ui': ui = '0'
    # The aggregation is a separately observed FS contract; these are authored candidate variations.
    terminal = feed ^ len(general)
    for checksum in general: terminal ^= checksum
    if mode == 'wrong-terminal': terminal ^= 1
    separator = '!' if mode == 'bad-separator' else '@'
    command = 'cp '+str(sid)+' '+cgame+' '+ui+' '+separator+' '+ ' '.join(map(str, [*general, signed(terminal)]))
    if mode == 'extra-argument': command += ' 0'
    return command


def matrix_case(mode):
    with NativePureServer('pure-check-'+mode) as server, PureWireClient(server) as client:
        game = client.wait_game()
        if '\\sv_pure\\1' not in game['systemInfo']:
            raise AssertionError('Fixture is not a pure server')
        command = candidate(server, game, mode)
        if command is not None: client.command(command)
        client.control('BEGIN')
        client.pump(1.0)
        summary = client.summary()
        if (summary['begins'], summary['disconnects']) != EXPECTED[mode]:
            raise AssertionError((mode, command, summary, client.commands))
        if command is not None and (summary['last'] is None or summary['last']['ack'] < 1):
            raise AssertionError('Candidate command was not acknowledged')
        result = {'mode': mode, 'candidate': command, 'gamestate': game, 'summary': summary,
                  'commands': client.commands, 'wireRecords': client.records}
        (OUT / ('acceptance-'+mode+'.json')).write_text(json.dumps(result, indent=2)+'\n')
        print('PURE_CASE '+mode+' '+json.dumps(summary), flush=True)
        return result


def lifecycle():
    with NativePureServer('pure-check-lifecycle') as server, PureWireClient(server) as client:
        states = {}
        first = client.wait_game()
        cp = candidate(server, first, 'native')
        client.command(cp); client.control('BEGIN'); client.pump(.7)
        states['admitted'] = client.summary()
        assert states['admitted']['begins'] == 1
        client.batch(['vdr', cp]); client.pump(.35)
        states['paired'] = client.summary()
        assert states['paired']['games'] == 1 and states['paired']['begins'] == 1
        client.command('vdr'); client.wait_game(2); client.pump(.3)
        states['reset'] = client.summary()
        assert states['reset']['begins'] == 1 and states['reset']['disconnects'] == 0
        assert client.games[-1]['feed'] == first['feed'] and client.games[-1]['serverId'] == first['serverId']
        client.command(cp); client.pump(.5)
        states['reverified'] = client.summary()
        assert states['reverified']['begins'] == 2
        server.console('map_restart 0'); client.pump(.7)
        states['fastRestart'] = client.summary()
        assert states['fastRestart']['games'] == 2 and states['fastRestart']['begins'] == 3
        assert states['fastRestart']['last']['serverId'] != first['serverId']
        client.command('vdr'); client.wait_game(3); client.pump(.2)
        assert client.games[-1]['feed'] == first['feed']
        client.command(cp); client.pump(.5)
        states['oldIdSameFeed'] = client.summary()
        assert states['oldIdSameFeed']['begins'] == 4
        expected_game = len(client.games)+1
        server.console('map q3dm17'); second = client.wait_game(expected_game); client.pump(.2)
        states['newMap'] = client.summary()
        assert second['feed'] != first['feed'] and second['serverId'] != first['serverId']
        assert '\\mapname\\q3dm17' in second['serverInfo'] and states['newMap']['begins'] == 4
        client.command(cp); client.pump(.3)
        states['staleOldFeed'] = client.summary()
        assert states['staleOldFeed']['begins'] == 4 and states['staleOldFeed']['disconnects'] == 0
        client.command(candidate(server, second, 'native')); client.pump(.5)
        states['newFeedVerified'] = client.summary()
        assert states['newFeedVerified']['begins'] == 5
        result = {'states': states, 'games': client.games, 'commands': client.commands,
                  'wireRecords': client.records}
        (OUT/'lifecycle.json').write_text(json.dumps(result, indent=2)+'\n')
        print('PURE_LIFECYCLE '+json.dumps(states), flush=True)
        return result


if __name__ == '__main__':
    selection = sys.argv[1:]
    modes = list(EXPECTED) if not selection else [mode for mode in selection if mode != 'lifecycle']
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(matrix_case, modes))
    if not selection or 'lifecycle' in selection:
        lifecycle()
    print(f'PURE_SERVER acceptanceCases={len(results)} PASS', flush=True)
