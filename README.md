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

Published versions use the pattern `<release_version>-postgis-<postgis_version>`.
For example, running the GitHub Packages workflow with `release_version=18.3.0` and `postgis_version=3.6.2`
produces `18.3.0-postgis-3.6.2`.

The `embedded-postgres-binaries-bom` artifact manages versions only. Importing the BOM is not enough by itself.
You still need to declare one or more concrete platform artifacts such as `embedded-postgres-binaries-linux-amd64`
or `embedded-postgres-binaries-darwin-arm64v8`.

### Maven Central

Maven Central is still the source for the official release line. A list of published BOM versions is here:
https://mvnrepository.com/artifact/io.zonky.test.postgres/embedded-postgres-binaries-bom

### GitHub Packages

The manual `Publish GitHub Packages` workflow publishes a matching BOM plus these platform artifacts to the current repository's GitHub Packages registry:

* `embedded-postgres-binaries-linux-amd64`
* `embedded-postgres-binaries-linux-arm64v8`
* `embedded-postgres-binaries-linux-amd64-alpine`
* `embedded-postgres-binaries-linux-arm64v8-alpine`
* `embedded-postgres-binaries-windows-amd64`
* `embedded-postgres-binaries-windows-arm64v8`
* `embedded-postgres-binaries-darwin-arm64v8`

Use the repository URL `https://maven.pkg.github.com/<owner>/<repo>`. Replace `<owner>/<repo>` with the repository that ran the workflow.

Inside GitHub Actions for the same repository, `GITHUB_TOKEN` is enough.
For local development or for another repository, use a token that can read packages, for example a PAT with `read:packages`.

### Gradle example

```gradle
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/<owner>/<repo>")
        credentials {
            username = findProperty("gpr.user") ?: System.getenv("GITHUB_ACTOR")
            password = findProperty("gpr.key") ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:18.3.0-postgis-3.6.2")

    implementation "io.zonky.test.postgres:embedded-postgres-binaries-linux-amd64"
    implementation "io.zonky.test.postgres:embedded-postgres-binaries-linux-arm64v8"
    implementation "io.zonky.test.postgres:embedded-postgres-binaries-linux-amd64-alpine"
    implementation "io.zonky.test.postgres:embedded-postgres-binaries-linux-arm64v8-alpine"
    implementation "io.zonky.test.postgres:embedded-postgres-binaries-windows-amd64"
    implementation "io.zonky.test.postgres:embedded-postgres-binaries-windows-arm64v8"
    implementation "io.zonky.test.postgres:embedded-postgres-binaries-darwin-arm64v8"
}
```

If you only need one platform, declare only that artifact.
For example, on Apple Silicon macOS:

```gradle
dependencies {
    implementation platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:18.3.0-postgis-3.6.2")
    implementation "io.zonky.test.postgres:embedded-postgres-binaries-darwin-arm64v8"
}
```

### Maven example

Configure credentials in `~/.m2/settings.xml`:

```xml
<settings>
    <servers>
        <server>
            <id>github</id>
            <username>${env.GITHUB_ACTOR}</username>
            <password>${env.GITHUB_TOKEN}</password>
        </server>
    </servers>
</settings>
```

Then declare the GitHub Packages repository, import the BOM, and add the platform artifacts you need:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/&lt;owner&gt;/&lt;repo&gt;</url>
    </repository>
</repositories>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.zonky.test.postgres</groupId>
            <artifactId>embedded-postgres-binaries-bom</artifactId>
            <version>18.3.0-postgis-3.6.2</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.zonky.test.postgres</groupId>
        <artifactId>embedded-postgres-binaries-linux-amd64</artifactId>
    </dependency>
    <dependency>
        <groupId>io.zonky.test.postgres</groupId>
        <artifactId>embedded-postgres-binaries-darwin-arm64v8</artifactId>
    </dependency>
</dependencies>
```

## Supported architectures

By default, only dependencies for `amd64` architecture, in the [io.zonky.test:embedded-database-spring-test](https://github.com/zonkyio/embedded-database-spring-test) and [io.zonky.test:embedded-postgres](https://github.com/zonkyio/embedded-postgres) projects, are included.
Support for other architectures can be enabled by adding the corresponding Maven dependencies as shown in the example below.

```xml
<dependency>
    <groupId>io.zonky.test.postgres</groupId>
    <artifactId>embedded-postgres-binaries-linux-i386</artifactId>
    <scope>test</scope>
</dependency>
```

**Supported platforms:** `Darwin`, `Windows`, `Linux`, `Alpine Linux`  
**Supported architectures:** `amd64`, `i386`, `arm32v6`, `arm32v7`, `arm64v8`, `ppc64le`

Note that not all architectures are supported by all platforms, you can find an exhaustive list of all available artifacts here: https://mvnrepository.com/artifact/io.zonky.test.postgres

The GitHub Packages workflow does not publish the full historical matrix. It publishes only the seven platform artifacts listed above.

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
`git clone git@github.com:zonkyio/embedded-postgres-binaries.git`

### Make complete build

Builds all supported artifacts for all supported platforms and architectures, and also builds a BOM to control the versions of postgres binaries.

`./gradlew clean install --parallel -Pversion=18.3.0 -PpgVersion=18.3`

This repository now targets PostgreSQL 16+ only, and these builds include PostGIS `3.6.2` by default.

Note that the complete build can take a very long time, even a few hours, depending on the performance of the machine on which the build is running.

### Make partial build

Builds only binaries for a specified platform/submodule.

`./gradlew clean :debian-platforms:install -Pversion=18.3.0 -PpgVersion=18.3`

### Build only a single binary

Builds only a single binary for a specified platform and architecture.

`./gradlew clean install -Pversion=18.3.0 -PpgVersion=18.3 -ParchName=arm64v8 -PdistName=alpine`

PostgreSQL 16+ builds in this repository always include PostGIS. Use `-PpostgisVersion=3.6.2` only when you want to override the bundled PostGIS version.

For Apple Silicon macOS, use the dedicated Darwin build:

`./gradlew :custom-darwin-platform:testCustomDarwinJar -Pversion=18.3.0 -PpgVersion=18.3 -PdistName=darwin -ParchName=arm64v8`

For Windows amd64, use the dedicated Windows build:

`./gradlew :custom-windows-platform:testCustomWindowsJar -Pversion=18.3.0 -PpgVersion=18.3 -PdistName=windows -ParchName=amd64`

For Windows arm64, use the dedicated Windows ARM build:

`./gradlew :custom-windows-platform:testCustomWindowsJar -Pversion=18.2.0 -PpgVersion=18.2 -PdistName=windows -ParchName=arm64v8`

Optional parameters:
- *postgisVersion*
  - default value: `3.6.2`
  - supported values: a postgis version number (only 2.5.2+, 2.4.7+, 2.3.9+ versions are supported; the build scripts automatically select newer PROJ requirements for PostGIS 3.4+ and newer GEOS/GDAL requirements for PostGIS 3.6+)
  - note: the macOS arm64 build currently packages the core PostGIS extension only; raster support is intentionally disabled

If you are targeting PostgreSQL 18, use PostGIS 3.6+.
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

The milestone-driven `Release` GitHub Actions workflow now uses the default PostGIS-enabled Linux and Alpine build path. The repository also includes manual GitHub Actions workflows for `Publish PostGIS Darwin Artifact`, `Publish PostGIS Windows Artifact`, `Publish PostGIS Windows ARM64 Artifact`, and `Publish GitHub Packages`.

## License
The project is released under version 2.0 of the [Apache License](http://www.apache.org/licenses/LICENSE-2.0.html).
