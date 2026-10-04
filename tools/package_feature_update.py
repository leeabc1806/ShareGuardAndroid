"""Verify and package the reviewed ARM APK, migration and source changes."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT/'deliverables/20261005'
OUT.mkdir(parents=True, exist_ok=True)
APK = ROOT/'ShareGuardAndroid/app/build/outputs/apk/debug/app-debug.apk'
JAVA = r'C:\Program Files\Java\jdk-17\bin\java.exe'
def run(args):
    return subprocess.run([str(a) for a in args], capture_output=True, text=True, encoding='utf-8', errors='replace', check=True).stdout

cert = run([JAVA,'-jar',r'C:\Android\build-tools\36.1.0\lib\apksigner.jar','verify','--verbose','--print-certs',APK])
assert 'c21bb6c4f34f2fd57914e7837e375e974480ca3f' in cert, 'Must match installed signing certificate'
run([r'C:\Android\build-tools\36.1.0\zipalign.exe','-c','-P','16','4',APK])
dex = run([JAVA,r'-Dcom.android.sdklib.toolsdir=C:\Android\cmdline-tools\latest','-classpath',
    r'C:\Android\cmdline-tools\latest\lib\apkanalyzer-classpath.jar','com.android.tools.apk.analyzer.ApkAnalyzerCli',
    'dex','code','--class','com.shareguard.app.BuildConfig',APK])
fields = dict(re.findall(r'\.field public static final (\w+):Ljava/lang/String; = "([^"\n]*)"',dex))
settings = dict(line.split('=',1) for line in (ROOT/'ShareGuardAndroid/build/review-20261005/app.properties').read_text().splitlines())
assert all(fields.get(name)==(value.rstrip('/') if name == 'SHAREGUARD_API_BASE_URL' else value) for name,value in settings.items()), 'Connection settings changed unexpectedly'
assert fields['VERSION_NAME']=='1.3'
with zipfile.ZipFile(APK) as archive:
    assert 'lib/arm64-v8a/libK3fAndroid.so' in archive.namelist(), 'Deliverable must include real map native library'

tests = []
for xml in (ROOT/'ShareGuardAndroid/app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    suite=ET.parse(xml).getroot()
    assert int(suite.attrib['failures'])==0 and int(suite.attrib['errors'])==0
    tests.append({'suite':suite.attrib['name'],'tests':int(suite.attrib['tests'])})
assert sum(t['tests'] for t in tests)>=11
lint = ET.parse(ROOT/'ShareGuardAndroid/app/build/reports/lint-results-debug.xml').getroot()
issues = lint.findall('issue')
assert not any(i.attrib.get('severity') in ('Error','Fatal') for i in issues), 'Lint has errors'
db_checks = (ROOT/'ShareGuardAndroid/build/feature-db-tests/result.txt').read_text(encoding='utf-8').splitlines()
assert len(db_checks)>=26
sql = ROOT/'ShareGuardAndroid/database/migrations/20261005_transactions_chat_notifications.sql'
assert sql.read_bytes()==(ROOT/'backend/src/main/resources/db/migration/V4__transactions_chat_notifications.sql').read_bytes()
target=OUT/'ShareGuard-features-20261005.apk'
shutil.copy2(APK,target)
shutil.copy2(sql,OUT/sql.name)
shutil.copy2(ROOT/'docs/20261005-feature-update.md',OUT/'README.md')
(OUT/'verification.json').write_text(json.dumps({
    'version':'1.3','versionCode':4,'apk_sha256':hashlib.sha256(target.read_bytes()).hexdigest(),
    'signing_certificate_matches_installed_app':True,'zipalign_16kb':True,'public_configuration_values_preserved':len(settings),
    'unit_tests':tests,'database_checks':db_checks,'lint_errors':0,'lint_warnings':sum(i.attrib.get('severity')=='Warning' for i in issues),
    'ui_smoke':'Not performed by this packaging tool; verify this exact build separately.',
    'production_database_migration_applied':False,'physical_phone_tested':False,
    'background_notifications':'JobScheduler periodic polling, 15 minutes or later; not immediate FCM push'
},ensure_ascii=False,indent=2),encoding='utf-8')
(OUT/'apk-signature.txt').write_text(cert,encoding='utf-8')
paths = [
    'ShareGuardAndroid/app/build.gradle.kts',
    'ShareGuardAndroid/app/src/main/AndroidManifest.xml',
    'ShareGuardAndroid/app/src/main/java/com/shareguard/app/MainActivity.kt',
    'ShareGuardAndroid/app/src/main/java/com/shareguard/app/data/SupabaseRestClient.kt',
    'ShareGuardAndroid/app/src/main/java/com/shareguard/app/ReservationRules.kt',
    'ShareGuardAndroid/app/src/main/java/com/shareguard/app/ItemImageLoader.kt',
    'ShareGuardAndroid/app/src/main/java/com/shareguard/app/NotificationDelivery.kt',
    'ShareGuardAndroid/app/src/main/res/drawable/ic_notification.xml',
    'ShareGuardAndroid/app/src/test/java/com/shareguard/app/ReservationRulesTest.kt',
    'ShareGuardAndroid/database/migrations/20261005_transactions_chat_notifications.sql',
    'backend/src/main/resources/db/migration/V4__transactions_chat_notifications.sql',
    'docs/20261005-feature-update.md','tools/prepare_review_build.py','tools/test_feature_database.py','tools/package_feature_update.py'
]
with zipfile.ZipFile(OUT/'ShareGuard-source-changes-20261005.zip','w',zipfile.ZIP_DEFLATED) as archive:
    for path in paths: archive.write(ROOT/path,path)
print(json.dumps({'apk':str(target),'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),
    'unit_tests':sum(t['tests'] for t in tests),'database_checks':len(db_checks),'lint_errors':0},ensure_ascii=True))
