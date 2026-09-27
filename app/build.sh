#!/usr/bin/env bash
set -e
SDK=/c/Users/AAA/.workbuddy/android-sdk
BT=$SDK/build-tools/35.0.0
PLAT=$SDK/platforms/android-35/android.jar
APP="$(cd "$(dirname "$0")" && pwd)"
OUT=$APP/out
mkdir -p $OUT/classes
rm -rf $OUT/classes/*

echo "[1/6] javac 编译..."
javac -source 8 -target 8 -classpath "$PLAT" -d "$OUT/classes" "$APP"/src/com/y700/brightnesslock/*.java

echo "[2/6] d8 转 dex..."
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --lib "$PLAT" --output "$OUT" $(find "$OUT/classes" -name '*.class')

echo "[3/6] aapt2 打包资源..."
"$BT/aapt2.exe" link -o "$OUT/base.apk" --manifest "$APP/AndroidManifest.xml" -I "$PLAT" \
  --min-sdk-version 26 --target-sdk-version 35 --version-code 2 --version-name 1.1

echo "[4/6] 注入 classes.dex..."
OUT_WIN=$(cygpath -w "$OUT")
python - "$OUT_WIN" <<'PY'
import zipfile, sys
apk = sys.argv[1] + "\\base.apk"
dex = sys.argv[1] + "\\classes.dex"
z = zipfile.ZipFile(apk, "a")
z.write(dex, "classes.dex", compress_type=zipfile.ZIP_STORED)
z.close()
print("dex injected")
PY

echo "[5/6] zipalign..."
"$BT/zipalign.exe" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "[6/6] apksigner 签名..."
KS=$APP/debug.keystore
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias androiddebugkey -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000
fi
java -jar "$BT/lib/apksigner.jar" sign --ks "$KS" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android --out "$OUT/BrightnessLock.apk" "$OUT/aligned.apk"

echo "完成: $OUT/BrightnessLock.apk"
