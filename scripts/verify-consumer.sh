#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"
mkdir -p target
version_file="$(mktemp "$project_root/target/consumer-version.XXXXXX")"
trap 'rm -f "$version_file"' EXIT

# Maven resolves the effective version, including any project version properties.
./mvnw --batch-mode --non-recursive --file "$project_root/pom.xml" "$@" \
    org.apache.maven.plugins:maven-help-plugin:3.5.1:evaluate \
    -Dexpression=project.version "-Doutput=$version_file"
library_version="$(cat "$version_file")"
if [[ -z "$library_version" ]]; then
    printf '%s\n' 'Could not read the library version from the root POM.' >&2
    exit 1
fi

printf 'Verifying installed starter version %s\n' "$library_version"
./mvnw --batch-mode "$@" \
    --file "$project_root/integration-tests/starter-consumer/pom.xml" \
    "-Ddokubridge.version=$library_version" verify
