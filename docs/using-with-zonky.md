# Using These Binaries With `io.zonky.test:embedded-postgres`

This repository publishes PostGIS-enabled binary artifacts under the `dev.tronto.postgis` group.

The intended migration path is:

* keep `io.zonky.test:embedded-postgres`
* replace only the binary artifacts with `dev.tronto.postgis:*`

Published versions use the format `<pg_version>-<postgis_version>`, for example `18.3-3.6.2`.

## Important points

* Importing `embedded-postgres-binaries-bom` manages versions only. You still need one or more concrete platform artifacts.
* For explicit Apple Silicon usage, prefer `embedded-postgres-binaries-darwin-arm64v8`.
* For drop-in compatibility with existing Zonky setups on Apple Silicon, `embedded-postgres-binaries-darwin-amd64` is provided as a compatibility alias. It keeps the legacy module name, but the payload is Apple Silicon only.
* Do not declare both `embedded-postgres-binaries-darwin-arm64v8` and `embedded-postgres-binaries-darwin-amd64`.

## `build.gradle` (Groovy DSL)

This is the closest thing to a drop-in replacement. It keeps the existing Zonky dependency and transparently swaps every `io.zonky.test.postgres:embedded-postgres-binaries-*` module to `dev.tronto.postgis:*`.

```gradle
def postgisBinariesVersion = "18.3-3.6.2"

repositories {
    mavenCentral()
}

configurations.configureEach {
    resolutionStrategy.eachDependency { details ->
        if (details.requested.group == "io.zonky.test.postgres" &&
                details.requested.name.startsWith("embedded-postgres-binaries")) {
            details.useTarget("dev.tronto.postgis:${details.requested.name}:${postgisBinariesVersion}")
            details.because("Use PostGIS-enabled embedded postgres binaries")
        }
    }
}

dependencies {
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
}
```

If you prefer explicit platform declaration instead of substitution:

```gradle
dependencies {
    testImplementation("io.zonky.test:embedded-postgres:2.2.2") {
        exclude group: "io.zonky.test.postgres"
    }

    testRuntimeOnly platform("dev.tronto.postgis:embedded-postgres-binaries-bom:18.3-3.6.2")
    testRuntimeOnly "dev.tronto.postgis:embedded-postgres-binaries-darwin-arm64v8"
}
```

## `build.gradle.kts` (Kotlin DSL)

The Kotlin DSL equivalent of the drop-in substitution setup:

```kotlin
val postgisBinariesVersion = "18.3-3.6.2"

repositories {
    mavenCentral()
}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "io.zonky.test.postgres" &&
            requested.name.startsWith("embedded-postgres-binaries")
        ) {
            useTarget("dev.tronto.postgis:${requested.name}:$postgisBinariesVersion")
            because("Use PostGIS-enabled embedded postgres binaries")
        }
    }
}

dependencies {
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
}
```

The explicit platform form is:

```kotlin
dependencies {
    testImplementation("io.zonky.test:embedded-postgres:2.2.2") {
        exclude(group = "io.zonky.test.postgres")
    }

    testRuntimeOnly(platform("dev.tronto.postgis:embedded-postgres-binaries-bom:18.3-3.6.2"))
    testRuntimeOnly("dev.tronto.postgis:embedded-postgres-binaries-darwin-arm64v8")
}
```

## `pom.xml` (Maven)

Maven does not have a dependency substitution feature equivalent to Gradle, so the migration is explicit:

1. keep `io.zonky.test:embedded-postgres`
2. exclude the old Zonky binary artifacts that would otherwise stay on the classpath
3. import the `dev.tronto.postgis` BOM
4. add the `dev.tronto.postgis` platform artifacts you want

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

If you want the Apple Silicon compatibility alias instead of the explicit arm64 artifact, replace the last dependency with:

```xml
<dependency>
    <groupId>dev.tronto.postgis</groupId>
    <artifactId>embedded-postgres-binaries-darwin-amd64</artifactId>
    <version>18.3-3.6.2</version>
    <scope>runtime</scope>
</dependency>
```

## Platform artifacts

Primary published artifacts:

* `embedded-postgres-binaries-linux-amd64`
* `embedded-postgres-binaries-linux-arm64v8`
* `embedded-postgres-binaries-linux-amd64-alpine`
* `embedded-postgres-binaries-linux-arm64v8-alpine`
* `embedded-postgres-binaries-windows-amd64`
* `embedded-postgres-binaries-darwin-arm64v8`

Additional compatibility / optional artifacts:

* `embedded-postgres-binaries-darwin-amd64`
  * Apple Silicon compatibility alias only
* `embedded-postgres-binaries-windows-arm64v8`
  * optional, experimental
