#!/usr/bin/env python3
"""Score each reference utterance independently; never concatenate across clips."""
import argparse,json,pathlib
from score_asr import normalize,distance

def evaluate(manifest,split,output):
    totals=dict(clips=0,words=0,word_errors=0,characters=0,character_errors=0,empty_hypotheses=0,invalid_segment_times=0,audio_seconds=0.0,processing_seconds=0.0)
    rows=[]
    for item in manifest['utterances']:
        if item['split']!=split:continue
        result=json.loads((output/(item['id']+'.json')).read_text())
        hypothesis=' '.join(s['text'].strip() for s in result['segments'])
        ref,hyp=normalize(item['reference']),normalize(hypothesis)
        totals['empty_hypotheses']+=int(not hyp)
        totals['invalid_segment_times']+=sum(not (0<=s['startMs']<=s['endMs']<=result['duration']*1000+1) for s in result['segments'])
        words=len(ref.split());errors=distance(ref.split(),hyp.split())
        rc,hc=ref.replace(' ',''),hyp.replace(' ','')
        totals['clips']+=1;totals['words']+=words;totals['word_errors']+=errors
        totals['characters']+=len(rc);totals['character_errors']+=distance(rc,hc)
        totals['audio_seconds']+=result['duration']
        elapsed=float((output/(item['id']+'.seconds')).read_text());totals['processing_seconds']+=elapsed
        rows.append(dict(id=item['id'],words=words,word_errors=errors,reference=item['reference'],hypothesis=hypothesis,elapsed_seconds=elapsed))
    if not totals['clips'] or not totals['words']:raise ValueError('Empty benchmark')
    totals['WER']=totals['word_errors']/totals['words'];totals['CER']=totals['character_errors']/totals['characters']
    totals['RTF']=totals['processing_seconds']/totals['audio_seconds']
    return dict(summary=totals,utterances=rows)
if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('manifest',type=pathlib.Path);ap.add_argument('split',choices=['dev','test']);ap.add_argument('results',type=pathlib.Path);ap.add_argument('--output',type=pathlib.Path);args=ap.parse_args()
    m=json.loads(args.manifest.read_text());r=evaluate(m,args.split,args.results)
    if args.output:args.output.write_text(json.dumps(r,ensure_ascii=False,indent=2))
    print(json.dumps(r['summary'],indent=2))
