#!/bin/bash
# Builds AC-PhoneCam.apk (ARCore version) on Debian/Ubuntu without Android Studio.
# sudo apt install aapt apksigner dalvik-exchange libandroid-23-java openjdk-17-jdk-headless zipalign
# libs/arcore_client.aar = ARCore SDK 1.25 (Apache-2.0, from github.com/google-ar/arcore-unity-sdk)
set -e
cd "$(dirname "$0")"
JAR=/usr/share/java/com.android.android-23.jar
rm -rf out arcore gen unsigned.apk aligned.apk classes.dex; mkdir -p out/classes gen arcore
(cd arcore && unzip -q ../libs/arcore_client.aar)
# resources + R classes (app + ARCore library)
aapt package -f -m -J gen -M AndroidManifest.xml -S res -S arcore/res -I $JAR --extra-packages com.google.ar.core \
     --auto-add-overlay -F unsigned.apk
javac -encoding UTF-8 --release 8 -Xlint:-options -cp $JAR:arcore/classes.jar -d out/classes \
      src/com/phonecam/*.java $(find gen -name R.java)
dalvik-exchange --dex --min-sdk-version=24 --output=classes.dex out/classes arcore/classes.jar
aapt add unsigned.apk classes.dex >/dev/null
# native libs (arm64 + arm32 phones)
mkdir -p stage && rm -rf stage/* && mkdir -p stage/lib && cp -r arcore/jni/arm64-v8a arcore/jni/armeabi-v7a stage/lib/
(cd stage && find lib -type f | xargs aapt add ../unsigned.apk >/dev/null)
zipalign -f -p 4 unsigned.apk aligned.apk
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android -alias phonecam -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=PhoneCam"
apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out AC-PhoneCam.apk aligned.apk
echo "Built AC-PhoneCam.apk"
