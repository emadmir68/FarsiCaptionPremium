#!/usr/bin/env python3
"""Independent informal-speech research and non-speech/repetition controls.

PSRB permits research/educational use. Audio, references and recognition text
remain on the ephemeral runner; exported evidence contains only IDs and metrics.
Never uses user media. Run only after the development selection is frozen.
"""
import argparse
import array
import csv
import json
import math
from pathlib import Path
import subprocess
import sys
import wave

from research_asr import ROOT, download, prepare_model, sha
from report_fa_benchmark import evaluate
from score_asr import normalize, distance

PSRB_REV = '626745e790667d6cbf70bdb10a260d3af90ed2d2'
PSRB = f'https://huggingface.co/datasets/PartAI/PSRB/resolve/{PSRB_REV}/'
SOURCES = ['animation','audiobook','debate','documentary','film','lecture','news','podcast','talk_show']

def pcm(path, samples):
    data = array.array('h', samples)
    if sys.byteorder != 'little': data.byteswap()
    with wave.open(str(path), 'wb') as w:
        w.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
        w.writeframes(data.tobytes())

def decode(build, work, items, name):
    tsv = work/(name+'.tsv')
    tsv.write_text(''.join(f"{x['id']}\t{x['wav']}\n" for x in items))
    output = work/(name+'-hypotheses')
    subprocess.run([str(build/'asr_batch'), str(work/'models/model.bin'), str(work/'models/vad.bin'),
                    str(tsv), str(output), '128', '4'], check=True)
    return output

def main(arm, work, build):
    work.mkdir(parents=True, exist_ok=True)
    evidence = work/'evidence'; evidence.mkdir(exist_ok=True)
    source = prepare_model(arm, work/'models', build)
    (evidence/'model.json').write_text(json.dumps(source, indent=2))
    labels = download(PSRB+'Labels.csv', work/'Labels.csv')
    rows = list(csv.DictReader(labels.open(encoding='utf-8-sig')))
    # This exact pinned CSV has three mismatched headers: duration contains text,
    # number_of_speakers contains duration, and text contains speaker count.
    # Validate the observed schema explicitly instead of silently scoring counts.
    for row in rows:
        assert any('\u0600' <= c <= '\u06ff' for c in row['audio_duration'])
        assert float(row['number_of_speakers']) > 0
        assert row['text'].isdigit()
    selected = []
    for category in SOURCES:
        eligible = [r for r in rows if r['data_source'] == category and r['formality'] == 'informal'
                    and 3 <= float(r['number_of_speakers']) <= 30 and '{' not in r['audio_duration']]
        eligible.sort(key=lambda r: int(Path(r['audio_path']).stem.split('_')[-1]))
        assert len(eligible) >= 2
        for r in eligible[:2]:
            uid = 'psrb-'+Path(r['audio_path']).stem.replace('_','-')
            original = download(PSRB+r['audio_path'], work/(uid+'-source.wav'))
            wav = work/(uid+'.wav')
            subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-i',str(original),
                            '-ar','16000','-ac','1','-c:a','pcm_s16le',str(wav)], check=True)
            selected.append(dict(id=uid, split='test', wav=str(wav), reference=r['audio_duration'],
                                 source=r['data_source'], gender=r['gender'], accent=r['accents'],
                                 environment=r['acoustic_environment'], sha256=sha(wav)))
    manifest = dict(dataset='PartAI/PSRB', revision=PSRB_REV, labels_sha256=sha(labels), utterances=selected)
    # Freeze the manifest before decoding, but do not upload restricted text/audio.
    (work/'private-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
    hypotheses = decode(build, work, selected, 'psrb')
    report = evaluate(manifest, 'test', hypotheses)
    for row in report['utterances']:
        row.pop('reference'); row.pop('hypothesis')
    report.update(arm=arm, dataset='PartAI/PSRB', revision=PSRB_REV, labels_sha256=sha(labels),
                  sample_metadata=[{k:v for k,v in x.items() if k not in ('wav','reference')} for x in selected])
    (evidence/'informal-report.json').write_text(json.dumps(report, indent=2))

    # Digital silence and a pure tone must not become speech captions.
    pcm(work/'silence.wav', [0]*48000)
    pcm(work/'tone.wav', [int(3000*math.sin(2*math.pi*440*i/16000)) for i in range(48000)])
    music = download('https://raw.githubusercontent.com/librosa/data/main/audio/Kevin_MacLeod_-_Vibe_Ace.ogg', work/'music.ogg')
    subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-i',str(music),'-t','20',
                    '-ar','16000','-ac','1','-c:a','pcm_s16le',str(work/'music.wav')], check=True)
    dev = work/'public-control'
    subprocess.run([sys.executable, str(ROOT/'scripts/prepare_fa_benchmark.py'),str(dev),
                    '--count','1','--splits','dev'], check=True)
    item = json.loads((dev/'manifest.json').read_text())['utterances'][0]
    with wave.open(item['wav']) as w:
        raw = w.readframes(w.getnframes()); length = w.getnframes()/16000
    with wave.open(str(work/'repeat.wav'), 'wb') as w:
        w.setparams((1,2,16000,0,'NONE','not compressed'))
        w.writeframes(raw+b'\x00'*(3*16000*2)+raw)
    controls = [dict(id=name, wav=str(work/(name+'.wav'))) for name in ('silence','tone','music','repeat')]
    out = decode(build, work, controls, 'controls')
    observations = {}
    for control in controls:
        result = json.loads((out/(control['id']+'.json')).read_text())
        text = normalize(' '.join(s['text'] for s in result['segments']))
        entry = dict(segments=len(result['segments']), words=len(text.split()),
                     invalid_segment_times=sum(not (0 <= s['startMs'] <= s['endMs'] <= result['duration']*1000+1)
                                               for s in result['segments']))
        if control['id'] == 'repeat':
            ref = normalize(item['reference']+' '+item['reference'])
            entry.update(word_errors=distance(ref.split(), text.split()), reference_words=len(ref.split()),
                         first_occurrence=any(s['startMs'] < length*1000 for s in result['segments']),
                         second_occurrence=any(s['startMs'] >= (length+3)*1000-300 for s in result['segments']),
                         gap_seconds=3)
        observations[control['id']] = entry
    (evidence/'controls.json').write_text(json.dumps(dict(arm=arm,observations=observations,
        music=dict(title='Vibe Ace', author='Kevin MacLeod', license='CC-BY-3.0', sha256=sha(music),
                   source='https://freemusicarchive.org/music/Kevin_MacLeod/Jazz_Sampler/Vibe_Ace')), indent=2))
    print(json.dumps(dict(arm=arm, informal=report['summary'], controls=observations), indent=2))

if __name__ == '__main__':
    p=argparse.ArgumentParser();p.add_argument('arm',choices=['baseline','merged','vhdm','givia'])
    p.add_argument('work',type=Path);p.add_argument('build',type=Path);a=p.parse_args()
    main(a.arm,a.work.resolve(),a.build.resolve())
