#!/system/bin/sh
# ============================================================
#  外屏时钟 FoldClock —— 一键构建（资源 → 编译 → dex → 打包 → 签名）
#
#  依赖（三选一，脚本会自己找）：
#    1. PATH 里已有 aapt2 / javac / d8 / apksigner
#    2. Termux：pkg install aapt2 openjdk-17（d8 / apksigner 随 build-tools）
#    3. 自带一套放在 $TOOLCHAIN_SH 指向的脚本里（会 source 它）
#
#  环境变量：
#    AJAR        android.jar 路径（不设就按常见位置找）
#    KS          签名密钥路径（不设就在 ./keystore/foldclock.jks 新建）
#    KSPASS      密钥口令（不设就提示输入，脚本里不存任何口令）
#    ALIAS       密钥别名，默认 foldclock
#    OUTDIR      产物目录，默认 ./build
#
#  产物：$OUTDIR/FoldClock-<版本>.apk
# ============================================================
set -e

PROJ=$(cd "$(dirname "$0")" && pwd)
cd "$PROJ"

# ---- 版本号（改这里，或改清单）----
VERNAME=$(grep -oE 'android:versionName="[^"]+"' AndroidManifest.xml | head -1 | sed 's/.*="//;s/"//')
VERCODE=$(grep -oE 'android:versionCode="[^"]+"' AndroidManifest.xml | head -1 | sed 's/.*="//;s/"//')
[ -n "$VERNAME" ] || VERNAME=dev
[ -n "$VERCODE" ] || VERCODE=1
OUTDIR=${OUTDIR:-$PROJ/build}
OUTNAME=FoldClock-$VERNAME.apk

# ---- 工具链 ----
if [ -n "$TOOLCHAIN_SH" ] && [ -f "$TOOLCHAIN_SH" ]; then
    # shellcheck disable=SC1090
    . "$TOOLCHAIN_SH"
fi
for c in aapt2 javac d8 apksigner; do
    command -v "$c" >/dev/null 2>&1 || {
        echo "缺少命令：$c"
        echo "  Termux 里可以：pkg install aapt2 openjdk-17"
        exit 1
    }
done

# ---- android.jar ----
if [ -z "$AJAR" ]; then
    for c in \
        "$ANDROID_JAR" \
        "$ANDROID_HOME/platforms/android-35/android.jar" \
        "$ANDROID_SDK_ROOT/platforms/android-35/android.jar" \
        "$HOME/android-sdk/platforms/android-35/android.jar" \
        "$HOME/Android/Sdk/platforms/android-35/android.jar" \
        "$PREFIX/share/android-sdk/platforms/android-35/android.jar" ; do
        [ -n "$c" ] && [ -f "$c" ] && AJAR="$c" && break
    done
fi
[ -f "$AJAR" ] || { echo "找不到 android.jar，请设置 AJAR=/path/to/android.jar"; exit 1; }
echo "android.jar : $AJAR"

# ---- 签名密钥 ----
ALIAS=${ALIAS:-foldclock}
if [ -z "$KS" ]; then
    KS="$PROJ/keystore/foldclock.jks"
fi
if [ ! -f "$KS" ]; then
    echo "没有找到签名密钥，将在 $KS 新建一个。"
    echo "（这个密钥以后要一直用同一个，否则无法覆盖升级；请务必备份，不要提交到 git）"
    mkdir -p "$(dirname "$KS")"
    if [ -z "$KSPASS" ]; then
        printf "给新密钥设置口令: "
        stty -echo 2>/dev/null || true
        read KSPASS
        stty echo 2>/dev/null || true
        echo
    fi
    keytool -genkeypair -v \
        -keystore "$KS" -alias "$ALIAS" \
        -keyalg RSA -keysize 4096 -validity 10950 \
        -storepass "$KSPASS" -keypass "$KSPASS" \
        -dname "CN=FoldClock, OU=dev, O=FoldClock, L=Beijing, ST=Beijing, C=CN"
fi
if [ -z "$KSPASS" ]; then
    printf "签名密钥口令: "
    stty -echo 2>/dev/null || true
    read KSPASS
    stty echo 2>/dev/null || true
    echo
fi

# ---- 构建 ----
rm -rf "$OUTDIR/gen"
mkdir -p "$OUTDIR/classes" "$OUTDIR/dex" "$OUTDIR/gen"

echo "[1/5] aapt2 compile"
aapt2 compile --dir res -o "$OUTDIR/res.zip"

echo "[2/5] aapt2 link"
aapt2 link -o "$OUTDIR/base.apk" \
    -I "$AJAR" \
    --manifest AndroidManifest.xml \
    -R "$OUTDIR/res.zip" \
    --java "$OUTDIR/gen" \
    --min-sdk-version 32 \
    --target-sdk-version 35 \
    --version-code "$VERCODE" \
    --version-name "$VERNAME" \
    --auto-add-overlay

echo "[3/5] javac"
javac -source 8 -target 8 -nowarn -encoding UTF-8 \
    -classpath "$AJAR" -d "$OUTDIR/classes" \
    $(find src "$OUTDIR/gen" -name '*.java')

echo "[4/5] d8"
d8 --lib "$AJAR" --min-api 32 --output "$OUTDIR/dex" \
    $(find "$OUTDIR/classes" -name '*.class')

echo "[5/5] 打包 + 签名"
cp "$OUTDIR/base.apk" "$OUTDIR/unsigned.apk"
# Android 上通常没有 zip，用 JDK 自带的 jar
if command -v jar >/dev/null 2>&1; then
    ( cd "$OUTDIR/dex" && jar ufM ../unsigned.apk classes.dex )
else
    ( cd "$OUTDIR/dex" && zip -q ../unsigned.apk classes.dex )
fi

apksigner sign \
    --ks "$KS" --ks-key-alias "$ALIAS" \
    --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$OUTDIR/$OUTNAME" "$OUTDIR/unsigned.apk"

apksigner verify --print-certs "$OUTDIR/$OUTNAME" | grep -i 'SHA-256 digest' | head -1

# 手机上顺手拷一份出来，方便直接点安装
if [ -d /sdcard/Download ]; then
    cp "$OUTDIR/$OUTNAME" "/sdcard/Download/$OUTNAME"
    echo "已复制到 /sdcard/Download/$OUTNAME"
fi

echo
echo "完成：$OUTDIR/$OUTNAME ($(stat -c%s "$OUTDIR/$OUTNAME") 字节)"
