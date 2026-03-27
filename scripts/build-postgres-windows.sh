#!/bin/bash
set -euo pipefail

ARCH_NAME=amd64
POSTGIS_VERSION=
PG_BIN_VERSION=

while getopts "v:b:g:a:" opt; do
    case $opt in
    v) PG_VERSION=$OPTARG ;;
    b) PG_BIN_VERSION=$OPTARG ;;
    g) POSTGIS_VERSION=$OPTARG ;;
    a) ARCH_NAME=$OPTARG ;;
    \?) exit 1 ;;
    esac
done

if [ -z "${PG_VERSION:-}" ] ; then
  echo "Postgres version parameter is required!" && exit 1;
fi
if [ -z "${POSTGIS_VERSION:-}" ] ; then
  echo "PostGIS version parameter is required!" && exit 1;
fi
if [ "$ARCH_NAME" != "amd64" ] && [ "$ARCH_NAME" != "arm64v8" ] ; then
  echo "Windows PostGIS builds currently support only amd64 and arm64v8 architectures!" && exit 1;
fi
if [ "$ARCH_NAME" = "amd64" ] && [ -z "${PG_BIN_VERSION:-}" ] ; then
  echo "Postgres binary version parameter is required for amd64 Windows builds!" && exit 1;
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
    # MSYS2 clangarm64 package databases and packages are zstd-compressed.
    # Windows ARM runners do not ship zstd by default, so install the CLI once.
    choco install zstandard -y --no-progress
    export PATH="$PATH:/c/ProgramData/chocolatey/bin"
    hash -r || true
  fi

  if command -v zstd >/dev/null 2>&1 ; then
    return 0
  fi

  echo "zstd is required to extract MSYS2 arm64 package metadata and archives!" && exit 1;
}

extract_archive() {
  local archive_path=$1
  local target_dir=$2

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

build_amd64_bundle() {
  local edb_zip="$DIST_DIR/postgresql-$PG_BIN_VERSION-windows-x64-binaries.zip"
  local postgis_zip="$DIST_DIR/postgis-bundle-pg${PG_MAJOR}-${POSTGIS_VERSION}x64.zip"

  download_if_missing "$edb_zip" "https://get.enterprisedb.com/postgresql/postgresql-$PG_BIN_VERSION-windows-x64-binaries.zip"
  download_if_missing "$postgis_zip" "https://download.osgeo.org/postgis/windows/pg${PG_MAJOR}/postgis-bundle-pg${PG_MAJOR}-${POSTGIS_VERSION}x64.zip"

  rm -rf "$PKG_DIR"
  mkdir -p "$PKG_DIR/edb" "$PKG_DIR/postgis"

  unzip -q "$edb_zip" -d "$PKG_DIR/edb"
  unzip -q "$postgis_zip" -d "$PKG_DIR/postgis"

  local pg_dir="$PKG_DIR/edb/pgsql"
  local postgis_root
  postgis_root=$(find "$PKG_DIR/postgis" -mindepth 1 -maxdepth 1 -type d | head -n 1)

  if [ ! -d "$pg_dir" ] ; then
    echo "EDB PostgreSQL bundle was not extracted correctly!" && exit 1;
  fi
  if [ -z "${postgis_root:-}" ] || [ ! -d "$postgis_root" ] ; then
    echo "PostGIS Windows bundle was not extracted correctly!" && exit 1;
  fi

  mkdir -p "$pg_dir/share/extension" "$pg_dir/share/contrib/postgis-$POSTGIS_SERIES" "$pg_dir/lib"

  local postgis_dll
  for postgis_dll in "$postgis_root"/bin/*.dll; do
    local dll_name
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

build_arm64_bundle() {
  local repo_url="https://mirror.nju.edu.cn/msys2/mingw/clangarm64"
  local db_file="$DIST_DIR/clangarm64.db"
  local db_dir="$PKG_DIR/repo-db"
  local stage_dir="$PKG_DIR/msys2"
  local root_dir
  local index_file="$PKG_DIR/repo-db-index.tsv"
  local pg_desc
  local postgis_desc
  local pg_version
  local postgis_version

  download_if_missing "$db_file" "$repo_url/clangarm64.db"
  ensure_zstd

  rm -rf "$PKG_DIR"
  mkdir -p "$db_dir" "$stage_dir" "$TRG_DIR"

  extract_archive "$db_file" "$db_dir"
  PACKAGE_INDEX_FILE="$index_file"
  build_package_index "$db_dir" "$PACKAGE_INDEX_FILE"

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

  local queue=(
    "mingw-w64-clang-aarch64-postgresql"
    "mingw-w64-clang-aarch64-postgis"
  )
  local resolved=()

  while [ ${#queue[@]} -gt 0 ]; do
    local package_name=${queue[0]}
    queue=("${queue[@]:1}")

    if [ ${#resolved[@]} -gt 0 ] && list_contains "$package_name" "${resolved[@]}" ; then
      continue
    fi

    local desc_file
    desc_file=$(find_desc_by_name "$db_dir" "$package_name") || {
      echo "Unable to resolve dependency '$package_name' from MSYS2 clangarm64." && exit 1;
    }

    resolved+=("$package_name")

    local dependency
    while IFS= read -r dependency; do
      dependency=$(sanitize_dep_name "$dependency")
      local expanded_dependency
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

  local package_name
  for package_name in "${resolved[@]}"; do
    local desc_file
    local filename

    desc_file=$(find_desc_by_name "$db_dir" "$package_name")
    filename=$(desc_value "$desc_file" '%FILENAME%')

    download_if_missing "$DIST_DIR/$filename" "$repo_url/$filename"
    extract_archive "$DIST_DIR/$filename" "$stage_dir"
  done

  root_dir="$stage_dir/clangarm64"
  if [ ! -d "$root_dir" ] ; then
    echo "MSYS2 arm64 packages were not extracted correctly!" && exit 1;
  fi

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

  local bundle_paths=(
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
}

if [ "$ARCH_NAME" = "amd64" ] ; then
  build_amd64_bundle
else
  build_arm64_bundle
fi
