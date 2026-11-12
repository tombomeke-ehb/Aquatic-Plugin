#!/usr/bin/env bash
# Builds AquaticSMP. Uses `mvn` from PATH, or IntelliJ's bundled Maven as a fallback.
set -e
cd "$(dirname "$0")"
if command -v mvn >/dev/null 2>&1; then MVN=mvn; else
  MVN="$(ls -d "/c/Program Files/JetBrains"/*/plugins/maven/lib/maven3/bin/mvn 2>/dev/null | tail -1)"
  [ -d "/c/Program Files/Java/jdk-21.0.11" ] && export JAVA_HOME="C:/Program Files/Java/jdk-21.0.11"
fi
"$MVN" -q "${@:-package}"
ls aquatic-dist/target/AquaticSMP-*.jar | grep -v original | sed "s/^/Built: /"
