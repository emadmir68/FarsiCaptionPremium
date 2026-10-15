#!/usr/bin/env python3
"""Recheck frozen evaluation sets after the native no-speech correctness fix.

Shards only divide execution. They do not change selection or tune decoding.
Restricted PSRB audio/text remain on the runner, as in the original protocol.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

from research_asr import ROOT, prepare_model, sha
from report_fa_benchmark import evaluate
from validate_asr_candidate import main as independent


def main(a):
    work, build = a.work.resolve(), a.build.resolve()
    if a.suite != 'fleurs':
        independent(a.arm, work, build, a.suite, a.shard_index, a.shard_count)
    else:
        work.mkdir(parents=True, exist_ok=True)
        evidence = work/'evidence'; evidence.mkdir(exist_ok=True)
        model = prepare_model(a.arm, work/'models', build)
        old = json.loads((ROOT/'docs/benchmarks/fa-3.4.0.json').read_text())['manifest']
        exclude = work/'exclude.json'; exclude.write_text(json.dumps(old))
        data = work/'data'
        subprocess.run([sys.executable, str(ROOT/'scripts/prepare_fa_benchmark.py'), str(data),
                        '--count', '20', '--splits', 'test', '--exclude-manifest', str(exclude)], check=True)
        manifest = json.loads((data/'manifest.json').read_text())
        manifest['utterances'] = manifest['utterances'][a.shard_index::a.shard_count]
        items = manifest['utterances']
        tsv = work/'input.tsv'
        tsv.write_text(''.join(f"{x['id']}\t{x['wav']}\n" for x in items))
        output = evidence/'hypotheses'
        subprocess.run([str(build/'asr_batch'), str(work/'models/model.bin'), str(work/'models/vad.bin'),
                        str(tsv), str(output), '128', '4'], check=True)
        report = evaluate(manifest, 'test', output)
        report.update(arm=a.arm, model=model)
        (evidence/'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
        for item in items: item['wav'] = Path(item['wav']).name
        (evidence/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
        (evidence/'model.json').write_text(json.dumps(model, indent=2))
        print(json.dumps(report['summary'], indent=2))
    provenance = dict(commit=os.environ.get('GITHUB_SHA'), suite=a.suite, arm=a.arm,
                      shard_index=a.shard_index, shard_count=a.shard_count,
                      native_core_sha256=sha(ROOT/'android/app/src/main/cpp/caption_core.cpp'),
                      patch_sha256=sha(ROOT/'android/app/src/main/cpp/patch_whisper.cmake'),
                      whisper_source_sha256=sha(build/'_deps/whisper_source-src/src/whisper.cpp'))
    (work/'evidence/provenance.json').write_text(json.dumps(provenance, indent=2))


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('arm', choices=['baseline', 'givia'])
    p.add_argument('suite', choices=['fleurs', 'informal', 'controls'])
    p.add_argument('work', type=Path); p.add_argument('build', type=Path)
    p.add_argument('--shard-index', type=int, default=0); p.add_argument('--shard-count', type=int, default=1)
    a = p.parse_args()
    assert 0 <= a.shard_index < a.shard_count
    main(a)
