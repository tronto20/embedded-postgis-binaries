# <img src="zonky.jpg" height="100"> Embedded Postgres Binaries

## Introduction

This project provides lightweight bundles of PostgreSQL binaries with reduced size that are intended for testing purposes.
It is a supporting project for the primary [io.zonky.test:embedded-database-spring-test](https://github.com/zonkyio/embedded-database-spring-test) and [io.zonky.test:embedded-postgres](https://github.com/zonkyio/embedded-postgres) projects.
However, with a little effort, the embedded binaries can also be integrated with other projects.

## Provided features

* Lightweight bundles of PostgreSQL binaries with reduced size (~10MB)
* Embedded PostgreSQL 16+ binaries even for Linux platform
* Configurable version of PostgreSQL binaries
* PostGIS core is enabled by default for PostgreSQL 16+ builds

## Projects using the embedded binaries

* [zonkyio/embedded-database-spring-test](https://github.com/zonkyio/embedded-database-spring-test) (Java - Spring)
* [zonkyio/embedded-postgres](https://github.com/zonkyio/embedded-postgres) (Java)
* [hgschmie/pg-embedded](https://github.com/hgschmie/pg-embedded) (Java)
* [fergusstrange/embedded-postgres](https://github.com/fergusstrange/embedded-postgres) (Go)
* [theseus-rs/postgresql-embedded](https://github.com/theseus-rs/postgresql-embedded) (Rust)
* [faokunega/pg-embed](https://github.com/faokunega/pg-embed) (Rust)
* [leinelissen/embedded-postgres](https://github.com/leinelissen/embedded-postgres) (NodeJS)

## Consuming published artifacts

This fork publishes binary artifacts under:

* group: `dev.tronto.postgis`
* version format: `<pg_version>-<postgis_version>`

For example, PostgreSQL `18.3` with PostGIS `3.6.2` is published as `18.3-3.6.2`.

The intended compatibility story is:

* keep `io.zonky.test:embedded-postgres`
* replace only the binary artifacts with `dev.tronto.postgis:*`

The `embedded-postgres-binaries-bom` artifact manages versions only. Importing the BOM is not enough by itself.
You still need one or more concrete platform artifacts such as `embedded-postgres-binaries-linux-amd64`
or `embedded-postgres-binaries-darwin-arm64v8`.

Published artifacts also support release branches during local builds and workflow dispatch runs:

* `pgVersion=17` resolves to the latest supported PostgreSQL 17 patch release
* `postgisVersion=3.4` resolves to the latest supported PostGIS 3.4 patch release
* Windows amd64 resolves PostGIS branches against the official OSGeo Windows bundle matrix for that PostgreSQL major
* Windows arm64 resolves branches against the versions currently published in MSYS2 `clangarm64`

### Maven Central

The Maven Central target coordinates are:

* `dev.tronto.postgis:embedded-postgres-binaries-bom`
* `dev.tronto.postgis:embedded-postgres-binaries-linux-amd64`
* `dev.tronto.postgis:embedded-postgres-binaries-linux-arm64v8`
* `dev.tronto.postgis:embedded-postgres-binaries-linux-amd64-alpine`
* `dev.tronto.postgis:embedded-postgres-binaries-linux-arm64v8-alpine`
* `dev.tronto.postgis:embedded-postgres-binaries-windows-amd64`
* `dev.tronto.postgis:embedded-postgres-binaries-darwin-arm64v8`

Once published to Maven Central, consumption only needs `mavenCentral()`.

Publishing to Maven Central uses:

* Central Portal user token username/password
* PGP signing for every published release and snapshot artifact
* `publishTarget=mavenCentral`

Recommended publisher environment variables:

* `ORG_GRADLE_PROJECT_mavenCentralUsername=<central token username>`
* `ORG_GRADLE_PROJECT_mavenCentralPassword=<central token password>`
* `ORG_GRADLE_PROJECT_signingKeyId=<optional key id>`
* `ORG_GRADLE_PROJECT_signingKey=<ascii-armored private key>`
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
If you want a fully automated local release, use `publishAndReleaseToMavenCentral` instead.

```bash
./gradlew publishSnapshotToMavenCentral \
  -PpublishTarget=mavenCentral \
  -Pversion=18.3-3.6.2-SNAPSHOT \
  -PpgVersion=18.3 \
  -PpostgisVersion=3.6.2
```

For a full multi-platform release or snapshot publish, use the GitHub Actions workflows rather than a local machine. The Maven-like Central compatibility API groups uploads by client IP, so the repository workflows build on platform-specific runners and then perform the actual Maven Central publish from one final Ubuntu runner.

### GitHub Packages

This repository also publishes the same coordinates to:

`https://maven.pkg.github.com/tronto20/embedded-postgis-binaries`

GitHub Packages still requires authentication for Maven and Gradle consumers, even when the package is public.

Recommended environment variables for GitHub Packages consumers:

* `GITHUB_USERNAME=<your GitHub username>`
* `GITHUB_TOKEN=<PAT classic with read:packages>`

Inside GitHub Actions for this same repository, `GITHUB_TOKEN` is enough for publishing.

### macOS

This repository publishes Apple Silicon macOS bundles only.

Primary macOS artifact:

* `embedded-postgres-binaries-darwin-arm64v8`

Compatibility alias for existing Zonky-based setups on Apple Silicon:

* `embedded-postgres-binaries-darwin-amd64`

Use one of them, not both. The compatibility alias keeps the legacy Zonky module name, but its payload is still the Apple Silicon `postgres-darwin-arm_64.txz` bundle.

If you are running an Intel macOS JVM, this repository does not publish a native `darwin-x86_64` bundle.

### Quick start

Groovy DSL (`build.gradle`):

```gradle
repositories {
    mavenCentral()
}

dependencies {
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    testRuntimeOnly(platform("dev.tronto.postgis:embedded-postgres-binaries-bom:18.3-3.6.2"))
    testRuntimeOnly("dev.tronto.postgis:embedded-postgres-binaries-darwin-arm64v8")
}
```

Kotlin DSL (`build.gradle.kts`):

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    testRuntimeOnly(platform("dev.tronto.postgis:embedded-postgres-binaries-bom:18.3-3.6.2"))
    testRuntimeOnly("dev.tronto.postgis:embedded-postgres-binaries-darwin-arm64v8")
}
```

Maven (`pom.xml`):

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>dev.tronto.postgis</groupId>
            <artifactId>embedded-postgres-binaries-bom</artifactId>
            <version>18.3-3.6.2</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.zonky.test</groupId>
        <artifactId>embedded-postgres</artifactId>
        <version>2.2.2</version>
    </dependency>
    <dependency>
        <groupId>dev.tronto.postgis</groupId>
        <artifactId>embedded-postgres-binaries-darwin-arm64v8</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

For the full compatibility guide, including drop-in substitution for existing Zonky users and the Apple Silicon compatibility alias, see [docs/using-with-zonky.md](docs/using-with-zonky.md).

### Workflow behavior

The default `Checks`, milestone-driven `Release`, manual `Publish Snapshot`, and `Publish GitHub Packages` workflows use the same default platform set:

* `Linux amd64`
* `Linux arm64`
* `Alpine amd64`
* `Alpine arm64`
* `Windows amd64`
* `macOS arm64`

For the Linux and Alpine arm64 targets, these workflows use native GitHub-hosted arm64 Linux runners instead of amd64 runners with QEMU emulation.

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

## Supported architectures

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

The GitHub Packages workflow does not publish the full historical matrix. By default it publishes only the six platform artifacts listed above. Windows arm64 is available only when you explicitly set `include_windows_arm64=true`.

## Building from Source
The project uses a [Gradle](http://gradle.org)-based build system. In the instructions
below, [`./gradlew`](http://vimeo.com/34436402) is invoked from the root of the source tree and serves as
a cross-platform, self-contained bootstrap mechanism for the build.

### Prerequisites

[Git](http://help.github.com/set-up-git-redirect), JDK 17 or later and [Docker](https://www.docker.com/get-started)

Be sure that your `JAVA_HOME` environment variable points to a JDK 17 installation.

Compiling non-native architectures rely on emulation, so it is necessary to register `qemu-*-static` executables:
   
`docker run --rm --privileged multiarch/qemu-user-static:register --reset`

**Note that the complete build of all supported architectures is now supported only on Linux platform.**

### Check out sources
`git clone git@github.com:tronto20/embedded-postgis-binaries.git`

### Make complete build

Builds all supported artifacts for all supported platforms and architectures, and also builds a BOM to control the versions of postgres binaries.

`./gradlew clean install --parallel -PpgVersion=18.3 -PpostgisVersion=3.6.2`

This repository now targets PostgreSQL 16.x, 17.x, and 18.x. Builds always include PostGIS and support the 3.4.x, 3.5.x, and 3.6.x release lines.

Note that the complete build can take a very long time, even a few hours, depending on the performance of the machine on which the build is running.

### Make partial build

Builds only binaries for a specified platform/submodule.

`./gradlew clean :debian-platforms:install -PpgVersion=18.3 -PpostgisVersion=3.6.2`

### Build only a single binary

Builds only a single binary for a specified platform and architecture.

`./gradlew clean install -PpgVersion=18.3 -PpostgisVersion=3.6.2 -ParchName=arm64v8 -PdistName=alpine`

PostgreSQL builds in this repository always include PostGIS. Supported combinations are PostgreSQL 16.x, 17.x, and 18.x with PostGIS 3.4.x, 3.5.x, or 3.6.x.
The supported cross-platform core feature set includes coordinate transforms, MVT, and Geobuf support.

For local Gradle builds, you can pass either exact patch versions or release branches:

`./gradlew :debian-platforms:testAmd64DebianJar -PpgVersion=17 -PpostgisVersion=3.4`

For Apple Silicon macOS, use the dedicated Darwin build:

`./gradlew :custom-darwin-platform:testCustomDarwinJar -PpgVersion=18.3 -PpostgisVersion=3.6.2 -PdistName=darwin -ParchName=arm64v8`

For Windows amd64, use the dedicated Windows build:

`./gradlew :custom-windows-platform:testCustomWindowsJar -PpgVersion=18.3 -PpostgisVersion=3.6.2 -PdistName=windows -ParchName=amd64`

For Windows arm64, use the dedicated Windows ARM build:

`./gradlew :custom-windows-platform:testCustomWindowsJar -PpgVersion=18.2 -PpostgisVersion=3.6.2 -PdistName=windows -ParchName=arm64v8`

Windows arm64 is currently experimental. It is not included in the default `Checks` or `Publish GitHub Packages` workflows and should be enabled manually only when you are validating that path specifically.

Optional parameters:
- *postgisVersion*
  - default value: `3.6.2`
  - supported values: `3.4.x`, `3.5.x`, `3.6.x`
  - note: use the latest patch releases from the supported branches, for example `3.4.5`, `3.5.5`, or `3.6.2`
  - note: the common cross-platform feature set is PostGIS core plus coordinate transforms, `geography`, MVT, and Geobuf
  - note: `raster` is intentionally excluded
  - note: macOS arm64 currently excludes `topology`, `address_standardizer`, `tiger`, and `sfcgal`
  - note: Windows arm64 currently excludes `address_standardizer`, `topology`, and `sfcgal` in addition to `raster`
  - note: Windows amd64 packages the same intended core feature set and does not currently bundle the extra PostGIS extension families

- *pgBinVersion*
  - default value: `<pgVersion>-1`
  - note: this applies to Windows amd64 only and is usually needed only when the published EnterpriseDB binary suffix does not match the default
- *archName*
  - default value: `amd64`
  - supported values: `amd64`, `i386`, `arm32v6`, `arm32v7`, `arm64v8`, `ppc64le`
  - note: `distName=darwin` currently supports only `arm64v8`
- *distName*
  - default value: debian-like distribution
  - supported values: the default value, `alpine`, `darwin`, or `windows`
  - note: `distName=darwin` and `distName=windows` are PostGIS-enabled build paths; leave PostGIS enabled or set an explicit `postgisVersion`
  - note: `distName=windows` supports `amd64` and `arm64v8`; `amd64` repacks the official PostgreSQL + PostGIS Windows bundles, while `arm64v8` repacks the current MSYS2 `clangarm64` PostgreSQL/PostGIS packages and is limited to the versions published there
- *dockerImage*
  - default value: resolved based on the platform
  - supported values: any supported docker image
- *qemuPath*
  - default value: executables are resolved from `/usr/bin` directory or downloaded from https://github.com/multiarch/qemu-user-static/releases/download/v2.12.0
  - supported values: a path to a directory containing qemu executables

The `Release` workflow publishes versions in the `<pg_version>-<postgis_version>` format and targets the six default release artifacts listed above. It still runs automatically when a milestone is closed, and it can also be started manually with `workflow_dispatch`. Linux and Alpine are built as four separate jobs (`linux-amd64`, `linux-arm64`, `alpine-amd64`, `alpine-arm64`), Windows amd64 and macOS arm64 are tested on their native runners, and then all Maven Central uploads are performed from a single final Ubuntu job.

The workflow intentionally stops after the signed release artifacts have been uploaded. You are expected to open the Central deployment in the web UI, inspect it, and publish/release it manually there. Git tags are also left manual so they can follow that verification step.

The `Publish Snapshot` workflow mirrors the same platform build matrix but publishes `<pg_version>-<postgis_version>-SNAPSHOT` artifacts to the Central snapshots repository instead of creating a release deployment.

For manual `Release` runs, `include_windows_arm64` is optional and defaults to `false`. When you set it to `true`, the workflow also publishes the experimental Windows arm64 artifact and includes it in the BOM. You can optionally override that platform's PostgreSQL version with `windows_arm64_pg_version` when the MSYS2 package line lags behind the main release line.

The manual `Checks` workflow accepts `pg_version` and `postgis_version` so you can verify a specific PostgreSQL/PostGIS combination before publishing. By default it runs the same six-platform matrix as the release line: `linux-amd64`, `linux-arm64`, `alpine-amd64`, `alpine-arm64`, `windows-amd64`, and `macos-arm64`. The Linux and Alpine arm64 checks run on native arm64 Linux runners. It also supports optional `pg_bin_version`, `include_windows_arm64`, and `windows_arm64_pg_version` inputs for the Windows paths. `include_windows_arm64` now defaults to `false`.

For the manual workflows, branch inputs are resolved automatically before Gradle runs:

* `pg_version=17` resolves to the latest published PostgreSQL 17 patch release
* `postgis_version=3.5` resolves to the latest published PostGIS 3.5 patch release
* workflows that include Windows amd64 resolve `postgis_version` to the latest patch release that also has an official Windows bundle for that PostgreSQL major
* the experimental Windows arm64 workflow resolves `pg_version` against the versions currently published in MSYS2 `clangarm64`

## License
The project is released under version 2.0 of the [Apache License](http://www.apache.org/licenses/LICENSE-2.0.html).
