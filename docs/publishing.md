# Publishing and Release

This repository publishes PostGIS-enabled PostgreSQL binary artifacts under the `dev.tronto.postgis` group.

Current release lines:

* `18.3-3.6.2`
* `17.9-3.5.3`

The published Maven Central coordinates are:

* `dev.tronto.postgis:embedded-postgres-binaries-bom`
* `dev.tronto.postgis:embedded-postgres-binaries-linux-amd64`
* `dev.tronto.postgis:embedded-postgres-binaries-linux-arm64v8`
* `dev.tronto.postgis:embedded-postgres-binaries-linux-amd64-alpine`
* `dev.tronto.postgis:embedded-postgres-binaries-linux-arm64v8-alpine`
* `dev.tronto.postgis:embedded-postgres-binaries-windows-amd64`
* `dev.tronto.postgis:embedded-postgres-binaries-darwin-arm64v8`

The compatibility alias `dev.tronto.postgis:embedded-postgres-binaries-darwin-amd64` is also published for Apple Silicon setups that still expect the legacy module name.

## Maven Central Publishing

Maven Central publishing uses:

* Central Portal user token username/password
* PGP signing for every published release and snapshot artifact
* `publishTarget=mavenCentral`

Recommended publisher environment variables:

* `ORG_GRADLE_PROJECT_mavenCentralUsername=<central token username>`
* `ORG_GRADLE_PROJECT_mavenCentralPassword=<central token password>`
* `ORG_GRADLE_PROJECT_signingKeyId=<optional key id>`
* `ORG_GRADLE_PROJECT_signingKey=<ascii-armored private key or base64-encoded armored key>`
* `ORG_GRADLE_PROJECT_signingPassword=<key password>`

Local publish commands:

```bash
./gradlew publishReleaseToMavenCentral \
  -PpublishTarget=mavenCentral \
  -Pversion=18.3-3.6.2 \
  -PpgVersion=18.3 \
  -PpostgisVersion=3.6.2
```

`publishReleaseToMavenCentral` uploads the signed release artifacts only. It does not finalize the deployment.

If you want upload plus finalize in one local command, use:

```bash
./gradlew publishAndReleaseToMavenCentral \
  -PpublishTarget=mavenCentral \
  -Pversion=18.3-3.6.2 \
  -PpgVersion=18.3 \
  -PpostgisVersion=3.6.2
```

Snapshot publishing uses the Central snapshots repository:

```bash
./gradlew publishSnapshotToMavenCentral \
  -PpublishTarget=mavenCentral \
  -Pversion=18.3-3.6.2-SNAPSHOT \
  -PpgVersion=18.3 \
  -PpostgisVersion=3.6.2
```

Snapshot versions must end in `-SNAPSHOT`.

## Workflow Behavior

The default `Checks`, milestone-driven `Release`, manual `Publish Snapshot`, and `Publish GitHub Packages` workflows use the same default platform set:

* `Linux amd64`
* `Linux arm64`
* `Alpine amd64`
* `Alpine arm64`
* `Windows amd64`
* `macOS arm64`

For the Linux and Alpine arm64 targets, these workflows use native GitHub-hosted arm64 Linux runners instead of amd64 runners with QEMU emulation.

Windows arm64 is optional and defaults to `false` in the manual `Checks`, `Release`, and `Publish GitHub Packages` workflows.

The `Release` workflow publishes versions in the `<pg_version>-<postgis_version>` format and runs automatically when a milestone is closed. It can also be started manually with `workflow_dispatch`.

The workflow uploads the signed release artifacts and finalizes the Maven Central deployment automatically.

The `Publish Snapshot` workflow mirrors the same platform build matrix but publishes `<pg_version>-<postgis_version>-SNAPSHOT` artifacts to the Central snapshots repository instead of creating a release deployment.

The `Publish GitHub Packages` workflow publishes the same platform artifacts and BOM to the repository GitHub Packages registry.

## GitHub Packages

This repository also publishes the same coordinates to:

`https://maven.pkg.github.com/tronto20/embedded-postgis-binaries`

GitHub Packages still requires authentication for Maven and Gradle consumers, even when the package is public.

Recommended environment variables for GitHub Packages consumers:

* `GITHUB_USERNAME=<your GitHub username>`
* `GITHUB_TOKEN=<PAT classic with read:packages>`

Inside GitHub Actions for this same repository, `GITHUB_TOKEN` is enough for publishing.

## Workflow Matrix

The default `Checks`, milestone-driven `Release`, manual `Publish Snapshot`, and `Publish GitHub Packages` workflows use the same default platform set:

* `Linux amd64`
* `Linux arm64`
* `Alpine amd64`
* `Alpine arm64`
* `Windows amd64`
* `macOS arm64`

For the Linux and Alpine arm64 targets, these workflows use native GitHub-hosted arm64 Linux runners instead of amd64 runners with QEMU emulation.

Windows arm64 is optional and defaults to `false` in the manual `Checks`, `Release`, and `Publish GitHub Packages` workflows.

The `Release` workflow publishes versions in the `<pg_version>-<postgis_version>` format and runs automatically when a milestone is closed. It can also be started manually with `workflow_dispatch`.

The workflow uploads the signed release artifacts and finalizes the Maven Central deployment automatically.

The `Publish Snapshot` workflow mirrors the same platform build matrix but publishes `<pg_version>-<postgis_version>-SNAPSHOT` artifacts to the Central snapshots repository instead of creating a release deployment.

The `Publish GitHub Packages` workflow publishes the same platform artifacts and BOM to the repository GitHub Packages registry.

## Supported Platforms and Artifacts

The primary published artifact set is:

* `embedded-postgres-binaries-linux-amd64`
* `embedded-postgres-binaries-linux-arm64v8`
* `embedded-postgres-binaries-linux-amd64-alpine`
* `embedded-postgres-binaries-linux-arm64v8-alpine`
* `embedded-postgres-binaries-windows-amd64`
* `embedded-postgres-binaries-darwin-arm64v8`

Additional artifacts:

* `embedded-postgres-binaries-darwin-amd64`
  * Apple Silicon compatibility alias
* `embedded-postgres-binaries-windows-arm64v8`
  * optional and experimental

**Supported platforms:** `Darwin`, `Windows`, `Linux`, `Alpine Linux`

**Documented published architectures:** `amd64`, `arm64v8`

Not all platforms expose the same packaging path. The common cross-platform feature set is PostGIS core plus coordinate transforms, `geography`, MVT, and Geobuf.

## Manual Inputs and Version Resolution

Workflow inputs:

* `pg_version` and `postgis_version` are required
* `pg_version` may be either a branch such as `16`, `17`, or `18`, or an exact patch release such as `18.3`
* `postgis_version` may be either a branch such as `3.4`, `3.5`, or `3.6`, or an exact patch release such as `3.6.2`
* `pg_bin_version` is optional and defaults to `<pg_version>-1` for Windows x64
* `include_windows_arm64` is optional and defaults to `false`
* `windows_arm64_pg_version` is optional and is only needed when you explicitly opt in to the Windows arm64 path

Supported combinations:

* PostgreSQL `16.x`, `17.x`, and `18.x` support PostGIS `3.4.x`, `3.5.x`, and `3.6.x`
* The common cross-platform feature set is PostGIS core plus coordinate transforms, `geography`, MVT, and Geobuf
* `raster` is not included in these published artifacts
* Windows arm64 is currently opt-in only because the upstream MSYS2 prebuilt PostGIS package does not yet provide the same protobuf-backed feature set as the other platforms
* Windows amd64 support also depends on the official OSGeo Windows bundle matrix for each PostgreSQL major

For the manual workflows, branch inputs are resolved automatically before Gradle runs:

* `pg_version=17` resolves to the latest published PostgreSQL 17 patch release
* `postgis_version=3.5` resolves to the latest published PostGIS 3.5 patch release
* workflows that include Windows amd64 resolve `postgis_version` to the latest patch release that also has an official Windows bundle for that PostgreSQL major
* the experimental Windows arm64 workflow resolves `pg_version` against the versions currently published in MSYS2 `clangarm64`
