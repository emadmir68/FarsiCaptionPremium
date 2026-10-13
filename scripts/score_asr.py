#!/usr/bin/env python3
"""Print WER/CER for UTF-8 reference and hypothesis text files (one utterance per line)."""
import argparse, json, re, unicodedata
from pathlib import Path
def normalize(s):
    s=unicodedata.normalize('NFC',s).translate(str.maketrans({'ي':'ی','ى':'ی','ك':'ک','\u200c':' '}))
    s=''.join(c for c in s if not unicodedata.category(c).startswith(('P','M')))
    return re.sub(r'\s+',' ',s).strip()
def distance(a,b):
    row=list(range(len(b)+1))
    for i,x in enumerate(a,1):
        nxt=[i]
        for j,y in enumerate(b,1): nxt.append(min(nxt[-1]+1,row[j]+1,row[j-1]+(x!=y)))
        row=nxt
    return row[-1]
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('reference',type=Path);p.add_argument('hypothesis',type=Path);a=p.parse_args()
    refs=a.reference.read_text().splitlines();hyps=a.hypothesis.read_text().splitlines()
    if len(refs)!=len(hyps): p.error('Reference and hypothesis utterance counts differ')
    totals=dict(words=0,word_errors=0,characters=0,character_errors=0)
    for r,h in zip(refs,hyps):
        r,h=normalize(r),normalize(h);rc,hc=r.replace(' ',''),h.replace(' ','')
        totals['words']+=len(r.split());totals['word_errors']+=distance(r.split(),h.split())
        totals['characters']+=len(rc);totals['character_errors']+=distance(rc,hc)
    totals['WER']=totals['word_errors']/totals['words'] if totals['words'] else None
    totals['CER']=totals['character_errors']/totals['characters'] if totals['characters'] else None
    print(json.dumps(totals,indent=2))
