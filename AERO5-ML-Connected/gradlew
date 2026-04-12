#!/bin/sh
#
# Gradle start up script for UNIX
#
GRADLE_APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`
APP_HOME="`pwd -P`"
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Determine the Java command to use
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

exec "$JAVACMD"  \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"
