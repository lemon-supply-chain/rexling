#!/usr/bin/env python3
"""Test Activity badge delivery using a disposable session on an explicitly owned server."""
import argparse,json,os,shutil,subprocess
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server',required=True,help='Rex server you own/control')
    parser.add_argument('--serial',default='emulator-5554')
    args=parser.parse_args()
    if not args.serial.startswith('emulator-'):
        parser.error('Use a dedicated emulator: debug instrumentation may replace app data')
    sdk=Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or Path.home()/'Library/Android/sdk')
    adb=shutil.which('adb') or str(sdk/'platform-tools/adb')
    probe=ROOT.parent/'rexling-core/target/debug/rex-probe'
    if not probe.exists():
        parser.error('First run: cargo build --locked --manifest-path rexling-core/Cargo.toml -p rex-probe')
    apks=[ROOT/'app/build/outputs/apk/debug/app-debug.apk',ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
    if not all(apk.exists() for apk in apks):
        parser.error('First build :app:assembleDebug :app:assembleDebugAndroidTest')
    def call(method,params,session=None):
        command=[str(probe),'-S',args.server,'call']
        if session:command+=['--session',session]
        return json.loads(subprocess.check_output(command+[method,json.dumps(params)],text=True))
    script="stty -echo; printf '\\033]7501;state=blocked:kind=permission:app=codex:title=TmVlZHMgaW5wdXQ=\\007'; printf 'ATTENTION_READY\\n'; while IFS= read -r line; do :; done"
    created=call('session.create',{'label':f'rexlink-attention-ui-test-{os.getpid()}','initial_windows':[{'layout':{'block':{'flavor':'com.superlogical.terminal.shell','options':{'shell':'none','command':['/bin/sh','-c',script]}}}}]})
    session=created['session_id']
    try:
        for apk in apks:subprocess.run([adb,'-s',args.serial,'install','-r',str(apk)],check=True)
        command=[adb,'-s',args.serial,'shell','am','instrument','-w','-e','class','dev.lemon.rex.ActivityAttentionTest,dev.lemon.rex.ActivitySheetTest','-e','attention_server',args.server,'-e','attention_session',session,'dev.lemon.rex.test/androidx.test.runner.AndroidJUnitRunner']
        output=subprocess.check_output(command,text=True);print(output)
        if 'OK (6 tests)' not in output:raise RuntimeError('Activity instrumentation failed')
        image=subprocess.check_output([adb,'-s',args.serial,'exec-out','run-as','dev.lemon.rex','cat','cache/activity-attention.png'])
        target=ROOT/'.build/activity-attention.png';target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(image)
        print('Badge screenshot:',target)
    finally:
        call('session.destroy',{'session_id':session},session)

if __name__=='__main__':main()
