#!/bin/bash
set -euo pipefail

POSTGIS_VERSION=

while getopts "v:g:" opt; do
    case $opt in
    v) PG_VERSION=$OPTARG ;;
    g) POSTGIS_VERSION=$OPTARG ;;
    \?) exit 1 ;;
    esac
done

if [ -z "${PG_VERSION:-}" ] ; then
  echo "Postgres version parameter is required!" && exit 1;
fi
if [ -z "${POSTGIS_VERSION:-}" ] ; then
  echo "PostGIS version parameter is required!" && exit 1;
fi

IFS=. read -r POSTGIS_MAJOR POSTGIS_MINOR _ <<EOF
$POSTGIS_VERSION
EOF

POSTGIS_MAJOR=${POSTGIS_MAJOR:-0}
POSTGIS_MINOR=${POSTGIS_MINOR:-0}
POSTGIS_SERIES="$POSTGIS_MAJOR.$POSTGIS_MINOR"

DIST_DIR=$PWD/dist
PKG_DIR=$PWD/package
TRG_DIR=$PWD/bundle

mkdir -p "$DIST_DIR" "$PKG_DIR" "$TRG_DIR"

download_if_missing() {
  local target=$1
  local url=$2

  if [ ! -e "$target" ] ; then
    echo "Downloading $(basename "$target")"
    curl --fail --silent --show-error --location --globoff --retry 3 --retry-delay 2 --url "$url" --output "$target"
  fi
}

ensure_zstd() {
  if command -v zstd >/dev/null 2>&1 ; then
    return 0
  fi

  if command -v choco >/dev/null 2>&1 ; then
    choco install zstandard -y --no-progress
    export PATH="$PATH:/c/ProgramData/chocolatey/bin"
    hash -r || true
  fi

  if command -v zstd >/dev/null 2>&1 ; then
    return 0
  fi

  echo "zstd is required to extract MSYS2 arm64 package metadata and archives!" && exit 1;
}

resolve_python() {
  if command -v python3 >/dev/null 2>&1 ; then
    printf '%s\n' python3
    return 0
  fi

  if command -v python >/dev/null 2>&1 ; then
    printf '%s\n' python
    return 0
  fi

  if command -v py >/dev/null 2>&1 ; then
    printf '%s\n' py
    return 0
  fi

  echo "Python is required to patch the Windows arm64 PostGIS SQL files!" && exit 1;
}

extract_archive() {
  local archive_path=$1
  local target_dir=$2
  local archive_name
  local extractor=
  local tmp_tar=
  archive_name=$(basename "$archive_path")

  # MSYS2 package indexes and packages are zstd-compressed tar archives.
  # GitHub Windows ARM runners are inconsistent about what `tar -xf` can
  # auto-detect here, so decompress to a plain tar first.
  if command -v zstd >/dev/null 2>&1 && command -v tar >/dev/null 2>&1 ; then
    case "$archive_name" in
      *.zst|*.db)
        if command -v bsdtar >/dev/null 2>&1 ; then
          extractor=bsdtar
        else
          extractor=tar
        fi

        tmp_tar=$(mktemp "$target_dir/.extract.XXXXXX.tar")
        zstd -d -f -c -- "$archive_path" > "$tmp_tar"
        "$extractor" -xf "$tmp_tar" -C "$target_dir"
        rm -f "$tmp_tar"
        return 0
        ;;
    esac
  fi

  if command -v bsdtar >/dev/null 2>&1 ; then
    bsdtar -xf "$archive_path" -C "$target_dir"
    return 0
  fi

  if command -v tar >/dev/null 2>&1 ; then
    if tar -xf "$archive_path" -C "$target_dir" ; then
      return 0
    fi
  fi

  if command -v zstd >/dev/null 2>&1 && command -v tar >/dev/null 2>&1 ; then
    zstd -dc -- "$archive_path" | tar -xf - -C "$target_dir"
    return 0
  fi

  echo "Unable to extract $(basename "$archive_path"): neither bsdtar nor tar/zstd fallback is available." && exit 1;
}

patch_arm64_postgis_sql() {
  local root_dir=$1
  local sql_file
  local python_bin
  local -a python_cmd

  python_bin=$(resolve_python)
  if [ "$python_bin" = "py" ] ; then
    python_cmd=(py -3)
  else
    python_cmd=("$python_bin")
  fi

  for sql_file in \
    "$root_dir/share/postgresql/extension/postgis--$POSTGIS_VERSION.sql" \
    "$root_dir/share/postgresql/contrib/postgis-$POSTGIS_SERIES/postgis.sql"; do
    [ -f "$sql_file" ] || continue

    "${python_cmd[@]}" - "$sql_file" <<'PY'
import re
import sys
from pathlib import Path

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8", errors="ignore")
schema_prefix = "@extschema@." if "/extension/" in str(path) else ""

pattern = re.compile(
    r"CREATE OR REPLACE FUNCTION ST_SymDifference\(geom1 geometry, geom2 geometry, gridSize float8 DEFAULT -1(?:\.0)?\)\n"
    r"\tRETURNS geometry\n"
    r"\tAS '\$libdir/postgis-3','ST_SymDifference'\n"
    r"\tLANGUAGE 'c' IMMUTABLE STRICT PARALLEL SAFE\n"
    r"\tCOST 5000;"
)

replacement = (
    "CREATE OR REPLACE FUNCTION ST_SymDifference(geom1 geometry, geom2 geometry, gridSize float8 DEFAULT -1.0)\n"
    "\tRETURNS geometry\n"
    f"\tAS 'SELECT {schema_prefix}ST_Union({schema_prefix}ST_Difference(geom1, geom2, gridSize), "
    f"{schema_prefix}ST_Difference(geom2, geom1, gridSize), gridSize);'\n"
    "\tLANGUAGE 'sql' IMMUTABLE STRICT PARALLEL SAFE\n"
    "\tCOST 5000;"
)

new_text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    sys.stderr.write(f"Failed to patch ST_SymDifference wrapper in {path}\n")
    sys.exit(1)

path.write_text(new_text, encoding="utf-8")
PY
  done
}

PACKAGE_INDEX_FILE=

desc_value() {
  local desc_file=$1
  local section=$2

  awk -v section="$section" '
    $0 == section { getline; print; exit }
  ' "$desc_file"
}

desc_values() {
  local desc_file=$1
  local section=$2

  awk -v section="$section" '
    $0 == section { in_section = 1; next }
    in_section && /^%/ { exit }
    in_section && NF { print }
  ' "$desc_file"
}

build_package_index() {
  local db_dir=$1
  local index_file=$2

  : > "$index_file"

  while IFS= read -r desc_file; do
    local provided_name

    printf '%s\t%s\n' "$(desc_value "$desc_file" '%NAME%')" "$desc_file" >> "$index_file"
    while IFS= read -r provided_name; do
      provided_name=$(sanitize_dep_name "$provided_name")
      [ -n "$provided_name" ] || continue
      printf '%s\t%s\n' "$provided_name" "$desc_file" >> "$index_file"
    done < <(desc_values "$desc_file" '%PROVIDES%')
  done < <(find "$db_dir" -mindepth 2 -maxdepth 2 -name desc -print)
}

find_desc_by_name() {
  local _db_dir=$1
  local package_name=$2
  local desc_file

  desc_file=$(awk -F '\t' -v package_name="$package_name" '
    $1 == package_name { print $2; exit }
  ' "$PACKAGE_INDEX_FILE")

  [ -n "$desc_file" ] || return 1
  printf '%s\n' "$desc_file"
}

sanitize_dep_name() {
  local dep_name=${1%% *}
  dep_name=${dep_name%%:*}
  dep_name=${dep_name%%[<>=]*}
  printf '%s\n' "$dep_name"
}

expand_virtual_dep() {
  local dep_name=$1

  case "$dep_name" in
    mingw-w64-clang-aarch64-cc-libs)
      printf '%s\n' \
        mingw-w64-clang-aarch64-libc++ \
        mingw-w64-clang-aarch64-libunwind \
        mingw-w64-clang-aarch64-libwinpthread \
        mingw-w64-clang-aarch64-llvm-openmp
      ;;
    mingw-w64-clang-aarch64-gcc-libs)
      printf '%s\n' \
        mingw-w64-clang-aarch64-libc++ \
        mingw-w64-clang-aarch64-libunwind \
        mingw-w64-clang-aarch64-libwinpthread
      ;;
    mingw-w64-clang-aarch64-omp)
      printf '%s\n' mingw-w64-clang-aarch64-llvm-openmp
      ;;
    *)
      printf '%s\n' "$dep_name"
      ;;
  esac
}

list_contains() {
  local needle=$1
  shift

  local item
  for item in "$@"; do
    if [ "$item" = "$needle" ] ; then
      return 0
    fi
  done

  return 1
}

repo_url="https://mirror.nju.edu.cn/msys2/mingw/clangarm64"
db_file="$DIST_DIR/clangarm64.db"
db_dir="$PKG_DIR/repo-db"
stage_dir="$PKG_DIR/msys2"
index_file="$PKG_DIR/repo-db-index.tsv"

download_if_missing "$db_file" "$repo_url/clangarm64.db"
ensure_zstd

rm -rf "$PKG_DIR"
mkdir -p "$db_dir" "$stage_dir" "$TRG_DIR"

echo "Extracting MSYS2 package database"
extract_archive "$db_file" "$db_dir"
PACKAGE_INDEX_FILE="$index_file"
build_package_index "$db_dir" "$PACKAGE_INDEX_FILE"

echo "Resolving PostgreSQL and PostGIS arm64 packages"
pg_desc=$(find_desc_by_name "$db_dir" "mingw-w64-clang-aarch64-postgresql") || {
  echo "Unable to resolve the current PostgreSQL arm64 package from MSYS2!" && exit 1;
}
postgis_desc=$(find_desc_by_name "$db_dir" "mingw-w64-clang-aarch64-postgis") || {
  echo "Unable to resolve the current PostGIS arm64 package from MSYS2!" && exit 1;
}

pg_version=$(desc_value "$pg_desc" '%VERSION%')
postgis_version=$(desc_value "$postgis_desc" '%VERSION%')

if [[ "$pg_version" != "$PG_VERSION"-* ]] ; then
  echo "Windows arm64 PostgreSQL $PG_VERSION is not currently published in the MSYS2 clangarm64 repository (found $pg_version instead)." && exit 1;
fi
if [[ "$postgis_version" != "$POSTGIS_VERSION"-* ]] ; then
  echo "Windows arm64 PostGIS $POSTGIS_VERSION is not currently published in the MSYS2 clangarm64 repository (found $postgis_version instead)." && exit 1;
fi

queue=(
  "mingw-w64-clang-aarch64-postgresql"
  "mingw-w64-clang-aarch64-postgis"
)
resolved=()

while [ ${#queue[@]} -gt 0 ]; do
  package_name=${queue[0]}
  queue=("${queue[@]:1}")

  if [ ${#resolved[@]} -gt 0 ] && list_contains "$package_name" "${resolved[@]}" ; then
    continue
  fi

  desc_file=$(find_desc_by_name "$db_dir" "$package_name") || {
    echo "Unable to resolve dependency '$package_name' from MSYS2 clangarm64." && exit 1;
  }

  resolved+=("$package_name")

  while IFS= read -r dependency; do
    dependency=$(sanitize_dep_name "$dependency")
    while IFS= read -r expanded_dependency; do
      case "$expanded_dependency" in
        mingw-w64-clang-aarch64-gdal|mingw-w64-clang-aarch64-sfcgal)
          continue
          ;;
        mingw-w64-clang-aarch64-*)
          if ! { [ ${#resolved[@]} -gt 0 ] && list_contains "$expanded_dependency" "${resolved[@]}"; } \
              && ! { [ ${#queue[@]} -gt 0 ] && list_contains "$expanded_dependency" "${queue[@]}"; }; then
            queue+=("$expanded_dependency")
          fi
          ;;
      esac
    done < <(expand_virtual_dep "$dependency")
  done < <(desc_values "$desc_file" '%DEPENDS%')
done

for package_name in "${resolved[@]}"; do
  desc_file=$(find_desc_by_name "$db_dir" "$package_name")
  filename=$(desc_value "$desc_file" '%FILENAME%')

  download_if_missing "$DIST_DIR/$filename" "$repo_url/$filename"
  echo "Extracting $filename"
  extract_archive "$DIST_DIR/$filename" "$stage_dir"
done

root_dir="$stage_dir/clangarm64"
if [ ! -d "$root_dir" ] ; then
  echo "MSYS2 arm64 packages were not extracted correctly!" && exit 1;
fi

echo "Patching Windows arm64 PostGIS SQL files"
patch_arm64_postgis_sql "$root_dir"

rm -f \
  "$root_dir/lib/postgresql/address_standardizer-3.dll" \
  "$root_dir/lib/postgresql/postgis_raster-3.dll" \
  "$root_dir/lib/postgresql/postgis_sfcgal-3.dll" \
  "$root_dir/lib/postgresql/postgis_topology-3.dll"

find "$root_dir/share/postgresql/extension" -maxdepth 1 -type f \
  \( -name 'address_standardizer*' -o -name 'postgis_raster*' -o -name 'postgis_sfcgal*' -o -name 'postgis_topology*' \) \
  -delete

if [ -d "$root_dir/share/postgresql/contrib/postgis-$POSTGIS_SERIES" ] ; then
  rm -f \
    "$root_dir/share/postgresql/contrib/postgis-$POSTGIS_SERIES"/rtpostgis* \
    "$root_dir/share/postgresql/contrib/postgis-$POSTGIS_SERIES"/sfcgal* \
    "$root_dir/share/postgresql/contrib/postgis-$POSTGIS_SERIES"/topology* \
    "$root_dir/share/postgresql/contrib/postgis-$POSTGIS_SERIES"/uninstall_rtpostgis.sql \
    "$root_dir/share/postgresql/contrib/postgis-$POSTGIS_SERIES"/uninstall_sfcgal.sql \
    "$root_dir/share/postgresql/contrib/postgis-$POSTGIS_SERIES"/uninstall_topology.sql
fi

cd "$root_dir"

bundle_paths=(
  share/postgresql
  lib/postgresql
  bin/*.dll
  bin/initdb.exe
  bin/pg_ctl.exe
  bin/postgres.exe
  bin/psql.exe
)

if [ -d share/proj ] ; then
  bundle_paths+=(share/proj)
fi

tar -cJvf "$TRG_DIR/postgres-windows-arm_64.txz" "${bundle_paths[@]}"
