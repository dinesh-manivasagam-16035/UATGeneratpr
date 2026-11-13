#!/usr/bin/env bash
# Compiles the function with the jars in lib/ and runs the dependency-free SelfCheck
# (parsing, rule structuring, lanes vs. matrix, execution plan, diagnostics, runner recipes).
set -euo pipefail
cd "$(dirname "$0")"
OUT="${TMPDIR:-/tmp}/liftoff-selfcheck"
rm -rf "$OUT" && mkdir -p "$OUT"
CP="$(ls lib/*.jar | tr '\n' ':')"
javac -nowarn -d "$OUT" -cp "$CP" $(find src/main/java src/test/java -name '*.java')
java -cp "$OUT:$CP" com.uat.generator.SelfCheck
java -cp "$OUT:$CP" com.uat.generator.CapabilitySheet "$(cd ../.. && pwd)/docs/capabilities"
