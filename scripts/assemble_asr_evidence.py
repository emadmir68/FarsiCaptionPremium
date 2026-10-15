#!/usr/bin/env python3
"""Assemble saved Actions reports without re-scoring or exposing private media.
Usage: assemble_asr_evidence.py ARTIFACT_ROOT PRIVATE_COUNTS OUTPUT_JSON
"""
import argparse,hashlib,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def read(directory,name):
    files=list(directory.rglob(name));assert len(files)==1,(directory,name,files)
    return json.loads(files[0].read_text())
def aggregate(reports):
    first=reports[0]
    totals={k:sum(r['summary'][k] for r in reports) for k in first['summary'] if k not in ('WER','CER','RTF')}
    totals.update(WER=totals['word_errors']/totals['words'],CER=totals['character_errors']/totals['characters'],RTF=totals['processing_seconds']/totals['audio_seconds'])
    result=dict(first,summary=totals,utterances=sorted([x for r in reports for x in r['utterances']],key=lambda x:x['id']))
    assert len({x['id'] for x in result['utterances']})==totals['clips']
    if 'sample_metadata' in first:
        result['sample_metadata']=sorted([x for r in reports for x in r['sample_metadata']],key=lambda x:x['id'])
        assert all(r['labels_sha256']==first['labels_sha256'] for r in reports)
    return result
def main(a):
    root=a.artifacts
    result=dict(version='3.4.2-persian',candidate='givia',development={},held_out={},informal={},controls={},manifests=dict(development={},held_out={}),corrected=dict(held_out={},informal={},controls={},manifests={},provenance=[]),private_clip=json.loads(a.private_counts.read_text()),actions_runs=dict(selection=38606792364,independent=38607589009,corrected=38694071591,android=38694071555))
    for split,prefix,arms in [('development','dev',('baseline','merged','vhdm','givia')),('held_out','test',('baseline','givia'))]:
        for arm in arms:
            directory=root/f'{prefix}-{arm}'
            result[split][arm]=read(directory,'report.json')
            result['manifests'][split][arm]=read(directory,'manifest.json')
    for arm in ('baseline','givia'):
        directory=root/f'independent-{arm}'
        result['informal'][arm]=read(directory,'informal-report.json')
        result['informal'][arm]['model']=read(directory,'model.json')
        result['controls'][arm]=read(directory,'controls.json')
        for suite,section,file in [('fleurs','held_out','report.json'),('informal','informal','informal-report.json')]:
            directories=[root/f'fixed-{arm}-{suite}-{i}' for i in range(3)]
            reports=[read(d,file) for d in directories]
            for r,d in zip(reports,directories):r['model']=read(d,'model.json')
            assert all(r['model']==reports[0]['model'] for r in reports)
            result['corrected'][section][arm]=aggregate(reports)
            if suite=='fleurs':
                manifests=[read(d,'manifest.json') for d in directories]
                manifest=dict(manifests[0]);manifest['utterances']=sorted([x for m in manifests for x in m['utterances']],key=lambda x:x['id'])
                result['corrected']['manifests'][arm]=manifest
            result['corrected']['provenance'].extend(read(d,'provenance.json') for d in directories)
        directory=root/f'fixed-{arm}-controls-0'
        result['corrected']['controls'][arm]=read(directory,'controls.json')
        result['corrected']['provenance'].append(read(directory,'provenance.json'))
    for key,file in [('native_core_sha256','caption_core.cpp'),('native_patch_sha256','patch_whisper.cmake')]:
        result[key]=hashlib.sha256((ROOT/'android/app/src/main/cpp'/file).read_bytes()).hexdigest()
    a.output.parent.mkdir(parents=True,exist_ok=True)
    a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('artifacts',type=Path);p.add_argument('private_counts',type=Path);p.add_argument('output',type=Path);main(p.parse_args())
