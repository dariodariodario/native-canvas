#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
exec mvn "-Dmaven.compiler.release=${JAVA_RELEASE:-8}" package "$@"
