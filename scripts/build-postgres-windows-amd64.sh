#!/bin/bash
set -euo pipefail

POSTGIS_VERSION=
PG_BIN_VERSION=

while getopts "v:b:g:" opt; do
    case $opt in
    v) PG_VERSION=$OPTARG ;;
    b) PG_BIN_VERSION=$OPTARG ;;
    g) POSTGIS_VERSION=$OPTARG ;;
    \?) exit 1 ;;
    esac
done

if [ -z "${PG_VERSION:-}" ] ; then
  echo "Postgres version parameter is required!" && exit 1;
fi
if [ -z "${PG_BIN_VERSION:-}" ] ; then
  echo "Postgres binary version parameter is required for amd64 Windows builds!" && exit 1;
fi
if [ -z "${POSTGIS_VERSION:-}" ] ; then
  echo "PostGIS version parameter is required!" && exit 1;
fi

IFS=. read -r PG_MAJOR _ <<EOF
$PG_VERSION
EOF

IFS=. read -r POSTGIS_MAJOR POSTGIS_MINOR _ <<EOF
$POSTGIS_VERSION
EOF

PG_MAJOR=${PG_MAJOR:-0}
POSTGIS_MAJOR=${POSTGIS_MAJOR:-0}
POSTGIS_MINOR=${POSTGIS_MINOR:-0}
POSTGIS_SERIES="$POSTGIS_MAJOR.$POSTGIS_MINOR"

DIST_DIR=$PWD/dist
PKG_DIR=$PWD/package
TRG_DIR=$PWD/bundle

mkdir -p "$DIST_DIR" "$PKG_DIR" "$TRG_DIR"

download_if_missing() {
  local target=$1
  shift
  local urls=("$@")
  local url

  if [ ! -e "$target" ] ; then
    echo "Downloading $(basename "$target")"
    for url in "${urls[@]}"; do
      if curl --fail --silent --show-error --location --globoff --retry 3 --retry-delay 2 --url "$url" --output "$target"; then
        return 0
      fi
    done
    echo "Unable to download $(basename "$target") from any known source" && exit 1;
  fi
}

edb_zip="$DIST_DIR/postgresql-$PG_BIN_VERSION-windows-x64-binaries.zip"
postgis_zip="$DIST_DIR/postgis-bundle-pg${PG_MAJOR}-${POSTGIS_VERSION}x64.zip"

download_if_missing "$edb_zip" \
  "https://get.enterprisedb.com/postgresql/postgresql-$PG_BIN_VERSION-windows-x64-binaries.zip"
download_if_missing "$postgis_zip" \
  "https://download.osgeo.org/postgis/windows/pg${PG_MAJOR}/postgis-bundle-pg${PG_MAJOR}-${POSTGIS_VERSION}x64.zip" \
  "https://download.osgeo.org/postgis/windows/pg${PG_MAJOR}/archive/postgis-bundle-pg${PG_MAJOR}-${POSTGIS_VERSION}x64.zip"

rm -rf "$PKG_DIR"
mkdir -p "$PKG_DIR/edb" "$PKG_DIR/postgis"

unzip -q "$edb_zip" -d "$PKG_DIR/edb"
unzip -q "$postgis_zip" -d "$PKG_DIR/postgis"

pg_dir="$PKG_DIR/edb/pgsql"
postgis_root=$(find "$PKG_DIR/postgis" -mindepth 1 -maxdepth 1 -type d | head -n 1)

if [ ! -d "$pg_dir" ] ; then
  echo "EDB PostgreSQL bundle was not extracted correctly!" && exit 1;
fi
if [ -z "${postgis_root:-}" ] || [ ! -d "$postgis_root" ] ; then
  echo "PostGIS Windows bundle was not extracted correctly!" && exit 1;
fi

mkdir -p "$pg_dir/share/extension" "$pg_dir/share/contrib/postgis-$POSTGIS_SERIES" "$pg_dir/lib"

for postgis_dll in "$postgis_root"/bin/*.dll; do
  dll_name=$(basename "$postgis_dll")

  if [ -e "$pg_dir/bin/$dll_name" ] ; then
    echo "Keeping EDB runtime DLL $dll_name"
    continue
  fi

  cp -fp "$postgis_dll" "$pg_dir/bin/"
done

cp -fp "$postgis_root/lib/postgis-3.dll" "$pg_dir/lib/"
cp -fp "$postgis_root/share/extension/postgis.control" "$pg_dir/share/extension/"
find "$postgis_root/share/extension" -maxdepth 1 -type f -name 'postgis--*.sql' -exec cp -fp {} "$pg_dir/share/extension/" \;

for file in postgis_upgrade.sql postgis_upgrade_for_extension.sql.in; do
  if [ -f "$postgis_root/share/extension/$file" ]; then
    cp -fp "$postgis_root/share/extension/$file" "$pg_dir/share/extension/"
  fi
done

cp -a "$postgis_root/share/contrib/postgis-$POSTGIS_SERIES/." "$pg_dir/share/contrib/postgis-$POSTGIS_SERIES/"

cd "$pg_dir"
tar -cJvf "$TRG_DIR/postgres-windows-x86_64.txz" \
  share \
  lib/*.dll \
  bin/*.dll \
  bin/initdb.exe \
  bin/pg_ctl.exe \
  bin/postgres.exe \
  bin/psql.exe
