#!/usr/bin/env bash
set -euo pipefail

repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
(cd -- "$repo" && node ops/scan-secrets.mjs)
out=${1:?Specify an output directory outside the frontend build directory}
mkdir -p -- "$out"
out=$(cd -- "$out" && pwd -P)
if [[ $out == "$repo/frontend/dist" || $out == "$repo/frontend/dist/"* ]]; then
    echo 'The output directory must be outside frontend/dist.' >&2
    exit 2
fi

shopt -s nullglob
jars=("$repo"/backend/target/studysphere-backend-*.jar)
if (( ${#jars[@]} != 1 )) || [[ ! -f "$repo/frontend/dist/index.html" ]]; then
    echo 'Build the backend and frontend first. Exactly one Spring Boot JAR is required.' >&2
    exit 1
fi

stage=$(mktemp -d)
trap 'rm -rf -- "$stage"' EXIT
bundle="$stage/studysphere"
mkdir -p -- "$bundle/web" "$bundle/ops"
cp -- "${jars[0]}" "$bundle/studysphere.jar"
cp -R -- "$repo/frontend/dist/." "$bundle/web/"
cp -- "$repo"/ops/{Caddyfile,studysphere.service,data-snapshot.sh,load-smoke.mjs} "$bundle/ops/"
cp -- "$repo/docs/PRODUCTION_RUNBOOK.md" "$bundle/"
cp -- "$repo/.env.example" "$bundle/"
tar -czf "$out/studysphere-native.tar.gz" -C "$stage" studysphere
(cd -- "$out" && sha256sum studysphere-native.tar.gz > studysphere-native.tar.gz.sha256)
