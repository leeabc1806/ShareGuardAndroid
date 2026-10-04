"""Preserve the installed APK's public endpoint/provider configuration for a review build."""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[1]
apk = root / 'ShareGuardAndroid/build/kakaomap-fix-20261004/device-check/installed-after.apk'
if not apk.exists():
    apk = Path.home() / 'Downloads/ShareGuard-kakaomap-fix-20261004.apk'
result = subprocess.run([
    r'C:\Program Files\Java\jdk-17\bin\java.exe',
    r'-Dcom.android.sdklib.toolsdir=C:\Android\cmdline-tools\latest', '-classpath',
    r'C:\Android\cmdline-tools\latest\lib\apkanalyzer-classpath.jar',
    'com.android.tools.apk.analyzer.ApkAnalyzerCli', 'dex', 'code', '--class',
    'com.shareguard.app.BuildConfig', str(apk)
], capture_output=True, text=True, encoding='utf-8', check=True)
fields = dict(re.findall(r'\.field public static final (\w+):Ljava/lang/String; = "([^"\n]*)"', result.stdout))
names = ['SUPABASE_URL','SUPABASE_PUBLISHABLE_KEY','KAKAO_NATIVE_APP_KEY','TOSS_CLIENT_KEY',
         'TOSS_CONFIRM_URL','SHAREGUARD_API_BASE_URL','PORTONE_STORE_ID','PORTONE_IDENTITY_CHANNEL_KEY']
assert all(name in fields for name in names)
assert fields['KAKAO_NATIVE_APP_KEY'].endswith('4214b4')
folder = root / 'ShareGuardAndroid/build/review-20261005'
folder.mkdir(parents=True, exist_ok=True)
(folder / 'app.properties').write_text('\n'.join(name+'='+fields[name] for name in names)+'\n', encoding='ascii')
print('Review build configuration preserved from verified APK (8 public settings).')
