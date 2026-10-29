#!/usr/bin/env bash
# 在 GitHub 上创建/更新 Release 并上传签名 APK。
#
# 用法：
#   GITHUB_TOKEN=ghp_xxx scripts/github_release.sh          # 用环境变量传 token
#   scripts/github_release.sh                              # 或先把 token 写进 ~/.github_token（chmod 600）
#
# 可选环境变量：
#   REPO=conandllu/xz1-dsd-player   TAG=v1.0   NAME="XZ1 DSD Player v1.0"   NOTES=docs/release/v1.0.md
#   DRY_RUN=1  → 只打印将执行的请求，不真的上传
#
# token 权限：classic PAT 勾 `repo`；fine-grained PAT 选该仓库 + Contents: Read and write。
set -euo pipefail
cd "$(dirname "$0")/.."

REPO=${REPO:-conandllu/xz1-dsd-player}
TAG=${TAG:-v1.0}
NAME=${NAME:-"XZ1 DSD Player $TAG"}
NOTES=${NOTES:-"docs/release/${TAG}.md"}
DRY_RUN=${DRY_RUN:-0}

TOKEN=${GITHUB_TOKEN:-}
if [ -z "$TOKEN" ] && [ -f "$HOME/.github_token" ]; then
  TOKEN=$(tr -d '\r\n' < "$HOME/.github_token")
fi
if [ -z "$TOKEN" ]; then
  echo "✗ 缺少 token：请 export GITHUB_TOKEN=… 或写入 ~/.github_token（chmod 600）" >&2
  exit 1
fi

API=https://api.github.com
AUTH=(-H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json")

# 要上传的资产：本地文件:release 里的文件名
ASSETS=(
  "app/build/outputs/apk/dsd/release/app-dsd-release.apk:XZ1-DSD-Player-${TAG}-dsd.apk"
  "app/build/outputs/apk/companion/release/app-companion-release.apk:XZ1-DSD-Player-${TAG}-companion.apk"
)

say() { echo "[release] $*"; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# 调 API：$1=描述 $2=方法 $3=url，其余参数透传给 curl；非 2xx 打印 GitHub 的 message 后退出
api() {
  local what=$1 method=$2 url=$3; shift 3
  local body="$WORK/body" code
  code=$(curl -sS -o "$body" -w '%{http_code}' -X "$method" "${AUTH[@]}" "$@" "$url" || echo 000)
  if [ "$code" -lt 200 ] || [ "$code" -ge 300 ]; then
    echo "✗ $what 失败（HTTP $code）" >&2
    python3 -c 'import json,sys
try:
    d=json.load(open(sys.argv[1]))
    print("  " + str(d.get("message","")))
    for e in d.get("errors",[]) or []: print("  -", e)
except Exception:
    print(open(sys.argv[1]).read()[:300])' "$body" >&2
    case "$code" in
      401) echo "  提示：token 无效或过期（~/.github_token）" >&2 ;;
      403) echo "  提示：token 权限不足 —— fine-grained PAT 需要 Repository permissions → Contents: Read and write" >&2 ;;
      404) echo "  提示：仓库不存在，或 token 未被授权访问该仓库" >&2 ;;
    esac
    exit 1
  fi
  cat "$body"
}

for a in "${ASSETS[@]}"; do
  f=${a%%:*}
  [ -f "$f" ] || { echo "✗ 缺少构建产物：$f（先跑 ./gradlew assembleDsdRelease assembleCompanionRelease）" >&2; exit 1; }
done
[ -f "$NOTES" ] || { echo "✗ 缺少 release notes：$NOTES" >&2; exit 1; }

say "仓库 $REPO，tag $TAG，notes $NOTES"
say "校验产物："
for a in "${ASSETS[@]}"; do
  f=${a%%:*}; n=${a##*:}
  printf '        %-46s %10s B  md5=%s\n' "$n" "$(stat -c%s "$f")" "$(md5sum "$f" | cut -d' ' -f1)"
done

# 1) 已存在同名 release 就复用，否则创建
REL_ID=$(curl -fsS "${AUTH[@]}" "$API/repos/$REPO/releases/tags/$TAG" 2>/dev/null \
         | python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))' 2>/dev/null || true)

if [ -z "${REL_ID:-}" ]; then
  say "创建 release $TAG"
  if [ "$DRY_RUN" = "1" ]; then echo "  (dry-run) POST $API/repos/$REPO/releases"; REL_ID=0; else
    BODY=$(python3 -c 'import json,sys; print(json.dumps({"tag_name":sys.argv[1],"name":sys.argv[2],"body":open(sys.argv[3]).read(),"target_commitish":"main","draft":False,"prerelease":False}))' "$TAG" "$NAME" "$NOTES")
    RESP=$(api "创建 release" POST "$API/repos/$REPO/releases" \
           -H "Content-Type: application/json" -d "$BODY")
    REL_ID=$(printf '%s' "$RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
    say "release id=$REL_ID"
  fi
else
  say "release 已存在（id=$REL_ID），复用"
fi

# 2) 逐个上传（同名资产先删掉，便于重跑）
for a in "${ASSETS[@]}"; do
  f=${a%%:*}; n=${a##*:}
  if [ "$DRY_RUN" = "1" ]; then echo "  (dry-run) upload $n"; continue; fi
  OLD=$(curl -fsS "${AUTH[@]}" "$API/repos/$REPO/releases/$REL_ID/assets" 2>/dev/null \
        | python3 -c "import json,sys
name='$n'
for a in json.load(sys.stdin):
    if a.get('name')==name: print(a['id'])" 2>/dev/null || true)
  if [ -n "${OLD:-}" ]; then
    say "删除同名旧资产 $n（id=$OLD）"
    curl -fsS -X DELETE "${AUTH[@]}" "$API/repos/$REPO/releases/assets/$OLD" >/dev/null || true
  fi
  say "上传 $n ($(stat -c%s "$f") B)…"
  RESP=$(api "上传 $n" POST "https://uploads.github.com/repos/$REPO/releases/$REL_ID/assets?name=$n" \
         -H "Content-Type: application/vnd.android.package-archive" --data-binary @"$f")
  printf '%s' "$RESP" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(f"        → {d[\"name\"]}  {d[\"size\"]} B  state={d[\"state\"]}")'
done

# 3) 回读校验
if [ "$DRY_RUN" != "1" ]; then
  say "回读资产列表："
  curl -fsS "${AUTH[@]}" "$API/repos/$REPO/releases/$REL_ID" \
    | python3 -c 'import json,sys
d=json.load(sys.stdin)
print(f"        release {d[\"tag_name\"]}  draft={d[\"draft\"]}  assets={len(d[\"assets\"])}")
for a in d["assets"]: print(f"        - {a[\"name\"]}  {a[\"size\"]} B  {a.get(\"digest\") or \"\"}")'
  echo
  say "完成：https://github.com/$REPO/releases/tag/$TAG"
fi
