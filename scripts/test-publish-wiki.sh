#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/dokubridge-wiki-test.XXXXXX")"
trap 'rm -rf -- "$test_root"' EXIT
source_repo="$test_root/source"
wiki_remote="$test_root/wiki.git"
seed_repo="$test_root/seed"

fail() {
    printf '%s\n' "$1" >&2
    exit 1
}

commit() {
    git -C "$1" -c user.name='Piotr Nowak' -c user.email='piotr_nowak@outlook.com' \
        -c commit.gpgsign=false commit --quiet -m "$2"
}

publish() {
    GIT_ALLOW_PROTOCOL=file DOKUBRIDGE_WIKI_REMOTE="$wiki_remote" \
        "$source_repo/scripts/publish-wiki.sh" > "$test_root/publish.log" 2>&1 \
        || { cat "$test_root/publish.log" >&2; fail 'Wiki publication failed.'; }
}

git init --quiet --initial-branch=main "$source_repo"
mkdir -p "$source_repo/scripts" "$source_repo/docs/wiki"
cp "$project_root/scripts/publish-wiki.sh" "$source_repo/scripts/publish-wiki.sh"
cat > "$source_repo/docs/wiki/Home.md" <<'MD'
# Home

[Guide](Guide.md#details)
MD
cat > "$source_repo/docs/wiki/Guide.md" <<'MD'
# Guide

## Details

Committed content.
MD
git -C "$source_repo" add .
commit "$source_repo" 'test: create documentation sources'
source_revision="$(git -C "$source_repo" rev-parse --short HEAD)"

git init --quiet --bare --initial-branch=main "$wiki_remote"
git init --quiet --initial-branch=main "$seed_repo"
printf '%s\n' 'Existing custom page.' > "$seed_repo/Custom.md"
git -C "$seed_repo" add .
commit "$seed_repo" 'test: initialize unrelated wiki content'
git -C "$seed_repo" remote add origin "$wiki_remote"
git -C "$seed_repo" push --quiet origin main

printf '%s\n' 'Staged content must not be published.' > "$source_repo/docs/wiki/Home.md"
git -C "$source_repo" add docs/wiki/Home.md
printf '%s\n' 'Unstaged content must not be published.' > "$source_repo/docs/wiki/Guide.md"
printf '%s\n' 'Untracked content must not be published.' > "$source_repo/docs/wiki/Untracked.md"
source_status="$(git -C "$source_repo" status --porcelain)"
publish

cat > "$test_root/expected-home" <<'MD'
# Home

[Guide](Guide#details)
MD
git --git-dir="$wiki_remote" show main:Home.md > "$test_root/actual-home"
cmp -s "$test_root/expected-home" "$test_root/actual-home" \
    || fail 'The publisher used staged documentation instead of its recorded source commit.'
git -C "$source_repo" show HEAD:docs/wiki/Guide.md > "$test_root/expected-guide"
git --git-dir="$wiki_remote" show main:Guide.md > "$test_root/actual-guide"
cmp -s "$test_root/expected-guide" "$test_root/actual-guide" \
    || fail 'The publisher used unstaged documentation.'
if git --git-dir="$wiki_remote" cat-file -e main:Untracked.md 2>/dev/null; then
    fail 'The publisher included an untracked page.'
fi
[[ "$(git --git-dir="$wiki_remote" show main:Custom.md)" == 'Existing custom page.' ]] \
    || fail 'The publisher changed an unrelated wiki page.'
[[ "$(git -C "$source_repo" status --porcelain)" == "$source_status" ]] \
    || fail 'The publisher changed the source worktree or index.'
[[ "$(git --git-dir="$wiki_remote" log -1 main --format=%s)" == "docs: sync DokuBridge documentation from $source_revision" ]] \
    || fail 'The publication did not record the source commit.'
[[ "$(git --git-dir="$wiki_remote" log -1 main --format='%an <%ae> / %cn <%ce>')" \
    == 'Piotr Nowak <piotr_nowak@outlook.com> / Piotr Nowak <piotr_nowak@outlook.com>' ]] \
    || fail 'The publication used a different Git identity.'

published_revision="$(git --git-dir="$wiki_remote" rev-parse main)"
publish
[[ "$(git --git-dir="$wiki_remote" rev-parse main)" == "$published_revision" ]] \
    || fail 'Uncommitted changes caused another wiki commit.'

git -C "$source_repo" restore --source=HEAD --staged --worktree docs/wiki/Home.md docs/wiki/Guide.md
printf '%s\n' '# Updated guide' > "$source_repo/docs/wiki/Guide.md"
git -C "$source_repo" add docs/wiki/Guide.md
commit "$source_repo" 'test: update a committed guide'
publish
[[ "$(git --git-dir="$wiki_remote" show main:Guide.md)" == '# Updated guide' ]] \
    || fail 'The publisher did not include the new committed guide.'
[[ "$(git --git-dir="$wiki_remote" rev-parse main)" != "$published_revision" ]] \
    || fail 'A committed documentation change did not create a publication.'
if git --git-dir="$wiki_remote" cat-file -e main:Untracked.md 2>/dev/null; then
    fail 'The publisher included an untracked page during an update.'
fi

published_revision="$(git --git-dir="$wiki_remote" rev-parse main)"
git -C "$source_repo" rm --quiet docs/wiki/Home.md docs/wiki/Guide.md
printf '%s\n' 'No committed Markdown pages.' > "$source_repo/docs/wiki/.keep"
git -C "$source_repo" add docs/wiki/.keep
commit "$source_repo" 'test: remove committed wiki pages'
if GIT_ALLOW_PROTOCOL=file DOKUBRIDGE_WIKI_REMOTE="$wiki_remote" \
        "$source_repo/scripts/publish-wiki.sh" > "$test_root/publish.log" 2>&1; then
    fail 'The publisher accepted a commit without wiki Markdown pages.'
fi
[[ "$(git --git-dir="$wiki_remote" rev-parse main)" == "$published_revision" ]] \
    || fail 'An invalid source commit changed the wiki.'

printf '%s\n' 'Wiki publisher checks passed: committed pages, navigation, source identity, preserved worktree, unrelated pages, no-op updates and invalid sources.'
