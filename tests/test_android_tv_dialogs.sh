#!/usr/bin/env bash
# Two rules about what MainActivity puts on the screen, which a TV remote depends on.
#
# 1. Every system dialog goes through its show(), which gives the buttons a focus
#    ring: on a remote, a dialog shown any other way has buttons that never show which
#    one is focused. This fails on a .show() anywhere else but a Toast's. (The system
#    dialogs are on their way out: the in-app overlays replace them, and then this
#    script bans them outright.)
# 2. Every overlay of the app's own goes through showOverlay(), the one helper that
#    adds to overlayLayer: it is what traps the focus, hides the page behind it,
#    gives the focus back, and handles Back. An overlayLayer.addView( anywhere else,
#    in MainActivity or in any other file, would be an overlay with none of that.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# bare_shows <file>: prints each .show() that bypasses show(), and fails if any.
# The one allowed is the first line of show(AlertDialog dialog) itself.
bare_shows() {
  awk '/\.show\(\)/ && !/Toast\.makeText\(.*\)\.show\(\);/ &&
       !(prev ~ /private void show\(AlertDialog dialog\) \{$/ && /^ *dialog\.show\(\);$/) { print FNR ": " $0; found = 1 }
       { prev = $0 }
       END { exit found }' "$1"
}

# stray_adds <file>: prints each overlayLayer.addView( outside showOverlay, and fails if any.
# showOverlay runs from its declaration to the first line that is a lone 4-space }.
stray_adds() {
  awk '/^    (private )?[A-Za-z.<>]+ showOverlay\(/ { inside = 1 }
       /overlayLayer\.addView\(/ && !inside { print FNR ": " $0; found = 1 }
       inside && /^    }$/ { inside = 0 }
       END { exit found }' "$1"
}

indent() { sed 's/^/    /'; }

fail=0
# check <name> <function> <expect: pass|fail> <file>
check() {
  local name=$1 function=$2 expect=$3 file=$4 got=pass out
  out="$($function "$file")" || got=fail
  if [ "$got" = "$expect" ]; then
    echo "ok - $name"
  else
    echo "not ok - $name: expected $expect, got $got"
    indent <<<"$out"
    fail=1
  fi
}

cat > "$TMP/good" <<'EOF'
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private void show(AlertDialog dialog) {
        dialog.show();
    }
        show(new AlertDialog.Builder(this).setMessage(m));
EOF
check "show() and toasts" bare_shows pass "$TMP/good"
printf '%s\n' '        new AlertDialog.Builder(this).setMessage(m)' '                .setPositiveButton(ok, null).show();' > "$TMP/builder"
check "a builder shown directly" bare_shows fail "$TMP/builder"
printf '%s\n' '        editor.show();' > "$TMP/dialog"
check "a dialog shown directly" bare_shows fail "$TMP/dialog"
printf '%s\n' '    private void confirm() {' '        dialog.show();' > "$TMP/elsewhere"
check "a dialog named dialog, shown elsewhere" bare_shows fail "$TMP/elsewhere"

cat > "$TMP/overlay-good" <<'EOF'
    private Overlay showOverlay(String name, OverlayLayer.Kind kind, View content, String anchorKey, Runnable onClose) {
        Overlay open = overlay(name);
        overlayLayer.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        return overlay;
    }
    private void render() {
        pageRoot.addView(bar);
    }
EOF
check "overlayLayer.addView inside showOverlay" stray_adds pass "$TMP/overlay-good"
cat > "$TMP/overlay-stray" <<'EOF'
    private Overlay showOverlay(String name, OverlayLayer.Kind kind, View content, String anchorKey, Runnable onClose) {
        overlayLayer.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        return overlay;
    }
    private void myOwnPopup() {
        overlayLayer.addView(popup);
    }
EOF
check "overlayLayer.addView in another method" stray_adds fail "$TMP/overlay-stray"
cat > "$TMP/overlay-before" <<'EOF'
    private void earlier() {
        overlayLayer.addView(popup);
    }
    private Overlay showOverlay(String name, OverlayLayer.Kind kind, View content, String anchorKey, Runnable onClose) {
        overlayLayer.addView(layer, new FrameLayout.LayoutParams(-1, -1));
    }
EOF
check "overlayLayer.addView before showOverlay" stray_adds fail "$TMP/overlay-before"
printf '%s\n' '        host.overlayLayer.addView(card);' > "$TMP/overlay-other-file"
check "overlayLayer.addView in some other file" stray_adds fail "$TMP/overlay-other-file"

MAIN="$ROOT/android/app/src/main/java/org/freetunnel/android"
check "MainActivity.java shows its dialogs through show()" bare_shows pass "$MAIN/MainActivity.java"
check "MainActivity.java adds overlays only in showOverlay" stray_adds pass "$MAIN/MainActivity.java"
# Any other file of the app: none of them adds to the overlay layer at all.
others=0
for file in "$MAIN"/*.java "$MAIN"/*.kt; do
  [ "$file" = "$MAIN/MainActivity.java" ] && continue
  [ -f "$file" ] || continue
  others=$((others + 1))
  if ! out="$(stray_adds "$file")"; then
    echo "not ok - $(basename "$file") adds to overlayLayer"
    indent <<<"$out"
    fail=1
  fi
done
[ "$fail" -ne 0 ] || echo "ok - none of the other $others files adds to overlayLayer"
exit "$fail"
