#!/bin/sh
set -e
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
echo "Gradle bulunamadi. GitHub Actions workflow Gradle 8.7 kurmalidir." >&2
exit 1
