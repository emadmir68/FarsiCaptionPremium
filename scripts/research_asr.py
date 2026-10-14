#!/usr/bin/env python3
"""Durable host-only ASR experiments; never uploads private media or model weights."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
STOCK = ('https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin',
         '394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2')
VAD = ('https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin',
       '29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf')
GIVIA_REV = '9626d38558f04c02f480b702c9db740eea567911'
VHDM_REV = '95485391406953c01ffc06724af5a08cb517a325'

def sha(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()

def download(url, dest, expected=None):
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and (expected is None or sha(dest) == expected):
        return dest
    temp = dest.with_suffix(dest.suffix + '.part')
    # curl follows pinned source redirects, fails on HTTP errors and retries transport failures.
    subprocess.run(['curl', '--fail', '--location', '--retry', '4', '--retry-all-errors',
                    '--connect-timeout', '30', '--max-time', '1200', '--silent', '--show-error',
                    '--output', str(temp), url], check=True)
    if expected and sha(temp) != expected:
        raise ValueError(f'SHA256 mismatch for {dest.name}')
    temp.replace(dest)
    return dest

def prepare_model(arm, directory, build):
    vad = download(*[VAD[0], directory/'vad.bin', VAD[1]])
    if arm in ('baseline', 'merged'):
        model = download(STOCK[0], directory/'model.bin', STOCK[1])
        source = dict(repo='ggerganov/whisper.cpp', file='ggml-large-v3-turbo-q5_0.bin', sha256=STOCK[1])
    elif arm == 'givia':
        url = f'https://huggingface.co/givia/whisper-large-v3-turbo-persian/resolve/{GIVIA_REV}/ggml-persian-v3-turbo.bin'
        full = download(url, directory/'fp16.bin', '743c4030ecb1a1d56ae64ced7a1e20ae8e51b38a87e218e995ee590fa556fff1')
        model = directory/'model.bin'
        subprocess.run([str(build/'whisper_quantize'), str(full), str(model), 'q5_0'], check=True)
        source = dict(repo='givia/whisper-large-v3-turbo-persian', revision=GIVIA_REV, quantization='q5_0', license='CC-BY-4.0')
    elif arm == 'vhdm':
        base = f'https://huggingface.co/vhdm/whisper-large-fa-v1/resolve/{VHDM_REV}/'
        for name in ('config.json', 'vocab.json', 'added_tokens.json'):
            download(base+name, directory/name)
        download(base+'model.safetensors', directory/'model.safetensors',
                 'b9446fce68a888995f6d03a836859a1544e1022b562683ecb9cc0b276d11c4a6')
        whisper = directory/'openai-whisper'
        download('https://raw.githubusercontent.com/openai/whisper/v20250625/whisper/assets/mel_filters.npz',
                 whisper/'whisper/assets/mel_filters.npz')
        subprocess.run([sys.executable, str(ROOT/'scripts/convert_whisper_safetensors.py'),
                        str(directory), str(whisper), str(directory)], check=True)
        model = directory/'model.bin'
        subprocess.run([str(build/'whisper_quantize'), str(directory/'ggml-model.bin'), str(model), 'q5_0'], check=True)
        source = dict(repo='vhdm/whisper-large-fa-v1', revision=VHDM_REV, quantization='q5_0', license='MIT')
    else:
        raise ValueError(arm)
    source.update(sha256=sha(model), bytes=model.stat().st_size, vad_sha256=sha(vad), arm=arm)
    return source

def run(arm, stage, work, build):
    from report_fa_benchmark import evaluate
    work.mkdir(parents=True, exist_ok=True)
    evidence = work/'evidence'; evidence.mkdir(exist_ok=True)
    source = prepare_model(arm, work/'models', build)
    (evidence/'model.json').write_text(json.dumps(source, indent=2))
    data = work/'data'
    prepare = [sys.executable, str(ROOT/'scripts/prepare_fa_benchmark.py'), str(data)]
    if stage == 'dev':
        prepare += ['--count', '10', '--splits', 'dev']
        split = 'dev'
    else:
        old = json.loads((ROOT/'docs/benchmarks/fa-3.4.0.json').read_text())['manifest']
        exclude = work/'exclude.json'; exclude.write_text(json.dumps(old))
        prepare += ['--count', '20', '--splits', 'test', '--exclude-manifest', str(exclude)]
        split = 'test'
    subprocess.run(prepare, check=True)
    manifest = json.loads((data/'manifest.json').read_text())
    output = evidence/'hypotheses'
    subprocess.run([str(build/'asr_batch'), str(work/'models/model.bin'), str(work/'models/vad.bin'),
                    str(data/f'{split}.tsv'), str(output), '128', '4'], check=True)
    report = evaluate(manifest, split, output)
    report.update(arm=arm, stage=stage, commit=os.environ.get('GITHUB_SHA'), model=source)
    (evidence/'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    for item in manifest['utterances']:
        item['wav'] = Path(item['wav']).name
    (evidence/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
    print(json.dumps(report['summary'], indent=2), flush=True)

def select(directory, output):
    reports = {x['arm']: x for p in directory.rglob('report.json') if (x := json.loads(p.read_text()))['stage'] == 'dev'}
    if set(reports) != {'baseline', 'merged', 'vhdm', 'givia'}:
        raise ValueError(f'Incomplete development evidence: {sorted(reports)}')
    baseline = reports['baseline']['summary']
    eligible = []
    for arm, report in reports.items():
        s = report['summary']
        if arm != 'baseline' and s['WER'] < baseline['WER'] and s['CER'] <= baseline['CER'] and s['empty_hypotheses'] <= baseline['empty_hypotheses'] and s['invalid_segment_times'] == 0:
            eligible.append(arm)
    eligible.sort(key=lambda a: (reports[a]['summary']['WER'], reports[a]['summary']['CER'], a))
    winner = eligible[0] if eligible else 'none'
    result = dict(winner=winner, eligible=eligible, summaries={a:r['summary'] for a,r in reports.items()})
    output.write_text(json.dumps(result, indent=2))
    if 'GITHUB_OUTPUT' in os.environ:
        with open(os.environ['GITHUB_OUTPUT'], 'a') as f:
            f.write(f'winner={winner}\n')
            f.write('arms='+json.dumps(['baseline', winner] if eligible else ['baseline'])+'\n')
    print(json.dumps(result, indent=2))

if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='command', required=True)
    r = sub.add_parser('run'); r.add_argument('arm', choices=['baseline','merged','vhdm','givia'])
    r.add_argument('stage', choices=['dev','test']); r.add_argument('work', type=Path); r.add_argument('build', type=Path)
    s = sub.add_parser('select'); s.add_argument('directory', type=Path); s.add_argument('output', type=Path)
    a = ap.parse_args()
    if a.command == 'run': run(a.arm, a.stage, a.work.resolve(), a.build.resolve())
    else: select(a.directory, a.output)
