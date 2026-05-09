# <img src="zonky.jpg" height="100"> Embedded PostGIS Binaries

## Introduction

This project provides PostGIS-enabled PostgreSQL binaries for testing.
It is a supporting project for the primary [io.zonky.test:embedded-database-spring-test](https://github.com/zonkyio/embedded-database-spring-test) and [io.zonky.test:embedded-postgres](https://github.com/zonkyio/embedded-postgres) projects.
However, with a little effort, the embedded binaries can also be integrated with other projects.
Building from source requires JDK 17 or later.

## Provided features

* PostGIS-enabled PostgreSQL binaries for testing
* Embedded PostgreSQL 16+ binaries even for Linux platform
* Configurable version of PostgreSQL binaries
* PostGIS core is enabled by default for PostgreSQL 16+ builds

Current release-line JAR sizes for `18.3-3.6.2`:

* Linux amd64: `69.4 MB`
* Linux arm64: `67.2 MB`
* Alpine amd64: `81.7 MB`
* Alpine arm64: `78.9 MB`
* Windows amd64: `154.7 MB`
* macOS arm64: `45.4 MB`
* macOS amd64 compatibility alias: same payload as `macOS arm64`

## Consuming published artifacts

This fork publishes binary artifacts under:

* group: `dev.tronto.postgis`
* version format: `<pg_version>-<postgis_version>`

For example:

* PostgreSQL `18.3` with PostGIS `3.6.2` is published as `18.3-3.6.2`
* PostgreSQL `17.9` with PostGIS `3.5.3` is published as `17.9-3.5.3`

Current release examples:

* `18.3-3.6.2`
* `17.9-3.5.3`

The intended compatibility story is:

* keep `io.zonky.test:embedded-postgres`
* replace only the binary artifacts with `dev.tronto.postgis:*`
* exclude the old `io.zonky.test.postgres` binary artifacts so the legacy and PostGIS-enabled bundles do not coexist on the classpath

The `embedded-postgres-binaries-bom` artifact manages versions only. Importing the BOM is not enough by itself.
You still need one or more concrete platform artifacts such as `embedded-postgres-binaries-linux-amd64`
or `embedded-postgres-binaries-darwin-arm64v8`.

Published artifacts also support release branches during local builds:

* `pgVersion=17` resolves to the latest supported PostgreSQL 17 patch release
* `postgisVersion=3.4` resolves to the latest supported PostGIS 3.4 patch release
* Windows amd64 resolves PostGIS branches against the official OSGeo Windows bundle matrix for that PostgreSQL major
* Windows arm64 resolves branches against the versions currently published in MSYS2 `clangarm64`

For publishing, release automation, Maven Central, GitHub Packages, and workflow details, see [docs/publishing.md](docs/publishing.md).

### macOS

This repository publishes Apple Silicon macOS bundles only.

Primary macOS artifact:

* `embedded-postgres-binaries-darwin-arm64v8`

Compatibility alias for existing Zonky-based setups on Apple Silicon:

* `embedded-postgres-binaries-darwin-amd64`

Use one of them, not both. The compatibility alias keeps the legacy Zonky module name, but its payload is still the Apple Silicon `postgres-darwin-arm_64.txz` bundle.

If you are running an Intel macOS JVM, this repository does not publish a native `darwin-x86_64` bundle.

### Quick start

If you are replacing the legacy Zonky binary bundles, exclude `io.zonky.test.postgres` in the same dependency block and then add the `dev.tronto.postgis` binary artifact you want.
The Groovy and Kotlin examples intentionally use the same dependency coordinates; only the DSL syntax changes.

Groovy DSL (`build.gradle`):

```gradle
repositories {
    mavenCentral()
}

dependencies {
    testImplementation("io.zonky.test:embedded-postgres:2.2.2") {
        exclude group: "io.zonky.test.postgres"
    }
    testRuntimeOnly(platform("dev.tronto.postgis:embedded-postgres-binaries-bom:18.3-3.6.2"))
    testRuntimeOnly("dev.tronto.postgis:embedded-postgres-binaries-darwin-arm64v8")
}
```

To use the older release line, change the BOM and runtime version to `17.9-3.5.3`.

Kotlin DSL (`build.gradle.kts`):

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    testImplementation("io.zonky.test:embedded-postgres:2.2.2") {
        exclude(group = "io.zonky.test.postgres")
    }
    testRuntimeOnly(platform("dev.tronto.postgis:embedded-postgres-binaries-bom:18.3-3.6.2"))
    testRuntimeOnly("dev.tronto.postgis:embedded-postgres-binaries-darwin-arm64v8")
}
```

The same pattern works for `17.9-3.5.3`; only the version string changes.

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
        <exclusions>
            <exclusion>
                <groupId>io.zonky.test.postgres</groupId>
                <artifactId>embedded-postgres-binaries-linux-amd64</artifactId>
            </exclusion>
            <exclusion>
                <groupId>io.zonky.test.postgres</groupId>
                <artifactId>embedded-postgres-binaries-linux-amd64-alpine</artifactId>
            </exclusion>
            <exclusion>
                <groupId>io.zonky.test.postgres</groupId>
                <artifactId>embedded-postgres-binaries-windows-amd64</artifactId>
            </exclusion>
            <exclusion>
                <groupId>io.zonky.test.postgres</groupId>
                <artifactId>embedded-postgres-binaries-darwin-amd64</artifactId>
            </exclusion>
        </exclusions>
    </dependency>
    <dependency>
        <groupId>dev.tronto.postgis</groupId>
        <artifactId>embedded-postgres-binaries-darwin-arm64v8</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

Use `17.9-3.5.3` instead of `18.3-3.6.2` if you need the older release line.

For the full compatibility guide, including drop-in substitution for existing Zonky users and the Apple Silicon compatibility alias, see [docs/using-with-zonky.md](docs/using-with-zonky.md).

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

Builds and publishes all supported artifacts for all supported platforms and architectures to your local Maven repository, and also builds a BOM to control the versions of postgres binaries.

In this repository, `install` is the local Maven publish path used by the bundle subprojects.

`./gradlew clean install --parallel -PpgVersion=18.3 -PpostgisVersion=3.6.2`

This repository now targets PostgreSQL 16.x, 17.x, and 18.x. Builds always include PostGIS and support the 3.4.x, 3.5.x, and 3.6.x release lines.

Note that the complete build can take a very long time, even a few hours, depending on the performance of the machine on which the build is running.

### Make partial build

Builds and publishes only binaries for a specified platform/submodule to your local Maven repository.

`./gradlew clean :debian-platforms:install -PpgVersion=18.3 -PpostgisVersion=3.6.2`

### Build only a single binary

Builds and publishes only a single binary for a specified platform and architecture to your local Maven repository.

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

Windows arm64 is currently experimental and should be enabled manually only when you are validating that path specifically.

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

## License
The project is released under version 2.0 of the [Apache License](http://www.apache.org/licenses/LICENSE-2.0.html).
