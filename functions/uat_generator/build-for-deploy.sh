#!/usr/bin/env bash
# Builds everything `catalyst deploy` needs for this Java function:
#   lib/*.jar          runtime dependencies from Maven
#   lib/uat-classes.jar  the compiled com.uat.generator classes
#   .classpath         regenerated to list exactly the jars in lib/
#
# lib/ is not in git (.gitignore excludes *.jar), so run this before every deploy:
#   functions/uat_generator/build-for-deploy.sh && catalyst deploy --only functions:uat_generator
set -euo pipefail
cd "$(dirname "$0")"

echo "1/4 Compiling..."
mvn -q clean package

echo "2/4 Copying dependencies into lib/ ..."
mkdir -p lib
# Keep jars Maven doesn't provide (e.g. function-sdk from the Catalyst CLI template).
rm -f lib/uat-classes.jar
mvn -q dependency:copy-dependencies -DoutputDirectory=lib -DincludeScope=runtime -DoverWriteReleases=true
mvn -q dependency:copy -Dartifact=javax.servlet:javax.servlet-api:4.0.1 -DoutputDirectory=lib

echo "3/4 Packaging classes as lib/uat-classes.jar ..."
jar cf lib/uat-classes.jar -C target/classes .

echo "4/4 Writing .classpath ..."
{
  echo '<?xml version="1.0" encoding="UTF-8"?>'
  echo '<classpath>'
  for j in $(ls lib/*.jar | sort); do
    printf '\t<classpathentry kind="lib" path="%s"/>\n' "$j"
  done
  printf '\t<classpathentry including="**/*.java" kind="src" path="src/main/java"/>\n'
  printf '\t<classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>\n'
  printf '\t<classpathentry kind="output" path=".output"/>\n'
  echo '</classpath>'
} > .classpath

# The Catalyst entry point (GenerateServlet.java in this folder) needs the
# Advanced I/O runtime class. Maven doesn't supply it; the Catalyst CLI does.
found=""
for j in lib/*.jar; do
  if unzip -l "$j" 2>/dev/null | grep -q "com/catalyst/advanced/CatalystAdvancedIOHandler.class"; then
    found="$j"; break
  fi
done
if [ -z "$found" ]; then
  echo
  echo "MISSING: the Catalyst Advanced I/O runtime (CatalystAdvancedIOHandler)."
  echo "Get it once from a fresh Catalyst Java function template:"
  echo "  cd /tmp && mkdir cat-sdk && cd cat-sdk && catalyst init   # choose Functions > Java > Advanced I/O"
  echo "  cp functions/*/lib/*.jar <this repo>/functions/uat_generator/lib/"
  echo "Then run this script again."
  exit 1
fi

echo
echo "Ready: $(ls lib/*.jar | wc -l | tr -d ' ') jars in lib/, runtime in $found"
echo "Deploy with:  catalyst deploy --only functions:uat_generator"
