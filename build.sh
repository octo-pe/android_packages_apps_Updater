#!/bin/bash
export HERE=$(pwd)
cd ~/pe/pe/
. lunch.sh
cd $HERE
pwd
set -o errexit
time mka -j12 S3Updater
echo zipaligning
zipalign -p -f -v 4 ~/pe/pe/out/target/product/$device/system_ext/priv-app/S3Updater/S3Updater.apk dist/S3Updater.apk > /dev/null
zipalign -c -v 4 dist/S3Updater.apk
echo signing
apksigner sign -v --key ~/.android-certs/platform.pk8 --cert ~/.android-certs/platform.x509.pem dist/S3Updater.apk
apksigner verify -v dist/S3Updater.apk