#!/usr/bin/env sh
# Minimal Gradle wrapper bootstrap script.
# If gradle-wrapper.jar is missing, run `gradle wrapper` first (requires Gradle 8.7+).
DIR="$( cd "$( dirname "$0" )" && pwd )"
JAR="$DIR/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$JAR" ]; then
  echo "gradle-wrapper.jar not found. Run 'gradle wrapper --gradle-version 8.7' once to generate it." >&2
  exit 1
fi
exec java -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
