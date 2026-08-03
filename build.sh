#!/bin/sh
# Rebuild DictShare.apk. Requirements: aapt2, javac (JDK), dx (dalvik-exchange),
# zipalign, apksigner, and android.jar (any recent API level) in $SDK.
set -e
SDK=${SDK:-../sdk}
mkdir -p build/classes
aapt2 compile --dir res -o build/res.zip
aapt2 link -o build/base.apk -I "$SDK/android.jar" --manifest AndroidManifest.xml build/res.zip --auto-add-overlay
javac --release 8 -cp "$SDK/android.jar" -d build/classes src/com/dictshare/app/*.java
dalvik-exchange --dex --output=build/classes.dex build/classes
cp build/base.apk build/unsigned.apk
(cd build && zip -q unsigned.apk classes.dex)
zipalign -f 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks dictshare.keystore --ks-pass pass:dictshare \
  --ks-key-alias dictshare --out build/DictShare.apk build/aligned.apk
echo "Done: build/DictShare.apk"
