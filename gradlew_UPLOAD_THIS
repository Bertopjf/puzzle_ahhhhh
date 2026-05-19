#!/usr/bin/env sh
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$DIR/.gradle}"
exec java -cp "$DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
