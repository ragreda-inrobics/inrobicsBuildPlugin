from pathlib import Path
import hashlib, json, shutil, subprocess, zipfile, sys

base = Path(__file__).resolve().parents[3] / 'build/unity-download-verification'
base.mkdir(exist_ok=True)
source = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(r'C:\Users\ragreda\inrobics-android\download_unity_build.ps1')
shell = r'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'
for case, channel, pin, failure in [('dev','Dev',False,False), ('defined','Defined',False,False), ('master','Master',True,False), ('bad-hash','Dev',False,True), ('rclone-error','Dev',False,True), ('bad-marker','Dev',False,True)]:
    root = base / case
    root.mkdir(exist_ok=True)
    shutil.copyfile(source, root / source.name)
    meta = {'buildId':'test-build-1','flavor':'Care','commit':'abcdef','createdUtc':'2026-10-01T12:00:00Z'}
    archive = root / 'fixture.zip'
    with zipfile.ZipFile(archive,'w') as zip:
        inside = dict(meta)
        if case == 'bad-marker': inside['buildId']='wrong-build'
        zip.writestr('androidBuildCare/unityLibrary/unity-build.json',json.dumps(inside))
        zip.writestr('androidBuildCare/unityLibrary/build.gradle','// test')
    meta['sha256'] = '0'*64 if case == 'bad-hash' else hashlib.sha256(archive.read_bytes()).hexdigest()
    (root/'latest.json').write_text(json.dumps(meta))
    (root/'unity-builds.json').write_text(json.dumps({'Care':{'buildId':'test-build-1'},'Virtual':{'buildId':'keep'}}))
    (root/'gradle.properties').write_text('unityLibraryPathCare=UnityProject/androidBuildCare/unityLibrary\n')
    previous = root/'UnityProject/androidBuildCare'
    previous.mkdir(parents=True,exist_ok=True)
    (previous/'old.txt').write_text('preserve me')
    harness = root/'harness.ps1'
    harness.write_text('''function global:rclone {
    if ($args[0] -eq 'cat') { $global:LASTEXITCODE=0; Get-Content -LiteralPath (Join-Path $PSScriptRoot 'latest.json') -Raw }
    elseif ($args[0] -eq 'copyto') { Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'fixture.zip') -Destination $args[2]; $global:LASTEXITCODE=MOCK_CODE }
}
& (Join-Path $PSScriptRoot 'download_unity_build.ps1') Care -Channel CHANNEL PIN
exit $LASTEXITCODE
'''.replace('MOCK_CODE','7' if case=='rclone-error' else '0').replace('CHANNEL',channel).replace('PIN','-Pin' if pin else ''))
    result = subprocess.run([shell,'-NoProfile','-ExecutionPolicy','Bypass','-File',str(harness)],capture_output=True)
    assert (result.returncode != 0) == failure, (case,result.stdout,result.stderr)
    assert (previous/'old.txt').exists() == failure, case
    assert not list((root/'UnityProject').glob('.unity-download-*')), case
    if not failure: assert json.loads((previous/'unityLibrary/unity-build.json').read_text())['buildId']=='test-build-1'
    assert json.loads((root/'unity-builds.json').read_text(encoding='utf-8-sig'))['Virtual']['buildId']=='keep'
    print(case + ': PASS')
