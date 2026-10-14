#!/usr/bin/env python3
"""Fail publication unless the fixed ASR gates have complete recorded evidence."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
evidence = json.loads((ROOT/'docs/benchmarks/fa-3.4.1.json').read_text())
baseline_sha = '394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2'
candidate_sha = 'd832fe5cd81c756bc1962765cff4a33cacde00a7701809837875245870b6d5a8'
assert evidence['candidate'] == 'givia'
core = ROOT/'android/app/src/main/cpp/caption_core.cpp'
assert hashlib.sha256(core.read_bytes()).hexdigest() == evidence['native_core_sha256']

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

control = evidence['controls']['givia']['observations']
for name in ('silence','tone','music'):
    assert control[name]['words'] == control[name]['invalid_segment_times'] == 0
assert control['repeat']['first_occurrence'] and control['repeat']['second_occurrence']
assert control['repeat']['invalid_segment_times'] == 0
assert evidence['private_clip']['givia']['segments'] > 0
assert evidence['private_clip']['givia']['invalid_segment_times'] == 0
assert evidence['private_clip']['verified_reference_available'] is False
print('ASR publication gates passed: fixed selection, 20 fresh test + 18 informal clips, non-speech and repeat controls.')
