#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# 把当前构建的两个 APK 同步到共享盘（WebDAV），并**回读校验**。
#
# 为什么要有这个脚本（真机复测踩出来的经验）：
#   · 共享盘的目录列表/文件名解析会缓存，同名的 `app-*.apk` 也可能被缓存或截断
#     → 一律用「临时名写入 → md5 比对 → 改名覆盖」，并把文件名带上 md5；
#   · 挂载点会间歇性"看不见"新文件（`stat` 说没有、[System.IO.File] 说有）
#     → 写完要重挂一次再回读比对，脚本最后会打印 PASS/FAIL；
#   · 只更新 APK-INFO 而不重写 APK 时，客户端可能仍读到旧包
#     → 每次同步都重写两个 APK（刷新 mtime），APK-INFO 顶部打刷新时间戳。
#
# 用法：
#   scripts/sync_webdav.sh [额外说明文本]
# 环境变量：
#   SHARE  覆盖共享盘路径（默认本机 gvfs 挂载点）
#
set -uo pipefail

SHARE="${SHARE:-/run/user/1000/gvfs/dav:host=192.168.0.129,port=5005,ssl=false/团队文件-conandllu/SONY_XZ1}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
DSD="$REPO/app/build/outputs/apk/dsd/debug/app-dsd-debug.apk"
CMP="$REPO/app/build/outputs/apk/companion/debug/app-companion-debug.apk"
NOTE="${1:-}"
URI="dav://192.168.0.129:5005/"

md5() { md5sum "$1" | cut -d' ' -f1; }

remount() {
    pkill -9 -f "^/usr/libexec/gvfsd-dav" 2>/dev/null
    sleep 1
    gio mount "$URI" >/dev/null 2>&1
    sleep 2
}

[ -f "$DSD" ] || { echo "FAIL: 找不到 $DSD（先构建）"; exit 1; }
[ -f "$CMP" ] || { echo "FAIL: 找不到 $CMP（先构建）"; exit 1; }

D_MD5="$(md5 "$DSD")"; D_SHORT="${D_MD5:0:8}"
C_MD5="$(md5 "$CMP")"; C_SHORT="${C_MD5:0:8}"
D_NAME="XZ1-dsd-$D_SHORT.apk"
C_NAME="XZ1-companion-$C_SHORT.apk"
D_SIZE="$(stat -c%s "$DSD")"; C_SIZE="$(stat -c%s "$CMP")"

echo "== 本地构建 =="
echo "   $D_NAME  $D_SIZE B"
echo "   $C_NAME  $C_SIZE B"

remount
[ -d "$SHARE" ] || { echo "FAIL: 挂载点不存在：$SHARE"; exit 1; }

# 1) 用临时名上传（避免同名覆盖踩缓存），再校验、再改名
cp "$DSD" "$SHARE/sync-dsd.new" || { echo "FAIL: 上传 dsd 失败"; exit 1; }
cp "$CMP" "$SHARE/sync-companion.new" || { echo "FAIL: 上传 companion 失败"; exit 1; }
[ "$(md5 "$SHARE/sync-dsd.new")" = "$D_MD5" ] || { echo "FAIL: dsd 上传后 md5 不符"; exit 1; }
[ "$(md5 "$SHARE/sync-companion.new")" = "$C_MD5" ] || { echo "FAIL: companion 上传后 md5 不符"; exit 1; }
mv -f "$SHARE/sync-dsd.new" "$SHARE/$D_NAME" || { echo "FAIL: 改名 dsd 失败"; exit 1; }
mv -f "$SHARE/sync-companion.new" "$SHARE/$C_NAME" || { echo "FAIL: 改名 companion 失败"; exit 1; }

# 2) 重写 APK-INFO（顶部刷新戳 + 当前包信息）
python3 - "$SHARE" "$D_NAME" "$D_SIZE" "$D_MD5" "$C_NAME" "$C_SIZE" "$C_MD5" "$NOTE" <<'PY'
import os, sys, time, re
share, dn, ds, dm, cn, cs, cm, note = sys.argv[1:9]
p = os.path.join(share, "APK-INFO.txt")
old = ""
if os.path.exists(p):
    try:
        old = open(p, encoding='utf-8').read()
    except Exception:
        old = ""
old = re.sub(r"^🔄[^\n]*\n", "", old)
head = "🔄 已同步到共享盘并回读校验：%s\n" % time.strftime("%Y-%m-%d %H:%M")
if note:
    head += "说明：%s\n" % note
body = (
    head +
    "\n取包顺序（实测）：1) [System.IO.File]::Exists → [System.IO.File]::Copy  "
    "2) Copy-Item  3) bash cp 按名直取  4) 最后才看目录列表\n"
    "    %s   %s B   md5 %s\n"
    "    %s   %s B   md5 %s\n"
    "校验（Windows）：certutil -hashfile %s MD5 → 必须 %s\n" % (dn, ds, dm, cn, cs, cm, dn, dm)
)
rest = old
# 去掉旧的三段头部（刷新戳/取包说明/校验行），保留正文
lines = []
skip = 0
for line in rest.splitlines():
    if (line.startswith("    XZ1-") or line.startswith("校验（Windows）")
            or line.startswith("取包顺序") or line.startswith("取包：")
            or line.startswith("说明：")
            or line.startswith("（") and "作废" in line):
        continue
    lines.append(line)
# 折叠多余空行
out_lines = []
for line in lines:
    if line.strip() == "" and out_lines and out_lines[-1].strip() == "":
        continue
    out_lines.append(line)
rest = "\n".join(out_lines).strip("\n")
open("/tmp/APK-INFO-sync.txt", "w", encoding='utf-8').write(body + ("\n" + rest + "\n" if rest else ""))
print("APK-INFO 准备完成")
PY
cp /tmp/APK-INFO-sync.txt "$SHARE/APK-INFO.new" || { echo "FAIL: 写 APK-INFO 失败"; exit 1; }
mv -f "$SHARE/APK-INFO.new" "$SHARE/APK-INFO.txt" || { echo "FAIL: 替换 APK-INFO 失败"; exit 1; }

# 3) 重挂后再回读，作为最终结论
remount
RD="$(md5 "$SHARE/$D_NAME" 2>/dev/null)"; RC="$(md5 "$SHARE/$C_NAME" 2>/dev/null)"
OK=1
[ "$RD" = "$D_MD5" ] || { echo "FAIL: 回读 dsd md5 不符（$RD）"; OK=0; }
[ "$RC" = "$C_MD5" ] || { echo "FAIL: 回读 companion md5 不符（$RC）"; OK=0; }
LEFT="$(ls "$SHARE" 2>/dev/null | grep -cE '\.new$|^sync-')"
[ "$LEFT" = "0" ] || { echo "WARN: 有残留临时文件 $LEFT 个"; }

echo
echo "== 共享盘回读 =="
ls -l "$SHARE/$D_NAME" "$SHARE/$C_NAME" "$SHARE/APK-INFO.txt" 2>/dev/null | sed 's|.*/||'
if [ "$OK" = "1" ]; then
    echo "PASS: 两个 APK 已同步并回读校验一致"
    echo "      $D_NAME  md5 $D_MD5"
    echo "      $C_NAME  md5 $C_MD5"
    exit 0
else
    echo "FAIL: 同步未通过校验"
    exit 2
fi
