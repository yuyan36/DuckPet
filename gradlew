#!/bin/sh

# Gradle startup script for POSIX

dirname=$( dirname "$0" )

APP_HOME=$( cd "${dirname}" >/dev/null && pwd )

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

java -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
