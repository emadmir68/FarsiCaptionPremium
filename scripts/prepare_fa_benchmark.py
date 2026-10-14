#!/usr/bin/env python3
"""Download a deterministic 10+10-utterance FLEURS Persian smoke benchmark.
CC-BY-4.0, Google FLEURS. Read speech, NOT a colloquial-video benchmark.
Large audio/model files stay outside git. Never uses ASR output as reference.
"""
import argparse,csv,hashlib,io,json,pathlib,subprocess,tarfile,urllib.request
REVISION='70bb2e84b976b7e960aa89f1c648e09c59f894dd'
BASE=f'https://huggingface.co/datasets/google/fleurs/resolve/{REVISION}/data/fa_ir'
def main():
    ap=argparse.ArgumentParser();ap.add_argument('output',type=pathlib.Path)
    ap.add_argument('--count',type=int,default=10)
    ap.add_argument('--splits',nargs='+',choices=['dev','test'],default=['dev','test'])
    ap.add_argument('--exclude-manifest',type=pathlib.Path)
    args=ap.parse_args()
    if args.count<1:ap.error('--count must be positive')
    args.output.mkdir(parents=True,exist_ok=True);manifest=[];seen_ids=set()
    if args.exclude_manifest:
        seen_ids.update(x['source_id'] for x in json.loads(args.exclude_manifest.read_text())['utterances'])
    for split in args.splits:
        tsv=urllib.request.urlopen(f'{BASE}/{split}.tsv',timeout=60).read().decode()
        rows={r[1]:r for r in csv.reader(io.StringIO(tsv),delimiter='\t')}
        directory=args.output/split;directory.mkdir(exist_ok=True);count=0
        # Streaming permits reading only the needed beginning of the archive.
        with urllib.request.urlopen(f'{BASE}/audio/{split}.tar.gz',timeout=90) as response:
            with tarfile.open(fileobj=response,mode='r|gz') as archive:
                for member in archive:
                    name=pathlib.PurePosixPath(member.name).name
                    if not member.isfile() or name not in rows:continue
                    row=rows[name]
                    if row[0] in seen_ids:continue
                    seen_ids.add(row[0]);uid=f'{split}-{name[:-4]}'
                    original=directory/(uid+'-source.wav');wav=directory/(uid+'.wav')
                    original.write_bytes(archive.extractfile(member).read())
                    subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-i',str(original),'-ar','16000','-ac','1','-c:a','pcm_s16le',str(wav)],check=True)
                    original.unlink()
                    manifest.append(dict(id=uid,split=split,source_id=row[0],source_file=name,reference=row[2],gender=row[-1],wav=str(wav.resolve()),sha256=hashlib.sha256(wav.read_bytes()).hexdigest()))
                    count+=1;print(split,count,uid,flush=True)
                    if count==args.count:break
        assert count==args.count, f'Only {count} examples found for {split}'
        chosen=[x for x in manifest if x['split']==split]
        (args.output/f'{split}.tsv').write_text(''.join(f"{x['id']}\t{x['wav']}\n" for x in chosen))
    (args.output/'manifest.json').write_text(json.dumps(dict(dataset='google/fleurs',revision=REVISION,config='fa_ir',license='CC-BY-4.0',selection=f'First {args.count} distinct transcript IDs per requested archive; earlier split IDs and optional exclusion-manifest IDs excluded',utterances=manifest),ensure_ascii=False,indent=2))
if __name__=='__main__':main()
