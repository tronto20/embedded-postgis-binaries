#!/bin/bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
ARCH_NAME=amd64
ARGS=()

while [ $# -gt 0 ]; do
  case "$1" in
    -a)
      ARCH_NAME=$2
      shift 2
      ;;
    *)
      ARGS+=("$1")
      shift
      ;;
  esac
done

case "$ARCH_NAME" in
  amd64)
    exec "$SCRIPT_DIR/build-postgres-windows-amd64.sh" "${ARGS[@]}"
    ;;
  arm64v8)
    exec "$SCRIPT_DIR/build-postgres-windows-arm64.sh" "${ARGS[@]}"
    ;;
  *)
    echo "Windows PostGIS builds currently support only amd64 and arm64v8 architectures!" && exit 1;
    ;;
esac
