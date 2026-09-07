"""Compare all original RoQ frames/audio with FFmpeg without extracting or packaging game assets."""
from pathlib import Path
import argparse, hashlib, json, os, re, shutil, subprocess, zipfile
import contextlib, http.server, threading, uuid
def reference_ok(result):
    # FFmpeg's RoQ demuxer reports its terminal header read as invalid input at clean EOF.
    # Keep the diagnostic; allow only that exact message, with zero exit and full hash equality.
    lines = result.stderr.decode().splitlines()
    return result.returncode == 0 and all(re.fullmatch(
        r'\[in#0/roq @ (?:0x)?[0-9a-f]+\] Error during demuxing: Invalid data found when processing input', line)
        for line in lines)

@contextlib.contextmanager
def seekable(data):
    # The reference demuxer seeks; expose bounded bytes on loopback without extracting a file.
    route = '/' + uuid.uuid4().hex
    class Reader(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_HEAD(self): self.send_data(False)
        def do_GET(self): self.send_data(True)
        def send_data(self, body):
            if self.path != route:
                self.send_error(404); return
            header = self.headers.get('Range')
            start, end = 0, len(data) - 1
            if header:
                match = re.fullmatch(r'bytes=(\d+)-(\d*)', header)
                if not match:
                    self.send_error(416); return
                start = int(match[1])
                end = min(end, int(match[2])) if match[2] else end
                if start > end:
                    self.send_error(416); return
            self.send_response(206 if header else 200)
            self.send_header('Content-Length', str(end - start + 1))
            self.send_header('Accept-Ranges', 'bytes')
            if header: self.send_header('Content-Range', f'bytes {start}-{end}/{len(data)}')
            self.end_headers()
            if body:
                try: self.wfile.write(memoryview(data)[start:end+1])
                except (BrokenPipeError, ConnectionResetError): pass
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Reader)
    thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
    try: yield f'http://127.0.0.1:{server.server_port}{route}'
    finally: server.shutdown(); server.server_close(); thread.join()

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--classpath', required=True)
parser.add_argument('--pk3', type=Path, default=Path('.tools/pak0-audit/games/baseq3/pak0.pk3'))
parser.add_argument('--video', help='One exact archive entry; default all RoQ entries')
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if 'JAVA_HOME' in os.environ else 'java'
ffmpeg = shutil.which('ffmpeg')
if not ffmpeg: raise RuntimeError('FFmpeg is required for this offline differential audit')
version = subprocess.check_output([ffmpeg, '-version'], text=True).splitlines()[0]
report = {'reference': version, 'videos': []}
with zipfile.ZipFile(args.pk3) as archive:
    names = [args.video] if args.video else sorted(n for n in archive.namelist() if n.lower().endswith('.roq'))
    if not names: raise RuntimeError('No RoQ entries found')
    for name in names:
        if archive.getinfo(name).file_size > 512 * 1024 * 1024:
            raise RuntimeError('RoQ entry exceeds audit memory limit: ' + name)
        label = Path(name).stem
        result = subprocess.run([java, '-cp', args.classpath, 'scripts/AuditRoq.java', str(args.pk3), name], capture_output=True, text=True, timeout=120)
        (args.output / (label + '-java.log')).write_text(result.stdout + result.stderr)
        if result.returncode: raise RuntimeError('Java decoder failed: ' + name)
        hashes = [line.split()[2] for line in result.stdout.splitlines() if line.startswith('V ')]
        audio = next(line.split()[1] for line in result.stdout.splitlines() if line.startswith('A '))
        summary = next(line for line in result.stdout.splitlines() if line.startswith('PASS '))
        data = archive.read(name)
        with seekable(data) as url:
            native = subprocess.run([ffmpeg, '-v', 'error', '-i', url, '-map', '0:v:0', '-c:v', 'rawvideo', '-pix_fmt', 'yuvj444p', '-f', 'framehash', '-hash', 'sha256', 'pipe:1'], capture_output=True, timeout=120)
            (args.output / (label + '-reference.log')).write_bytes(native.stdout + native.stderr)
            if not reference_ok(native): raise RuntimeError('Reference video decoder failed: ' + name)
            reference = [line.split(',')[-1].strip() for line in native.stdout.decode().splitlines() if line and not line.startswith('#')]
            if hashes != reference:
                mismatch = next((i for i, pair in enumerate(zip(hashes, reference)) if pair[0] != pair[1]), min(len(hashes), len(reference)))
                raise AssertionError(f'{name}: frame {mismatch} differs; Java={len(hashes)} reference={len(reference)}')
            if 'audioBlocks=0 ' not in summary:
                sound = subprocess.run([ffmpeg, '-v', 'error', '-i', url, '-map', '0:a:0', '-c:a', 'pcm_s16le', '-f', 'hash', '-hash', 'sha256', 'pipe:1'], capture_output=True, timeout=120)
                (args.output / (label + '-reference-audio.log')).write_bytes(sound.stdout + sound.stderr)
                if not reference_ok(sound): raise RuntimeError('Reference audio decoder failed: ' + name)
                reference_audio = sound.stdout.decode().strip().split('=')[-1]
                assert audio == reference_audio, name + ': audio differs'
            else: assert audio == hashlib.sha256(b'').hexdigest()
        item = {'entry': name, 'frames': len(hashes), 'audioSha256': audio, 'summary': summary, 'referenceEofDiagnostic': bool(native.stderr)}
        report['videos'].append(item)
        print(name + ': exact video/audio ' + summary, flush=True)
(args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
