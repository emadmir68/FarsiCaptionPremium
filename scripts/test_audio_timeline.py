#!/usr/bin/env python3
"""Exercise real ffmpeg extraction with a delayed PCM stream and a timestamp gap."""
import pathlib,subprocess,tempfile,wave,array
ROOT=pathlib.Path(__file__).resolve().parents[1]
ENGINE=ROOT/'android/app/src/main/java/com/farsicaption/premium/LocalCaptionEngine.kt'
# Read the production filter: this test fails if timestamp preservation is removed.
import re
FILTER=re.search(r'-af ([^ ]+) -c:a pcm_s16le',ENGINE.read_text()).group(1)
def run(*a): subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y',*map(str,a)],check=True)
def samples(p):
    with wave.open(str(p)) as w:
        assert (w.getframerate(),w.getnchannels(),w.getsampwidth())==(16000,1,2)
        return array.array('h',w.readframes(w.getnframes()))
with tempfile.TemporaryDirectory() as d:
    d=pathlib.Path(d)
    for delay in (0,1.25):
        source=d/'input.mkv';out=d/'out.wav'
        run('-f','lavfi','-i','color=black:s=32x32:r=10:d=3','-itsoffset',delay,'-f','lavfi','-i','sine=frequency=440:sample_rate=16000:duration=1','-c:v','ffv1','-c:a','pcm_s16le',source)
        run('-i',source,'-map','0:a:0','-vn','-ac','1','-ar','16000','-af',FILTER,'-c:a','pcm_s16le',out)
        x=samples(out);start=next(i for i,v in enumerate(x) if abs(v)>100)
        assert abs(start/16000-delay)<0.001,(delay,start/16000)
        assert abs(len(x)/16000-(delay+1))<0.001
    source=d/'gap.mkv';out=d/'gap.wav'
    run('-f','lavfi','-i','color=black:s=32x32:r=10:d=4','-f','lavfi','-i','sine=frequency=440:sample_rate=16000:duration=2','-af',r"asetpts=PTS+gte(T\,1)/TB",'-c:v','ffv1','-c:a','pcm_s16le',source)
    run('-i',source,'-map','0:a:0','-vn','-ac','1','-ar','16000','-af',FILTER,'-c:a','pcm_s16le',out)
    x=samples(out)
    assert 2.95<len(x)/16000<3.05
    assert max(abs(v) for v in x[20000:28000])==0
print('PASS: zero offset, 1.25 s delayed audio and internal timestamp gap preserved')
