#!/bin/bash
set -ex

POSTGIS_VERSION=

while getopts "j:z:v:g:" opt; do
    case $opt in
    j) JAR_FILE=$OPTARG ;;
    z) ZIP_FILE=$OPTARG ;;
    v) PG_VERSION=$OPTARG ;;
    g) POSTGIS_VERSION=$OPTARG ;;
    \?) exit 1 ;;
    esac
done

if [ -z "$JAR_FILE" ] ; then
  echo "Jar file parameter is required!" && exit 1;
fi
if [ -z "$ZIP_FILE" ] ; then
  echo "Zip file parameter is required!" && exit 1;
fi
if [ -z "$PG_VERSION" ] ; then
  echo "Postgres version parameter is required!" && exit 1;
fi

LIB_DIR=$PWD
TRG_DIR=$(mktemp -d)

mkdir -p $TRG_DIR/pg-dist
unzip -q -d $TRG_DIR/pg-dist $LIB_DIR/$JAR_FILE

mkdir -p $TRG_DIR/pg-test/data
tar -xJf $TRG_DIR/pg-dist/$ZIP_FILE -C $TRG_DIR/pg-test

INITDB=$TRG_DIR/pg-test/bin/initdb.exe
PG_CTL=$TRG_DIR/pg-test/bin/pg_ctl.exe
PSQL=$TRG_DIR/pg-test/bin/psql.exe
UUID_OSSP_CONTROL=$TRG_DIR/pg-test/share/extension/uuid-ossp.control

if [ ! -f "$UUID_OSSP_CONTROL" ] ; then
  UUID_OSSP_CONTROL=$TRG_DIR/pg-test/share/postgresql/extension/uuid-ossp.control
fi

if [ -f "$TRG_DIR/pg-test/share/proj/proj.db" ] ; then
  export PROJ_LIB=$TRG_DIR/pg-test/share/proj
else
  PROJ_DATA_DIR=$(find "$TRG_DIR/pg-test/share/contrib" -type f -path '*/proj/proj.db' -print -quit 2>/dev/null || true)
  if [ -n "$PROJ_DATA_DIR" ] ; then
    export PROJ_LIB=$(dirname "$PROJ_DATA_DIR")
  fi
fi

if [ ! -x "$PSQL" ] ; then
  PSQL=psql
fi

$INITDB -A trust -U postgres -D $TRG_DIR/pg-test/data -E UTF-8
$PG_CTL -w -D $TRG_DIR/pg-test/data -o '-p 65432 -F -c timezone=UTC -c synchronous_commit=off -c max_connections=300' start

# Shutdown DB server and do cleanup on exit
function cleanup() {
  local errcode=$?
  $PG_CTL -w -D $TRG_DIR/pg-test/data stop
  rm -rf $TRG_DIR
  return $errcode
}

trap cleanup EXIT

psql_scalar() {
  "$PSQL" -qAtX -h localhost -p 65432 -U postgres -d postgres -c "$1" | tr -d '\r'
}

test "$(psql_scalar "SHOW SERVER_VERSION")" = "$PG_VERSION"
test "$(psql_scalar "CREATE EXTENSION pgcrypto; SELECT digest('test', 'sha256');")" = "\x9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
if [ -f "$UUID_OSSP_CONTROL" ] ; then
  echo "$(psql_scalar 'CREATE EXTENSION "uuid-ossp"; SELECT uuid_generate_v4();')" | grep -E '^[^-]{8}-[^-]{4}-[^-]{4}-[^-]{4}-[^-]{12}$'
fi

if echo "$PG_VERSION" | grep -qvE '^(10|9)\.' ; then
  count=$(psql_scalar 'SET jit_above_cost = 10; SELECT SUM(relpages) FROM pg_class;')
  test "$count" -gt 0
fi

if [ -n "$POSTGIS_VERSION" ] ; then
  test "$(psql_scalar "CREATE EXTENSION postgis; SELECT PostGIS_Lib_Version();")" = "$POSTGIS_VERSION"
  test "$(psql_scalar "SELECT ST_AsText(ST_Transform(\$\$SRID=4326;POINT(0 0)\$\$::geometry, 3857));")" = "POINT(0 0)"
fi
