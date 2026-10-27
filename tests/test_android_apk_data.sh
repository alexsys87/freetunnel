#!/usr/bin/env bash
# scripts/check-apk-data.sh fails for an APK that lacks the shared data, or has it changed,
# and passes one that has it as the repository does. Fixtures: an APK is a zip.
#
#   bash tests/test_android_apk_data.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$ROOT/scripts/check-apk-data.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

command -v unzip > /dev/null || { echo "unzip is needed" >&2; exit 2; }
command -v python3 > /dev/null || { echo "python3 is needed" >&2; exit 2; }

fail=0
# apk <path> <name in the zip or ""> <file to put there>: a zip with that one entry, or none.
apk() {
  python3 - "$1" "$2" "$3" <<'PY'
import sys, zipfile
path, name, source = sys.argv[1:4]
with zipfile.ZipFile(path, "w") as z:
    z.writestr("AndroidManifest.xml", "x")
    if name:
        z.write(source, name)
PY
}
check() {
  local name=$1 expect=$2 file=$3 got=pass out
  out="$(bash "$SCRIPT" "$file" 2>&1)" || got=fail
  if [ "$got" = "$expect" ]; then
    echo "ok - $name"
  else
    echo "not ok - $name: expected $expect, got $got"
    sed 's/^/    /' <<<"$out"
    fail=1
  fi
}

apk "$TMP/good.apk" assets/split/recommended-russia.txt "$ROOT/data/split/recommended-russia.txt"
check "an APK with the list as the repository has it" pass "$TMP/good.apk"

printf 'yandex.ru\n' > "$TMP/short.txt"
apk "$TMP/short.apk" assets/split/recommended-russia.txt "$TMP/short.txt"
check "an APK with another list" fail "$TMP/short.apk"

{ cat "$ROOT/data/split/recommended-russia.txt"; echo "extra.example"; } > "$TMP/longer.txt"
apk "$TMP/longer.apk" assets/split/recommended-russia.txt "$TMP/longer.txt"
check "an APK with a rule more" fail "$TMP/longer.apk"

apk "$TMP/elsewhere.apk" assets/recommended-russia.txt "$ROOT/data/split/recommended-russia.txt"
check "an APK with the list in the wrong place" fail "$TMP/elsewhere.apk"

apk "$TMP/none.apk" "" ""
check "an APK with none" fail "$TMP/none.apk"

if bash "$SCRIPT" > /dev/null 2>&1; then
  echo "not ok - no APK named: expected fail, got pass"
  fail=1
else
  echo "ok - no APK named"
fi
exit "$fail"
