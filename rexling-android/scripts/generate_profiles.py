#!/usr/bin/env python3
"""Generate offline terminal profiles on an explicitly selected test device."""
import argparse
import os
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--copy-existing',action='store_true',help='Copy the last successful collection instead of running it again')
    args=parser.parse_args()
    if not args.serial.startswith("emulator-"):raise SystemExit("Use a dedicated emulator: the Gradle test runner may uninstall the target app and its data.")
    if not args.copy_existing:
        env=os.environ.copy();env['ANDROID_SERIAL']=args.serial
        cmd=[str(ROOT/'scripts/gradle.sh'),':benchmark:connectedProfileAndroidTest',
            '-Pandroid.testInstrumentationRunnerArguments.class=dev.lemon.rex.benchmark.BaselineProfileGenerator','--no-daemon']
        if args.serial.startswith('emulator-'):cmd+=['-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR']
        subprocess.run(cmd,cwd=ROOT,env=env,check=True)
    outputs=ROOT/'benchmark/build/outputs/connected_android_test_additional_output/profile/connected'
    profiles=list(outputs.glob('*/BaselineProfileGenerator_offlineTerminalJourney-startup-prof.txt'))
    if len(profiles)!=1:raise SystemExit('Expected one successful device collection; found '+str(len(profiles)))
    data=profiles[0].read_text()
    if 'Ldev/lemon/rex/' not in data:raise SystemExit('Collection does not contain Rexlet code')
    # Startup-profile rules are also valid Baseline Profile rules. This single offline
    # startup-to-terminal journey covers both, including Compose and JNI decoding.
    for name in ['baseline-prof.txt','startup-prof.txt']:
        (ROOT/'app/src/main'/name).write_text(data)
    print('Copied '+str(len(data.splitlines()))+' measured rules to app/src/main/')

if __name__=='__main__':main()
