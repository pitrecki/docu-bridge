#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source_commit="$(git -C "$project_root" rev-parse --verify HEAD)"
source_revision="$(git -C "$project_root" rev-parse --short "$source_commit")"
wiki_remote="${DOKUBRIDGE_WIKI_REMOTE:-https://github.com/pitrecki/docu-bridge.wiki.git}"
publication_root="$(mktemp -d "${TMPDIR:-/tmp}/dokubridge-wiki.XXXXXX")"
trap 'rm -rf -- "$publication_root"' EXIT
wiki_checkout="$publication_root/wiki"
source_pages="$publication_root/pages"

# Resolve both page names and contents from one immutable commit, even if HEAD or the worktree changes.
git -C "$project_root" ls-tree --name-only -z "$source_commit:docs/wiki" > "$source_pages"
printf 'Publishing committed documentation from %s\n' "$source_revision"

if ! git clone --quiet -- "$wiki_remote" "$wiki_checkout"; then
    printf '%s\n' 'Cannot access the wiki repository. Enable Wiki and create its first page on GitHub, then check Git push access.' >&2
    exit 1
fi

pages=()
while IFS= read -r -d '' page; do
    [[ "$page" == *.md ]] || continue
    # Wiki page URLs omit the .md suffix used by source navigation in the main repo.
    git -C "$project_root" show "$source_commit:docs/wiki/$page" \
        | sed -E 's@\]\(([[:alnum:]_-]+)\.md(#[^)]*)?\)@](\1\2)@g' > "$wiki_checkout/$page"
    pages+=("$page")
done < "$source_pages"
if [[ ${#pages[@]} -eq 0 ]]; then
    printf '%s\n' 'The source commit contains no wiki Markdown pages.' >&2
    exit 1
fi
git -C "$wiki_checkout" add -- "${pages[@]}"
if git -C "$wiki_checkout" diff --cached --quiet; then
    printf '%s\n' 'Wiki already matches the committed documentation.'
    exit 0
fi

git -C "$wiki_checkout" -c user.name='Piotr Nowak' -c user.email='piotr_nowak@outlook.com' \
    commit -m "docs: sync DokuBridge documentation from $source_revision"
git -C "$wiki_checkout" push origin HEAD
