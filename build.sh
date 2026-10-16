#!/system/bin/sh
# ============================================================
#  外屏时钟 FoldClock v1.4.4 —— 一键构建
#
#  两种跑法都行：
#    A) 在这台机器自带的工具链下（不需要 Termux / root / Shizuku）：
#         sh /sdcard/Download/FoldClock/源码重建/build.sh
#    B) 在 Termux 里：
#         bash /sdcard/Download/FoldClock/源码重建/build.sh
#
#  产物：build/FoldClock-v1.4.4.apk（同时复制到 /sdcard/Download/）
#
#  可选环境变量：AJAR  KS  KSPASS  ALIAS
#  KSPASS 必须由外面传入或交互输入，脚本里不保存任何口令。
# ============================================================
set -e

DH=/data/user/0/com.deepseek.harness.compat/files
PROJ=$(cd "$(dirname "$0")" && pwd)
cd "$PROJ"

VERNAME=1.4.4
VERCODE=20
OUTNAME=FoldClock-v${VERNAME}.apk

TZ=${TZ:-Asia/Shanghai}
export TZ

# ---------- 0. 准备工具链 ----------
TOOLCHAIN=""
if command -v aapt2 >/dev/null 2>&1 && command -v javac >/dev/null 2>&1; then
    echo "使用当前 PATH 里的工具链"
elif [ -f "$DH/toolchain.sh" ]; then
    echo "加载本机内置工具链：$DH/toolchain.sh"
    . "$DH/toolchain.sh"
    TOOLCHAIN=builtin
elif [ -x "$PREFIX/bin/aapt2" ]; then
    export PATH="$PREFIX/bin:$PATH"
    echo "使用 Termux 工具链：$PREFIX"
else
    echo "❌ 找不到 aapt2 / javac。请设置 PATH，或确认 $DH/toolchain.sh 存在。"
    exit 1
fi

for c in aapt2 javac d8 apksigner jar; do
    command -v "$c" >/dev/null 2>&1 || { echo "❌ 缺少命令：$c"; exit 1; }
done

# ---------- 1. android.jar ----------
AJAR=${AJAR:-}
if [ -z "$AJAR" ]; then
    for c in \
        "$ANDROID_JAR" \
        "$DH/android-tools/sdk/platforms/android-35/android.jar" \
        "$HOME/android-sdk/platforms/android-35/android.jar" \
        "/sdcard/Download/android-sdk/platforms/android-35/android.jar" ; do
        [ -n "$c" ] && [ -f "$c" ] && AJAR="$c" && break
    done
fi
[ -f "$AJAR" ] || { echo "❌ 找不到 android.jar，请设置 AJAR=..."; exit 1; }
echo "android.jar = $AJAR"

# ---------- 2. 签名密钥 ----------
KS=${KS:-}
if [ -z "$KS" ]; then
    for c in \
        "$DH/keystores/foldclock.jks" \
        "$HOME/keystores/foldclock.jks" \
        "$HOME/keystores/foldclock-release.jks" \
        "/sdcard/Download/FoldClock/签名/foldclock.jks" \
        "/sdcard/Download/FoldClock/签名/foldclock-release.jks" ; do
        [ -n "$c" ] && [ -f "$c" ] && KS="$c" && break
    done
fi
ALIAS=${ALIAS:-}
KSPASS=${KSPASS:-}

if [ -f "$KS" ]; then
    # 猜一个口令/别名组合
    if [ -z "$KSPASS" ]; then
        printf 'keystore password (KSPASS): ' >&2
        read -r KSPASS
        ALIAS=${ALIAS:-foldclock}
    fi
    [ -n "$ALIAS" ] || ALIAS=foldclock
else
    ALIAS=${ALIAS:-foldclock}
    if [ -z "$KSPASS" ]; then
        printf 'keystore password (KSPASS): ' >&2
        read -r KSPASS
    fi
    KS="$DH/keystores/foldclock.jks"
    echo "⚠️  没找到签名密钥，生成新的：$KS"
    echo "    ★ 签名与 v1.3.7 不一致，安装前必须先卸载旧版。"
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -v \
        -keystore "$KS" -alias "$ALIAS" \
        -keyalg RSA -keysize 4096 -validity 10950 \
        -storepass "$KSPASS" -keypass "$KSPASS" \
        -dname "CN=FoldClock, OU=dev, O=FoldClock, L=Beijing, ST=Beijing, C=CN"
    echo "    ★ 请立刻备份：cp $KS /sdcard/Download/FoldClock/签名/"
fi
[ -n "$KSPASS" ] || { echo "❌ 需要提供 KSPASS（或交互输入签名口令）"; exit 1; }
echo "keystore    = $KS (alias=$ALIAS)"

# ---------- 3. 资源 ----------
rm -rf build gen
mkdir -p build/classes build/dex gen

echo "[1/5] aapt2 compile"
aapt2 compile --dir res -o build/res.zip

echo "[2/5] aapt2 link"
aapt2 link -o build/base.apk \
    -I "$AJAR" \
    --manifest AndroidManifest.xml \
    -R build/res.zip \
    --java gen \
    --min-sdk-version 32 \
    --target-sdk-version 35 \
    --version-code "$VERCODE" \
    --version-name "$VERNAME" \
    --auto-add-overlay

# ---------- 4. 编译 ----------
echo "[3/5] javac"
javac -source 8 -target 8 -nowarn -encoding UTF-8 \
    -classpath "$AJAR" -d build/classes \
    $(find src gen -name '*.java')

echo "[4/5] d8 → classes.dex"
d8 --lib "$AJAR" --min-api 32 --output build/dex \
    $(find build/classes -name '*.class')

# ---------- 5. 打包 + 签名 ----------
echo "[5/5] 打包 + 签名"
cp build/base.apk build/unsigned.apk
# 注意：这台机器上**没有 zip**，用 JDK 自带的 jar
( cd build/dex && jar ufM ../unsigned.apk classes.dex )

apksigner sign \
    --ks "$KS" --ks-key-alias "$ALIAS" \
    --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "build/$OUTNAME" build/unsigned.apk

apksigner verify --print-certs "build/$OUTNAME" | grep -i "SHA-256 digest" | head -1

if [ -d /sdcard/Download ]; then
    cp "build/$OUTNAME" "/sdcard/Download/$OUTNAME"
fi

echo
echo "✅ 完成：$PROJ/build/$OUTNAME  ($(stat -c%s "build/$OUTNAME") 字节)"
echo "   已复制到 /sdcard/Download/$OUTNAME"
echo "   ★ 如果提示签名冲突：先卸载 v1.3.7 再装。"
