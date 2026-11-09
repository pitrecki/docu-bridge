#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
wiki_remote="${DOKUBRIDGE_WIKI_REMOTE:-https://github.com/pitrecki/docu-bridge.wiki.git}"
wiki_checkout="$(mktemp -d "${TMPDIR:-/tmp}/dokubridge-wiki.XXXXXX")"
trap 'rm -rf -- "$wiki_checkout"' EXIT

if ! git clone --quiet -- "$wiki_remote" "$wiki_checkout"; then
    printf '%s\n' 'Cannot access the wiki repository. Enable Wiki and create its first page on GitHub, then check Git push access.' >&2
    exit 1
fi

pages=()
for source in "$project_root"/docs/wiki/*.md; do
    page="$(basename -- "$source")"
    # Wiki page URLs omit the .md suffix used by source navigation in the main repo.
    sed -E 's@\]\(([[:alnum:]_-]+)\.md(#[^)]*)?\)@](\1\2)@g' "$source" > "$wiki_checkout/$page"
    pages+=("$page")
done
git -C "$wiki_checkout" add -- "${pages[@]}"
if git -C "$wiki_checkout" diff --cached --quiet; then
    printf '%s\n' 'Wiki already matches the documentation sources.'
    exit 0
fi

source_revision="$(git -C "$project_root" rev-parse --short HEAD)"
git -C "$wiki_checkout" -c user.name='Piotr Nowak' -c user.email='piotr_nowak@outlook.com' \
    commit -m "docs: sync DokuBridge documentation from $source_revision"
git -C "$wiki_checkout" push origin HEAD
