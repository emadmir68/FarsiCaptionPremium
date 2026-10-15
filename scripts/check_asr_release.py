#!/usr/bin/env python3
"""Fail publication unless the fixed ASR gates have complete recorded evidence."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
evidence = json.loads((ROOT/'docs/benchmarks/fa-3.4.2.json').read_text())
baseline_sha = '394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2'
candidate_sha = 'd832fe5cd81c756bc1962765cff4a33cacde00a7701809837875245870b6d5a8'
assert evidence['candidate'] == 'givia'
core = ROOT/'android/app/src/main/cpp/caption_core.cpp'
assert hashlib.sha256(core.read_bytes()).hexdigest() == evidence['native_core_sha256']
patch = ROOT/'android/app/src/main/cpp/patch_whisper.cmake'
assert hashlib.sha256(patch.read_bytes()).hexdigest() == evidence['native_patch_sha256']

def compare(baseline, candidate, count, strict=False):
    b, c = baseline['summary'], candidate['summary']
    assert b['clips'] == c['clips'] == count
    assert b['words'] == c['words'] > 0 and b['characters'] == c['characters'] > 0
    assert c['word_errors'] < b['word_errors'] if strict else c['word_errors'] <= b['word_errors']
    assert c['character_errors'] <= b['character_errors']
    assert c['empty_hypotheses'] <= b['empty_hypotheses']
    assert b['invalid_segment_times'] == c['invalid_segment_times'] == 0

dev = evidence['development']
compare(dev['baseline'], dev['givia'], 10, strict=True)
eligible = []
for name in ('merged','vhdm','givia'):
    try: compare(dev['baseline'], dev[name], 10, strict=True)
    except AssertionError: continue
    eligible.append(name)
assert min(eligible, key=lambda n:(dev[n]['summary']['WER'],dev[n]['summary']['CER'],n)) == 'givia'

for split, count in (('held_out',20),('informal',18)):
    pair = evidence[split]
    compare(pair['baseline'], pair['givia'], count)
    assert pair['baseline']['model']['sha256'] == baseline_sha
    assert pair['givia']['model']['sha256'] == candidate_sha

for split in ('development','held_out'):
    manifests = evidence['manifests'][split]
    reference = manifests['baseline']['utterances']
    for manifest in manifests.values():
        assert [(x['id'],x['sha256'],x['reference']) for x in manifest['utterances']] == [
            (x['id'],x['sha256'],x['reference']) for x in reference]
old = json.loads((ROOT/'docs/benchmarks/fa-3.4.0.json').read_text())['manifest']['utterances']
old_ids = {x['source_id'] for x in old}
fresh = evidence['manifests']['held_out']['baseline']['utterances']
assert len({x['source_id'] for x in fresh}) == 20
assert not old_ids.intersection(x['source_id'] for x in fresh)
assert evidence['informal']['baseline']['labels_sha256'] == evidence['informal']['givia']['labels_sha256']
assert evidence['informal']['baseline']['sample_metadata'] == evidence['informal']['givia']['sample_metadata']

corrected = evidence['corrected']
assert len(corrected['provenance']) == 14
for p in corrected['provenance']:
    assert p['native_core_sha256'] == evidence['native_core_sha256']
    assert p['patch_sha256'] == evidence['native_patch_sha256']
    assert p['whisper_source_sha256'] == 'b36883ea3df6835a6d8b21bad1a97767624254d6303593f1e1cdbbe2a50e66bc'
for split, count in (('held_out',20),('informal',18)):
    compare(corrected[split]['baseline'], corrected[split]['givia'], count)
    for arm, model_sha in (('baseline',baseline_sha),('givia',candidate_sha)):
        compare(evidence[split][arm], corrected[split][arm], count)
        assert corrected[split][arm]['model']['sha256'] == model_sha
        assert {x['id'] for x in evidence[split][arm]['utterances']} == {x['id'] for x in corrected[split][arm]['utterances']}
        if split == 'informal':
            old = sorted(evidence[split][arm]['sample_metadata'], key=lambda x:x['id'])
            assert old == corrected[split][arm]['sample_metadata']
        else:
            old = sorted(evidence['manifests'][split][arm]['utterances'], key=lambda x:x['id'])
            new = corrected['manifests'][arm]['utterances']
            assert [(x['id'],x['sha256'],x['reference']) for x in old] == [(x['id'],x['sha256'],x['reference']) for x in new]
for arm in ('baseline','givia'):
    control = corrected['controls'][arm]['observations']
    for name in ('silence','tone','music'):
        assert control[name]['words'] == control[name]['segments'] == control[name]['invalid_segment_times'] == 0
    assert control['repeat']['first_occurrence'] and control['repeat']['second_occurrence']
    assert control['repeat']['invalid_segment_times'] == 0
    assert control['repeat']['word_errors'] <= evidence['controls'][arm]['observations']['repeat']['word_errors']
assert evidence['private_clip']['givia']['segments'] > 0
assert evidence['private_clip']['givia']['invalid_segment_times'] == 0
assert evidence['private_clip']['verified_reference_available'] is False
print('ASR publication gates passed: fixed model selection, full native regression, non-speech and repeat controls.')
