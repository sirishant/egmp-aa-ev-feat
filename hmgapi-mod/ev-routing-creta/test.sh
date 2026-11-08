#!/bin/sh
# Host tests of the EV mod: log rows, the log format, the energy model's scaling.
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
AJAR=$(ls -d "$SDK"/platforms/android-* | sort -V | head -1)/android.jar
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac -nowarn -d "$CP" -cp "$AJAR" $(find tools/shim tools/test stubs -name '*.java') \
    $(find src -name '*.java' ! -name EvRouting.java)
for t in SenderRowTest EvLogTest ScaleTest LiveDataTest; do
    java -cp "$CP:$AJAR" hmgapi.mods.evcreta.$t
done
