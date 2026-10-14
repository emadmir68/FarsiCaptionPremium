#!/usr/bin/env python3
"""Build the exact measured model asset; never package an unchecked model."""
from pathlib import Path
import shutil
from research_asr import prepare_model

expected = 'd832fe5cd81c756bc1962765cff4a33cacde00a7701809837875245870b6d5a8'
work = Path('release-model').resolve()
source = prepare_model('givia', work, Path('native-build').resolve())
assert source['sha256'] == expected and source['bytes'] == 574041195, source
destination = Path('dist/ggml-persian-turbo-givia-q5_0.bin')
destination.parent.mkdir(exist_ok=True)
shutil.copyfile(work/'model.bin', destination)
destination.with_suffix('.bin.sha256').write_text(f'{expected}  {destination.name}\n')
shutil.copyfile('android/app/src/main/assets/model-notice.txt', 'dist/model-notice.txt')
print('Verified model matches the evaluated Q5_0 artifact:', expected)
