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
core_sha = hashlib.sha256(core.read_bytes()).hexdigest()
if core_sha != evidence['native_core_sha256']:
    perf = json.loads((ROOT/'docs/benchmarks/fa-3.5.1-performance.json').read_text())
    assert perf['version'] == '3.5.1-performance'
    assert perf['base_commit'] == 'ab064224989dec2556904154d3f58f28979f2593'
    assert perf['native_core_sha256'] == core_sha
    validation = perf['validation']
    assert validation['commit'] == '11963aed441e85db5b2f2f765a5c138c8a20b574'
    assert validation['run_id'] == 39050663913 and validation['conclusion'] == 'success'

    old_controls = evidence['corrected']['controls']['givia']['observations']
    new_controls = perf['controls']['current']
    for name in ('silence','tone','music'):
        assert new_controls[name]['words'] == new_controls[name]['segments'] == 0
        assert new_controls[name]['invalid_segment_times'] == 0
    repeat = new_controls['repeat']
    assert repeat['first_occurrence'] and repeat['second_occurrence']
    assert repeat['invalid_segment_times'] == 0
    assert repeat['word_errors'] <= old_controls['repeat']['word_errors']

    old_informal = evidence['corrected']['informal']['givia']['summary']
    new_informal = perf['informal']['current']
    assert new_informal['clips'] == old_informal['clips'] == 18
    assert new_informal['words'] == old_informal['words'] and new_informal['characters'] == old_informal['characters']
    assert new_informal['word_errors'] <= old_informal['word_errors']
    assert new_informal['character_errors'] <= old_informal['character_errors']
    assert new_informal['empty_hypotheses'] <= old_informal['empty_hypotheses']
    assert new_informal['invalid_segment_times'] == 0
    # Do not accept a material regression on the full informal set.
    assert new_informal['processing_seconds'] <= old_informal['processing_seconds'] * 1.02

    subset = perf['fleurs_subset']
    old_rows = {x['id']: x for x in evidence['corrected']['held_out']['givia']['utterances']}
    assert len(subset['ids']) == 5 and len(set(subset['ids'])) == 5
    assert all(i in old_rows for i in subset['ids'])
    old_words = sum(old_rows[i]['words'] for i in subset['ids'])
    old_errors = sum(old_rows[i]['word_errors'] for i in subset['ids'])
    old_seconds = sum(old_rows[i]['elapsed_seconds'] for i in subset['ids'])
    current = subset['current']
    assert current['clips'] == 5 and current['words'] == old_words
    assert current['word_errors'] <= old_errors
    assert current['empty_hypotheses'] == 0 and current['invalid_segment_times'] == 0
    assert abs(subset['previous']['processing_seconds'] - old_seconds) < 0.001
    assert subset['previous']['word_errors'] == old_errors and subset['previous']['words'] == old_words
    # This subset is the explicit latency regression guard for the new batching path.
    assert current['processing_seconds'] <= old_seconds * 0.80
    print('v3.5.1 performance evidence accepted:', validation['run_id'], core_sha)
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
