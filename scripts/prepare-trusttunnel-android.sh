#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "$0")/.." && pwd)"
# The core the app is built on: its version, and the SHA-256 of the official AAR of it. Its name must match
# coreAar in android/app/build.gradle, and its digest verifyPinnedRuntime's there.
version="1.1.12"
expected="ef66909c00813fd50255900f89b320a998e2a1c74bbd4aa83369fc7f7e310aca"
artifact="trusttunnel-client-android-$version.aar"
destination="$repo_dir/android/app/libs/$artifact"
if [[ -f "$destination" ]] && printf '%s  %s\n' "$expected" "$destination" | shasum -a 256 -c - >/dev/null 2>&1; then
    printf 'Pinned TrustTunnel %s already prepared.\n' "$version"
    exit 0
fi
temporary_dir="$(mktemp -d "${TMPDIR:-/tmp}/trusttunnel-android.XXXXXX")"
trap 'rm -rf "$temporary_dir"' EXIT
# Where the official AAR comes from, in this order. Each source is only a place to fetch the file from:
# whichever one answers, the file is accepted only if its SHA-256 is the pinned one below.
#  1. This repository's own mirror, a release named core-android-<version> holding the AAR that upstream
#     published (upstream offers it only on GitHub Packages, which needs a token to download from). Like the
#     build-tools release of the desktop build, it is not hosted by anybody who can change its page, and
#     curl retries a 5xx.
#  2. The public package page, which supplies download links to the original artifacts. It is HTML that
#     GitHub changes without notice ("Official package asset missing"), so it is the fallback, not the way.
# No PAT, old APK extraction, or alternate native core.
mirror_repository="${TRUSTTUNNEL_ANDROID_MIRROR_REPOSITORY:-${GITHUB_REPOSITORY:-alexsys87/freetunnel}}"
mirror_url="https://github.com/$mirror_repository/releases/download/core-android-$version/$artifact"
file="$temporary_dir/$artifact"
fetched=false
if curl -fsSL --retry 5 --retry-delay 5 --retry-connrefused --connect-timeout 15 --max-time 300 -o "$file" "$mirror_url"; then
    if printf '%s  %s\n' "$expected" "$file" | shasum -a 256 -c - >/dev/null 2>&1; then
        printf 'Fetched %s from the mirror %s.\n' "$artifact" "$mirror_url"
        fetched=true
    else
        printf 'The mirror %s holds a file with another SHA-256; ignoring it.\n' "$mirror_url" >&2
    fi
else
    printf 'No mirror at %s; trying the public package page.\n' "$mirror_url" >&2
fi
if [[ "$fetched" != true ]]; then
    download_url="$(curl -fsSL --retry 2 --connect-timeout 15 --max-time 90 "https://github.com/TrustTunnel/TrustTunnelClient/packages/2733074?version=$version" | python3 -c '
import html, re, sys
name = sys.argv[1]
links = [html.unescape(u) for u in re.findall(r"href=\"([^\"]+)\"", sys.stdin.read())]
url = next((u for u in links if "filename%3D" + name + "&" in u), None)
if not url:
    sys.exit("Official package asset missing: neither the mirror nor the package page has it. Mirror the AAR as the release core-android-" + sys.argv[2] + " of this repository (see android/README.md).")
print(url)
' "$artifact" "$version")"
    curl -fsSL --retry 2 --connect-timeout 15 -o "$file" "$download_url"
fi
# Said in full, so that a new version's digest can be read from the log before it is pinned.
printf 'Downloaded %s, SHA-256 %s\n' "$artifact" "$(shasum -a 256 "$temporary_dir/$artifact" | cut -d' ' -f1)"
printf '%s  %s\n' "$expected" "$temporary_dir/$artifact" | shasum -a 256 -c -
mkdir -p "$(dirname "$destination")"
mv "$temporary_dir/$artifact" "$destination"
printf 'Prepared official TrustTunnel %s.\n' "$version"
