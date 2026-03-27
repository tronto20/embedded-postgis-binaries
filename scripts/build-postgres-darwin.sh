#!/bin/bash
set -euxo pipefail

ARCH_NAME=arm64v8
POSTGIS_VERSION=

while getopts "v:g:a:" opt; do
    case $opt in
    v) PG_VERSION=$OPTARG ;;
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
if [ "$ARCH_NAME" != "arm64v8" ] ; then
  echo "Darwin PostGIS builds currently support only arm64v8 architecture!" && exit 1;
fi
if [ "$(uname -s)" != "Darwin" ] ; then
  echo "Darwin PostGIS builds can only run on macOS!" && exit 1;
fi
if [ "$(uname -m)" != "arm64" ] ; then
  echo "Darwin PostGIS builds can only run on Apple Silicon hosts!" && exit 1;
fi

PROJ_VERSION=6.0.0
PROJ_DATUMGRID_VERSION=1.8
GEOS_VERSION=3.7.2

IFS=. read -r POSTGIS_MAJOR POSTGIS_MINOR _ <<EOF
$POSTGIS_VERSION
EOF
POSTGIS_MAJOR=${POSTGIS_MAJOR:-0}
POSTGIS_MINOR=${POSTGIS_MINOR:-0}

if [ "$POSTGIS_MAJOR" -gt 3 ] || { [ "$POSTGIS_MAJOR" -eq 3 ] && [ "$POSTGIS_MINOR" -ge 4 ]; }; then
    PROJ_VERSION=6.1.1
fi
if [ "$POSTGIS_MAJOR" -gt 3 ] || { [ "$POSTGIS_MAJOR" -eq 3 ] && [ "$POSTGIS_MINOR" -ge 6 ]; }; then
    GEOS_VERSION=3.8.1
fi

CPU_COUNT=$(sysctl -n hw.ncpu)
WRK_DIR=$PWD/work
SRC_DIR=$WRK_DIR/src
TRG_DIR=$PWD/bundle
PREFIX=$WRK_DIR/pg-build

rm -rf "$WRK_DIR"
mkdir -p "$SRC_DIR" "$TRG_DIR" "$PREFIX"

ensure_formula_installed() {
    local formula=$1
    if ! brew list "$formula" >/dev/null 2>&1; then
        brew install "$formula"
    fi
}

resolve_brew_prefix() {
    local formula
    for formula in "$@"; do
        if brew --prefix "$formula" >/dev/null 2>&1; then
            brew --prefix "$formula"
            return 0
        fi
    done
    return 1
}

ensure_formula_installed bison
ensure_formula_installed flex
ensure_formula_installed pkgconf
ensure_formula_installed openssl@3
ensure_formula_installed libxml2
ensure_formula_installed libxslt
ensure_formula_installed icu4c
ensure_formula_installed geos
ensure_formula_installed json-c
ensure_formula_installed e2fsprogs

BISON_PREFIX=$(resolve_brew_prefix bison)
FLEX_PREFIX=$(resolve_brew_prefix flex)
PKGCONF_PREFIX=$(resolve_brew_prefix pkgconf)
OPENSSL_PREFIX=$(resolve_brew_prefix openssl@3)
LIBXML2_PREFIX=$(resolve_brew_prefix libxml2)
LIBXSLT_PREFIX=$(resolve_brew_prefix libxslt)
ICU_PREFIX=$(resolve_brew_prefix icu4c icu4c@78 icu4c@77)
GEOS_PREFIX=$(resolve_brew_prefix geos)
JSONC_PREFIX=$(resolve_brew_prefix json-c)
E2FS_PREFIX=$(resolve_brew_prefix e2fsprogs)

export PATH="$PREFIX/bin:$BISON_PREFIX/bin:$FLEX_PREFIX/bin:$PKGCONF_PREFIX/bin:$LIBXML2_PREFIX/bin:$LIBXSLT_PREFIX/bin:$GEOS_PREFIX/bin:$PATH"
export SDKROOT=$(xcrun --sdk macosx --show-sdk-path)
export MACOSX_DEPLOYMENT_TARGET=${MACOSX_DEPLOYMENT_TARGET:-13.0}
export CPPFLAGS="-I$OPENSSL_PREFIX/include -I$LIBXML2_PREFIX/include/libxml2 -I$LIBXSLT_PREFIX/include -I$ICU_PREFIX/include -I$JSONC_PREFIX/include -I$E2FS_PREFIX/include"
export LDFLAGS="-L$OPENSSL_PREFIX/lib -L$LIBXML2_PREFIX/lib -L$LIBXSLT_PREFIX/lib -L$ICU_PREFIX/lib -L$JSONC_PREFIX/lib -L$E2FS_PREFIX/lib"
export PKG_CONFIG_PATH="$OPENSSL_PREFIX/lib/pkgconfig:$LIBXML2_PREFIX/lib/pkgconfig:$LIBXSLT_PREFIX/lib/pkgconfig:$ICU_PREFIX/lib/pkgconfig:$JSONC_PREFIX/lib/pkgconfig:$E2FS_PREFIX/lib/pkgconfig"

curl -sSL "https://ftp.postgresql.org/pub/source/v$PG_VERSION/postgresql-$PG_VERSION.tar.bz2" | tar -xjf - -C "$SRC_DIR"
cd "$SRC_DIR/postgresql-$PG_VERSION"
./configure \
    CFLAGS="-Os" \
    --prefix="$PREFIX" \
    --enable-integer-datetimes \
    --enable-thread-safety \
    --with-uuid=e2fs \
    --with-icu \
    --with-libxml \
    --with-libxslt \
    --with-openssl \
    --without-readline
make -j"$CPU_COUNT" world-bin
make install-world-bin
make -C contrib install

mkdir -p "$SRC_DIR/proj"
curl -sSL "https://download.osgeo.org/proj/proj-$PROJ_VERSION.tar.gz" | tar -xzf - -C "$SRC_DIR/proj" --strip-components 1
cd "$SRC_DIR/proj"
curl -sSL "https://download.osgeo.org/proj/proj-datumgrid-$PROJ_DATUMGRID_VERSION.zip" -o proj-datumgrid.zip
unzip -o proj-datumgrid.zip -d data
./configure --disable-static --prefix="$PREFIX"
make -j"$CPU_COUNT"
make install

mkdir -p "$SRC_DIR/postgis"
curl -sSL "https://postgis.net/stuff/postgis-$POSTGIS_VERSION.tar.gz" | tar -xzf - -C "$SRC_DIR/postgis" --strip-components 1
cd "$SRC_DIR/postgis"
mkdir -p config
ln -sf ../build-aux/install-sh config/install-sh
# Raster is intentionally disabled to keep the macOS bundle self-contained and small.
# PostGIS still has a recursive-make race in the raster/topology-generated SQL
# targets when built with multiple jobs, so build it serially here.
./configure \
    --prefix="$PREFIX" \
    --with-pgconfig="$PREFIX/bin/pg_config" \
    --with-geosconfig="$GEOS_PREFIX/bin/geos-config" \
    --with-projdir="$PREFIX" \
    --with-jsondir="$JSONC_PREFIX" \
    --without-protobuf \
    --without-raster \
    --without-topology \
    --without-address-standardizer \
    --without-sfcgal \
    --without-tiger \
    --with-gettext=no
make -j1
make install

find "$PREFIX/lib" -maxdepth 1 -type f \( -name "*.a" -o -name "*.la" \) -delete
rm -rf "$PREFIX/include" "$PREFIX/lib/pkgconfig" "$PREFIX/lib/cmake" "$PREFIX/share/doc" "$PREFIX/share/info" "$PREFIX/share/man"

list_bundle_targets() {
    find "$PREFIX/bin" -type f \( -name initdb -o -name pg_ctl -o -name postgres \) -print
    find "$PREFIX/lib" -maxdepth 1 -type f -name "*.dylib" -print
    if [ -d "$PREFIX/lib/postgresql" ]; then
        find "$PREFIX/lib/postgresql" -maxdepth 1 -type f \( -name "*.dylib" -o -name "*.so" \) -print
    fi
}

copy_runtime_dependencies() {
    local changed=1
    local dep
    local file
    local target

    while [ "$changed" -eq 1 ]; do
        changed=0
        while IFS= read -r file; do
            [ -n "$file" ] || continue
            while IFS= read -r dep; do
                [ -n "$dep" ] || continue
                case "$dep" in
                    /System/*|/usr/lib/*|@*) continue ;;
                esac
                target="$PREFIX/lib/$(basename "$dep")"
                if [ ! -e "$target" ]; then
                    cp -p "$dep" "$target"
                    chmod u+w "$target" || true
                    changed=1
                fi
            done <<EOF_DEPS
$(otool -L "$file" | tail -n +2 | awk '{print $1}')
EOF_DEPS
        done <<EOF_FILES
$(list_bundle_targets)
EOF_FILES
    done
}

rewrite_dependencies() {
    local file=$1
    local relative_prefix=$2
    local dep
    local replacement

    chmod u+w "$file" || true
    while IFS= read -r dep; do
        [ -n "$dep" ] || continue
        replacement="$PREFIX/lib/$(basename "$dep")"
        if [ -e "$replacement" ]; then
            install_name_tool -change "$dep" "$relative_prefix/$(basename "$dep")" "$file"
        fi
    done <<EOF_DEPS
$(otool -L "$file" | tail -n +2 | awk '{print $1}')
EOF_DEPS
}

copy_formula_dylibs() {
    local lib_dir=$1
    local file
    local target

    [ -d "$lib_dir" ] || return
    find -L "$lib_dir" -maxdepth 1 \( -type f -o -type l \) -name "*.dylib" | while IFS= read -r file; do
        target="$PREFIX/lib/$(basename "$file")"
        if [ ! -e "$target" ]; then
            cp -pL "$file" "$target"
        fi
        chmod u+w "$target" || true
    done
}

sign_bundle_target() {
    local file=$1
    codesign --force --sign - "$file"
}

break_hardlinks() {
    local base_dir=$1
    local file
    local tmp_file

    [ -d "$base_dir" ] || return
    find "$base_dir" -type f -links +1 | while IFS= read -r file; do
        tmp_file="${file}.tmp.$$"
        cp -p "$file" "$tmp_file"
        mv -f "$tmp_file" "$file"
    done
}

copy_formula_dylibs "$ICU_PREFIX/lib"
copy_formula_dylibs "$GEOS_PREFIX/lib"
copy_formula_dylibs "$OPENSSL_PREFIX/lib"
copy_formula_dylibs "$LIBXML2_PREFIX/lib"
copy_formula_dylibs "$LIBXSLT_PREFIX/lib"
copy_formula_dylibs "$JSONC_PREFIX/lib"

copy_runtime_dependencies

find "$PREFIX/lib" -maxdepth 1 -type f -name "*.dylib" | while IFS= read -r file; do
    chmod u+w "$file" || true
    install_name_tool -id "@loader_path/$(basename "$file")" "$file"
    rewrite_dependencies "$file" "@loader_path"
    sign_bundle_target "$file"
done

if [ -d "$PREFIX/lib/postgresql" ]; then
    find "$PREFIX/lib/postgresql" -maxdepth 1 -type f \( -name "*.dylib" -o -name "*.so" \) | while IFS= read -r file; do
        rewrite_dependencies "$file" "@loader_path/.."
        sign_bundle_target "$file"
    done
fi

find "$PREFIX/bin" -type f \( -name initdb -o -name pg_ctl -o -name postgres \) | while IFS= read -r file; do
    rewrite_dependencies "$file" "@executable_path/../lib"
    sign_bundle_target "$file"
done

# embedded-postgres extracts txz archives in Java and does not preserve tar hard links correctly.
# Flattening them here keeps timezone/resource files usable for downstream consumers.
break_hardlinks "$PREFIX/share"

cd "$PREFIX"
COPYFILE_DISABLE=1 COPY_EXTENDED_ATTRIBUTES_DISABLE=1 tar --exclude '._*' -cJvf "$TRG_DIR/postgres-darwin-arm_64.txz" \
    share \
    lib \
    bin/initdb \
    bin/pg_ctl \
    bin/postgres
