#!/usr/bin/env python3
"""Package only a version-consistent APK signed by the installed app's certificate."""
import argparse, hashlib, json, os, pathlib, re, shutil, subprocess
p=argparse.ArgumentParser(); p.add_argument('--previous',required=True,type=pathlib.Path); a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1]
output=root/'android/app/build/outputs/apk/release'
meta=json.loads((output/'output-metadata.json').read_text()); item=meta['elements'][0]
version=item['versionName']; code=item['versionCode']; apk=output/item['outputFile']
assert re.fullmatch(r'\d+\.\d+\.\d+(?:-[a-z0-9.-]+)?',version), version
sdk=pathlib.Path(os.environ.get('ANDROID_HOME',os.environ.get('ANDROID_SDK_ROOT','')))/'build-tools/35.0.0'
def run(*args): return subprocess.check_output([str(x) for x in args],text=True)
def badging(file):
    match=re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",run(sdk/'aapt','dump','badging',file))
    if not match: raise RuntimeError('Missing APK package metadata')
    return match.groups()
def cert(file):
    digests=re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)',run(sdk/'apksigner','verify','--print-certs',file))
    if len(digests)!=1: raise RuntimeError('Expected exactly one verified signing certificate')
    return digests[0]
app,actual_code,actual_version=badging(apk); old_app,old_code,_=badging(a.previous)
assert app==old_app==meta['applicationId']=='com.farsicaption.premium'
assert actual_version==version and int(actual_code)==code and code>int(old_code)
certificate=cert(apk); assert certificate==cert(a.previous), 'Signing key changed: existing users cannot upgrade'
dest=root/'dist'; dest.mkdir(exist_ok=True)
target=dest/f'FarsiCaptionPremium-v{version}.apk'; shutil.copyfile(apk,target)
digest=hashlib.sha256(target.read_bytes()).hexdigest()
(target.with_suffix('.apk.sha256')).write_text(f'{digest}  {target.name}\n')
(dest/'build-info.json').write_text(json.dumps(dict(applicationId=app,versionName=version,versionCode=code,commit=os.environ.get('GITHUB_SHA'),apkSha256=digest,certificateSha256=certificate,whisperCommit='a8d002cfd879315632a579e73f0148d06959de36'),indent=2)+'\n')
if os.environ.get('GITHUB_OUTPUT'):
    with open(os.environ['GITHUB_OUTPUT'],'a') as f: f.write(f'version={version}\n')
print(f'Verified {target.name}: {app}, code {code}, matching upgrade certificate {certificate}')
