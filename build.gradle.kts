import de.undercouch.gradle.tasks.download.Download
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.internal.os.OperatingSystem
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.signing.SigningExtension
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

plugins {
    id("de.undercouch.download") version "4.1.1"
    id("java-platform")
}

data class PlatformDefinition(
    val name: String,
    val arch: String,
    val image: String? = null,
)

data class AlpineVariantDefinition(
    val name: String,
    val opt: String,
    val enabled: Boolean,
)

data class BomDependency(
    val artifactId: String,
    val version: String,
)

fun String.cap(): String = replaceFirstChar {
    if (it.isLowerCase()) {
        it.titlecase(Locale.US)
    } else {
        it.toString()
    }
}

fun normalizeVersionSpec(spec: String?): String {
    val value = (spec ?: "").trim()
    return if (value.endsWith(".x")) value.dropLast(2) else value
}

fun versionComponents(version: String): List<Int> = version.split('.').map { it.toInt() }

fun compareVersionComponents(left: List<Int>, right: List<Int>): Int {
    val maxSize = maxOf(left.size, right.size)
    for (index in 0 until maxSize) {
        val leftValue = left.getOrElse(index) { 0 }
        val rightValue = right.getOrElse(index) { 0 }
        if (leftValue != rightValue) {
            return leftValue.compareTo(rightValue)
        }
    }
    return 0
}

val versionIndexCache = mutableMapOf<String, List<String>>()

fun fetchIndexedVersions(cacheKey: String, url: String, regex: Regex): List<String> {
    versionIndexCache[cacheKey]?.let { return it }

    val connection = java.net.URI.create(url).toURL().openConnection()
    connection.connectTimeout = 30_000
    connection.readTimeout = 30_000
    connection.setRequestProperty("User-Agent", "embedded-postgres-binaries-version-resolver/1.0")

    val html = connection.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
    val versions = regex.findAll(html)
        .map { it.groupValues[1] }
        .toSet()
        .toList()
        .sortedWith { left, right ->
            compareVersionComponents(versionComponents(right), versionComponents(left))
        }

    versionIndexCache[cacheKey] = versions
    return versions
}

fun fetchWindowsAmd64PostgisVersions(pgMajor: Int): List<String> {
    val cacheKey = "windows-amd64-postgis-$pgMajor"
    versionIndexCache[cacheKey]?.let { return it }

    val regex = Regex("postgis-bundle-pg$pgMajor-(\\d+\\.\\d+\\.\\d+)x64\\.zip")
    val versions = (
        fetchIndexedVersions("${cacheKey}-current", "https://download.osgeo.org/postgis/windows/pg$pgMajor/", regex) +
            fetchIndexedVersions("${cacheKey}-archive", "https://download.osgeo.org/postgis/windows/pg$pgMajor/archive/", regex)
        )
        .toSet()
        .toList()
        .sortedWith { left, right ->
            compareVersionComponents(versionComponents(right), versionComponents(left))
        }

    versionIndexCache[cacheKey] = versions
    return versions
}

fun resolvePostgresVersionSpec(requestedSpec: String?, windowsArm64: Boolean = false): String {
    val spec = normalizeVersionSpec(requestedSpec)
    if (spec.isEmpty()) {
        return spec
    }
    if (Regex("""^\d+\.\d+$""").matches(spec)) {
        return spec
    }
    if (!Regex("""^\d+$""").matches(spec)) {
        throw GradleException("PostgreSQL versions must be a major branch like '17' or an exact patch release like '17.9'")
    }
    if (gradle.startParameter.isOffline) {
        throw GradleException("Cannot resolve the PostgreSQL branch '$requestedSpec' in offline mode; specify an exact patch release instead")
    }

    val versions = if (windowsArm64) {
        fetchIndexedVersions(
            "windows-arm64-postgres",
            "https://mirror.msys2.org/mingw/clangarm64/",
            Regex("""mingw-w64-clang-aarch64-postgresql(?:-\d+)?-(\d+\.\d+)-\d+-any\.pkg\.tar\.zst"""),
        )
    } else {
        fetchIndexedVersions(
            "postgres",
            "https://ftp.postgresql.org/pub/source/",
            Regex("""href="v(\d+\.\d+)/""""),
        )
    }

    return versions.find { it.startsWith("$spec.") }
        ?: throw GradleException("Unable to resolve the latest PostgreSQL patch release for branch '$requestedSpec'")
}

fun resolvePostgisVersionSpec(requestedSpec: String?, windowsArm64: Boolean = false): String {
    val spec = normalizeVersionSpec(requestedSpec)
    if (spec.isEmpty()) {
        return spec
    }
    if (Regex("""^\d+\.\d+\.\d+$""").matches(spec)) {
        return spec
    }
    if (!Regex("""^\d+\.\d+$""").matches(spec)) {
        throw GradleException("PostGIS versions must be a release branch like '3.6' or an exact patch release like '3.6.2'")
    }
    if (gradle.startParameter.isOffline) {
        throw GradleException("Cannot resolve the PostGIS branch '$requestedSpec' in offline mode; specify an exact patch release instead")
    }

    val versions = if (windowsArm64) {
        fetchIndexedVersions(
            "windows-arm64-postgis",
            "https://mirror.msys2.org/mingw/clangarm64/",
            Regex("""mingw-w64-clang-aarch64-postgis-(\d+\.\d+\.\d+)-\d+-any\.pkg\.tar\.zst"""),
        )
    } else {
        fetchIndexedVersions(
            "postgis",
            "https://postgis.net/stuff/",
            Regex("""href="postgis-(\d+\.\d+\.\d+)\.tar\.gz""""),
        )
    }

    return versions.find { it.startsWith("$spec.") }
        ?: throw GradleException("Unable to resolve the latest PostGIS patch release for branch '$requestedSpec'")
}

fun resolveWindowsAmd64PostgisVersionSpec(requestedSpec: String?, pgMajor: Int): String {
    val spec = normalizeVersionSpec(requestedSpec)
    if (spec.isEmpty()) {
        return spec
    }

    if (gradle.startParameter.isOffline) {
        if (Regex("""^\d+\.\d+\.\d+$""").matches(spec)) {
            return spec
        }
        throw GradleException("Cannot resolve the Windows amd64 PostGIS branch '$requestedSpec' in offline mode; specify an exact patch release instead")
    }

    val versions = fetchWindowsAmd64PostgisVersions(pgMajor)
    if (Regex("""^\d+\.\d+\.\d+$""").matches(spec)) {
        if (!versions.contains(spec)) {
            throw GradleException("PostGIS $spec is not published for Windows amd64 PostgreSQL $pgMajor; available versions are ${versions.joinToString(", ")}")
        }
        return spec
    }
    if (!Regex("""^\d+\.\d+$""").matches(spec)) {
        throw GradleException("PostGIS versions must be a release branch like '3.6' or an exact patch release like '3.6.2'")
    }

    return versions.find { it.startsWith("$spec.") }
        ?: throw GradleException("No Windows amd64 PostGIS bundle matching branch '$requestedSpec' is published for PostgreSQL $pgMajor; available versions are ${versions.joinToString(", ")}")
}

val requestedVersionParam = gradle.startParameter.projectProperties["version"]
val rawPublishTargetParam = findProperty("publishTarget")?.toString() ?: "mavenCentral"
val publishTargetParam = if (rawPublishTargetParam == "ossrh") "mavenCentral" else rawPublishTargetParam
val mavenCentralUsername = findProperty("mavenCentralUsername")?.toString()
    ?: findProperty("sonatypeUsername")?.toString()
    ?: findProperty("ossrh.username")?.toString()
    ?: System.getenv("MAVEN_CENTRAL_USERNAME")
    ?: System.getenv("SONATYPE_USERNAME")
    ?: System.getenv("OSSRH_USERNAME")
val mavenCentralPassword = findProperty("mavenCentralPassword")?.toString()
    ?: findProperty("sonatypePassword")?.toString()
    ?: findProperty("ossrh.password")?.toString()
    ?: System.getenv("MAVEN_CENTRAL_PASSWORD")
    ?: System.getenv("SONATYPE_PASSWORD")
    ?: System.getenv("OSSRH_PASSWORD")
val mavenCentralNamespace = findProperty("mavenCentralNamespace")?.toString() ?: "dev.tronto.postgis"
val githubPackagesUrl = findProperty("githubPackagesUrl")?.toString()
val githubPackagesUsername = findProperty("githubPackagesUsername")?.toString() ?: System.getenv("GITHUB_ACTOR")
val githubPackagesPassword = findProperty("githubPackagesPassword")?.toString() ?: System.getenv("GITHUB_TOKEN")
val mavenCentralReleasesUrl = "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
val mavenCentralSnapshotsUrl = "https://central.sonatype.com/repository/maven-snapshots/"
val bomArtifactNamesParam = (findProperty("bomArtifactNames")?.toString() ?: "")
    .split(',')
    .map { it.trim() }
    .filter { it.isNotEmpty() }
val bomArtifactVersionOverridesParam = (findProperty("bomArtifactVersionOverrides")?.toString() ?: "")
    .split(',')
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .associate { entry ->
        val delimiter = entry.indexOf('=')
        if (delimiter <= 0 || delimiter == entry.length - 1) {
            throw GradleException("The 'bomArtifactVersionOverrides' property must use artifactId=version entries")
        }
        entry.substring(0, delimiter) to entry.substring(delimiter + 1)
    }

val requestedPgVersionParam = findProperty("pgVersion")?.toString() ?: ""
val requestedPostgisVersionParam = findProperty("postgisVersion")?.toString() ?: "3.6.2"
val distNameParam = findProperty("distName")?.toString() ?: ""
val archNameParam = findProperty("archName")?.toString() ?: ""
val windowsTargetParam = distNameParam == "windows"
val windowsArm64TargetParam = windowsTargetParam && archNameParam == "arm64v8"
val windowsAmd64TargetParam = windowsTargetParam && archNameParam != "arm64v8"
val pgVersionParam = resolvePostgresVersionSpec(requestedPgVersionParam, windowsArm64TargetParam)
val pgBinVersionParam = findProperty("pgBinVersion")?.toString() ?: if (pgVersionParam.isNotEmpty()) "$pgVersionParam-1" else ""
val pgMajorVersionParam = Regex("""(\d+).+""").find(pgVersionParam)?.groupValues?.get(1)?.toInt()
val postgisVersionParam = if (windowsAmd64TargetParam) {
    resolveWindowsAmd64PostgisVersionSpec(requestedPostgisVersionParam, pgMajorVersionParam ?: 0)
} else {
    resolvePostgisVersionSpec(requestedPostgisVersionParam, windowsArm64TargetParam)
}
val postgisVersionMatcher = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?$""").find(postgisVersionParam)
val postgisMajorVersionParam = postgisVersionMatcher?.groupValues?.get(1)?.toInt()
val postgisMinorVersionParam = postgisVersionMatcher?.groupValues?.get(2)?.toInt()
val artifactVersionParam = requestedVersionParam ?: if (pgVersionParam.isNotEmpty()) "$pgVersionParam-$postgisVersionParam" else null
val dockerImageParam = findProperty("dockerImage")?.toString() ?: ""
val qemuPathParam = findProperty("qemuPath")?.toString() ?: ""

version = artifactVersionParam ?: version

fun resolveQemuBindings(prepareQemuExecutables: TaskProvider<Copy>): String {
    var bindings = fileTree(if (qemuPathParam.isNotEmpty()) qemuPathParam else "/usr/bin") {
        include("qemu-*-static")
    }.files

    if (bindings.isEmpty() && qemuPathParam.isEmpty()) {
        bindings = fileTree(prepareQemuExecutables.get().destinationDir) {
            include("qemu-*-static")
        }.files
    }

    return bindings.joinToString(" ") { "-v ${it.path}:/usr/bin/${it.name}" }
}

fun defaultDebianImage(archName: String, useEmulation: Boolean): String {
    val system = OperatingSystem.current()
    val systemArch = System.getProperty("os.arch")
    return when {
        normalizeArchName(archName) == normalizeArchName(systemArch) -> "ubuntu:18.04"
        system.isMacOsX || useEmulation -> "$archName/ubuntu:18.04"
        system.isLinux -> {
            val archMappings = mapOf("arm32v6" to "armel", "arm32v7" to "armhf", "arm64v8" to "arm64", "ppc64le" to "ppc64el")
            "multiarch/ubuntu-core:${archMappings[archName] ?: archName}-bionic"
        }
        else -> throw GradleException("Cross-building is not supported on the current platform: $system")
    }
}

fun defaultAlpineImage(archName: String, useEmulation: Boolean): String {
    val system = OperatingSystem.current()
    val systemArch = System.getProperty("os.arch")
    return when {
        normalizeArchName(archName) == normalizeArchName(systemArch) -> "alpine:3.9"
        system.isMacOsX || useEmulation -> "$archName/alpine:3.9"
        system.isLinux -> {
            val archMappings = mapOf("arm32v5" to "armel", "arm32v6" to "armhf", "arm64v8" to "arm64")
            "multiarch/alpine:${archMappings[archName] ?: archName}-v3.9"
        }
        else -> throw GradleException("Cross-building is not supported on the current platform: $system")
    }
}

fun normalizeArchName(input: String): String {
    val arch = input.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "")

    return when {
        Regex("^(x8664|amd64|ia32e|em64t|x64)$").matches(arch) -> "x86_64"
        Regex("^(x8632|x86|i[3-6]86|ia32|x32)$").matches(arch) -> "x86_32"
        Regex("^(ia64w?|itanium64)$").matches(arch) -> "itanium_64"
        arch == "ia64n" -> "itanium_32"
        Regex("^(sparcv9|sparc64)$").matches(arch) -> "sparc_64"
        Regex("^(sparc|sparc32)$").matches(arch) -> "sparc_32"
        Regex("^(aarch64|armv8|arm64).*$").matches(arch) -> "arm_64"
        Regex("^(arm|arm32).*$").matches(arch) -> "arm_32"
        Regex("^(mips|mips32)$").matches(arch) -> "mips_32"
        Regex("^(mipsel|mips32el)$").matches(arch) -> "mipsel_32"
        arch == "mips64" -> "mips_64"
        arch == "mips64el" -> "mipsel_64"
        Regex("^(ppc|ppc32)$").matches(arch) -> "ppc_32"
        Regex("^(ppcle|ppc32le)$").matches(arch) -> "ppcle_32"
        arch == "ppc64" -> "ppc_64"
        arch == "ppc64le" -> "ppcle_64"
        arch == "s390" -> "s390_32"
        arch == "s390x" -> "s390_64"
        else -> throw GradleException("Unsupported architecture: $arch")
    }
}

fun configureLazyCommandLine(task: Exec, vararg arguments: Any?) {
    task.doFirst {
        val resolved = arguments.map { argument ->
            when (argument) {
                is Function0<*> -> argument.invoke()
                else -> argument
            }
        }
        task.commandLine(resolved)
    }
}

fun resolveWindowsBashExecutable(): String {
    if (!OperatingSystem.current().isWindows) {
        return "bash"
    }

    val candidatePaths = mutableListOf<File>()
    listOf(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"))
        .filterNotNull()
        .forEach { base ->
            candidatePaths += File(base, "Git/bin/bash.exe")
            candidatePaths += File(base, "Git/usr/bin/bash.exe")
        }

    (System.getenv("PATH") ?: "")
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .forEach { dir ->
            candidatePaths += File(dir, "bash.exe")
        }

    candidatePaths.forEach { candidate ->
        val normalizedPath = candidate.absolutePath.replace('\\', '/').lowercase(Locale.US)
        if (normalizedPath.endsWith("/windows/system32/bash.exe")) {
            return@forEach
        }
        if (candidate.exists()) {
            return candidate.absolutePath
        }
    }

    throw GradleException("Unable to locate Git Bash on Windows; install Git for Windows or add bash.exe to PATH")
}

fun configurePom(publication: MavenPublication, artifact: String, desc: String) {
    publication.pom {
        name.set(artifact)
        description.set(desc)
        url.set("https://github.com/tronto20/embedded-postgis-binaries")

        scm {
            connection.set("scm:git:git://github.com/tronto20/embedded-postgis-binaries.git")
            developerConnection.set("scm:git:ssh://git@github.com/tronto20/embedded-postgis-binaries.git")
            url.set("https://github.com/tronto20/embedded-postgis-binaries")
        }

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("tronto20")
                name.set("tronto20")
            }
        }
    }
}

fun registerBundlePublication(project: Project, publicationName: String, artifactIdValue: String, jarTask: TaskProvider<Jar>, testTask: TaskProvider<out Task>? = null) {
    project.artifacts.add("bundles", jarTask)

    val sourcesJar = project.tasks.named("sourcesJar", Jar::class.java)
    val javadocJar = project.tasks.named("javadocJar", Jar::class.java)

    project.extensions.configure(PublishingExtension::class.java) {
        publications.create(publicationName, MavenPublication::class.java) {
            artifactId = artifactIdValue
            configurePom(this, artifactIdValue, "A lightweight bundle of PostgreSQL database with reduced size")

            artifact(jarTask)
            artifact(sourcesJar)
            artifact(javadocJar)
        }
    }

    project.tasks.named("jar") {
        dependsOn(jarTask)
    }
    if (testTask != null) {
        project.tasks.named("test", Test::class.java) {
            dependsOn(testTask)
        }
    }
    project.tasks.named("install") {
        dependsOn("publish${publicationName.cap()}PublicationToMavenLocal")
    }
}

fun resolveBomDependencies(project: Project): List<BomDependency> {
    val dependencies = if (bomArtifactNamesParam.isNotEmpty()) {
        bomArtifactNamesParam.map { artifactId ->
            BomDependency(artifactId, bomArtifactVersionOverridesParam[artifactId] ?: project.version.toString())
        }
    } else {
        project.subprojects.flatMap { subproject ->
            subproject.configurations.getByName("bundles").artifacts.map { archive ->
                BomDependency(archive.name, project.version.toString())
            }
        }
    }

    val sortRegex = Regex("""^embedded-postgres-binaries-([^-]+-([^-]+).*)$""")

    return dependencies
        .filter { it.artifactId != "embedded-postgres-binaries-darwin-amd64" }
        .sortedBy { dependency ->
            val match = sortRegex.find(dependency.artifactId)
            if (match == null) {
                dependency.artifactId
            } else {
                val suffix = match.groupValues[1]
                val arch = match.groupValues[2]
                val priority = listOf("amd64", "i386").indexOf(arch).let { if (it != -1) it else 9 }
                "$priority$suffix"
            }
        }
}

allprojects {
    pluginManager.apply("maven-publish")
    pluginManager.apply("signing")

    group = "dev.tronto.postgis"
    version = rootProject.version

    repositories {
        mavenCentral()
    }

    extensions.configure<PublishingExtension> {
        repositories {
            if (publishTargetParam == "githubPackages") {
                maven {
                    name = "githubPackages"
                    url = uri(githubPackagesUrl ?: "https://maven.pkg.github.com/invalid/invalid")
                    credentials {
                        username = githubPackagesUsername
                        password = githubPackagesPassword
                    }
                }
            } else if (publishTargetParam == "mavenCentral") {
                maven {
                    name = "mavenCentral"
                    url = uri(if (version.toString().endsWith("SNAPSHOT")) mavenCentralSnapshotsUrl else mavenCentralReleasesUrl)
                    credentials {
                        username = mavenCentralUsername
                        password = mavenCentralPassword
                    }
                }
            }
        }
    }

    extensions.configure<SigningExtension> {
        setRequired {
            publishTargetParam == "mavenCentral" && gradle.taskGraph.allTasks.any { task ->
                task.name.startsWith("publish") && !task.name.endsWith("ToMavenLocal")
            }
        }

        if (publishTargetParam == "mavenCentral") {
            val signingKey = findProperty("signingKey")?.toString() ?: System.getenv("SIGNING_KEY")
            val signingKeyId = findProperty("signingKeyId")?.toString() ?: System.getenv("SIGNING_KEY_ID")
            val signingPassword = findProperty("signingPassword")?.toString() ?: System.getenv("SIGNING_PASSWORD")
            useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)

            val publishing = extensions.getByType(PublishingExtension::class.java)
            sign(publishing.publications)
        }
    }

    tasks.register("install") {
        group = "publishing"
    }

    tasks.register("uploadArchives") {
        group = "publishing"
        dependsOn(
            if (this@allprojects == rootProject) {
                if (version.toString().endsWith("SNAPSHOT")) {
                    "publishSnapshotToMavenCentral"
                } else {
                    "publishReleaseToMavenCentral"
                }
            } else {
                "publish"
            },
        )
    }
}

subprojects {
    pluginManager.apply("java")

    extensions.configure<BasePluginExtension> {
        archivesName.set("embedded-postgres-binaries")
    }

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_1_6
        targetCompatibility = JavaVersion.VERSION_1_6
    }

    configurations.maybeCreate("bundles")

    val sourceSets = extensions.getByType(SourceSetContainer::class.java)
    val sourcesJar = tasks.register("sourcesJar", Jar::class.java) {
        dependsOn(tasks.named("classes"))
        from(sourceSets.getByName("main").allSource)
        archiveClassifier.set("sources")
    }
    val javadocJar = tasks.register("javadocJar", Jar::class.java) {
        dependsOn(tasks.named("javadoc"))
        from(tasks.named("javadoc", Javadoc::class.java).map { it.destinationDir })
        archiveClassifier.set("javadoc")
    }

}

val validateInputs = tasks.register("validateInputs") {
    doFirst {
        println("version:       ${project.version}")
        println("requestedPgVersion: $requestedPgVersionParam")
        println("pgVersion:     $pgVersionParam")
        println("pgBinVersion:  $pgBinVersionParam")
        println("requestedPostgisVersion: $requestedPostgisVersionParam")
        println("postgisVersion: $postgisVersionParam")

        if (pgVersionParam.isEmpty()) {
            throw GradleException("The 'pgVersion' property must be set")
        }
        if (project.version.toString().isBlank() || project.version.toString() == "unspecified") {
            throw GradleException("The artifact version could not be resolved")
        }
        if (requestedVersionParam?.contains("-postgis-") == true) {
            throw GradleException("Use the '<pgVersion>-<postgisVersion>' artifact version format instead of '-postgis-'")
        }
        if (pgMajorVersionParam == null || pgMajorVersionParam < 16 || pgMajorVersionParam > 18) {
            throw GradleException("This repository currently supports PostgreSQL 16, 17, and 18")
        }
        if (postgisMajorVersionParam != 3 || postgisMinorVersionParam !in listOf(4, 5, 6)) {
            throw GradleException("This repository currently supports PostGIS 3.4.x, 3.5.x, and 3.6.x")
        }
        if (findProperty("defaultPostgisVersion") != null) {
            throw GradleException("PostGIS is always enabled; use the 'postgisVersion' property to override the bundled version")
        }
        if (findProperty("disablePostgis") != null) {
            throw GradleException("PostGIS is always enabled; the 'disablePostgis' property is no longer supported")
        }
        if (publishTargetParam !in listOf("mavenCentral", "githubPackages")) {
            throw GradleException("The 'publishTarget' property must be either 'mavenCentral' or 'githubPackages'")
        }
        if (publishTargetParam == "githubPackages" && githubPackagesUrl.isNullOrBlank()) {
            throw GradleException("The 'githubPackagesUrl' property must be set when publishTarget=githubPackages")
        }
        if (bomArtifactVersionOverridesParam.keys.any { it !in bomArtifactNamesParam }) {
            throw GradleException("Every entry in 'bomArtifactVersionOverrides' must also be present in 'bomArtifactNames'")
        }
        if (distNameParam.isNotEmpty() && distNameParam !in listOf("alpine", "darwin", "windows")) {
            throw GradleException("Currently only the 'alpine', 'darwin', and 'windows' distributions are supported")
        }
        if (archNameParam.isNotEmpty() && !Regex("""^[a-z0-9]+$""").matches(archNameParam)) {
            throw GradleException("The 'archName' property must contain only alphanumeric characters")
        }
        if (distNameParam in listOf("darwin", "windows") && postgisVersionParam.isEmpty()) {
            throw GradleException("The 'postgisVersion' property must be set when building the '$distNameParam' distribution")
        }
        if (distNameParam == "darwin" && archNameParam.isNotEmpty() && archNameParam != "arm64v8") {
            throw GradleException("The 'darwin' distribution currently supports only the 'arm64v8' architecture")
        }
        if (distNameParam == "windows" && archNameParam.isNotEmpty() && archNameParam !in listOf("amd64", "arm64v8")) {
            throw GradleException("The 'windows' distribution currently supports only the 'amd64' and 'arm64v8' architectures")
        }
        if (distNameParam == "darwin") {
            val system = OperatingSystem.current()
            if (!system.isMacOsX || normalizeArchName(System.getProperty("os.arch")) != "arm_64") {
                throw GradleException("The 'darwin' distribution currently requires an Apple Silicon macOS host")
            }
        }
    }
}

fun requireMavenCentralCredentials() {
    if (mavenCentralUsername.isNullOrBlank() || mavenCentralPassword.isNullOrBlank()) {
        throw GradleException(
            "Maven Central credentials are required. Set 'mavenCentralUsername'/'mavenCentralPassword' " +
                "or the SONATYPE/MAVEN_CENTRAL environment variable equivalents.",
        )
    }
}

fun requireSigningConfiguration() {
    val signingKey = findProperty("signingKey")?.toString() ?: System.getenv("SIGNING_KEY")
    val signingPassword = findProperty("signingPassword")?.toString() ?: System.getenv("SIGNING_PASSWORD")
    if (signingKey.isNullOrBlank() || signingPassword.isNullOrBlank()) {
        throw GradleException(
            "Signing is required for Maven Central publishing. Provide 'signingKey' and 'signingPassword' " +
                "(and optionally 'signingKeyId') as Gradle properties or environment variables.",
        )
    }
}

val checkMavenCentralPublishing = tasks.register("checkMavenCentralPublishing") {
    group = "publishing"
    dependsOn(validateInputs)
    doLast {
        if (publishTargetParam != "mavenCentral") {
            throw GradleException("This task requires -PpublishTarget=mavenCentral")
        }
        requireMavenCentralCredentials()
        requireSigningConfiguration()
        if (mavenCentralNamespace.isBlank()) {
            throw GradleException("The 'mavenCentralNamespace' property must not be blank")
        }
    }
}

val releaseMavenCentralDeployment = tasks.register("releaseMavenCentralDeployment") {
    group = "publishing"
    dependsOn(checkMavenCentralPublishing)
    onlyIf {
        publishTargetParam == "mavenCentral" && !version.toString().endsWith("SNAPSHOT")
    }
    doLast {
        requireMavenCentralCredentials()

        val authorization = Base64.getEncoder().encodeToString(
            "${mavenCentralUsername}:${mavenCentralPassword}".toByteArray(StandardCharsets.UTF_8),
        )
        val connection = java.net.URI.create(
            "https://ossrh-staging-api.central.sonatype.com/manual/upload/defaultRepository/" +
                "$mavenCentralNamespace?publishing_type=automatic",
        ).toURL().openConnection() as HttpURLConnection

        connection.requestMethod = "POST"
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer $authorization")
        connection.outputStream.use { }

        val responseCode = connection.responseCode
        val responseBody = (
            if (responseCode in 200..299) connection.inputStream else connection.errorStream
            )?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

        if (responseCode !in 200..299) {
            throw GradleException(
                "Failed to finalize the Maven Central deployment for namespace '$mavenCentralNamespace' " +
                    "(HTTP $responseCode): $responseBody",
            )
        }

        println("Central deployment accepted for namespace '$mavenCentralNamespace'.")
        if (responseBody.isNotBlank()) {
            println(responseBody)
        }
    }
}

tasks.register("publishToMavenCentral") {
    group = "publishing"
    dependsOn(checkMavenCentralPublishing)
    if (distNameParam.isEmpty() && archNameParam.isEmpty() && dockerImageParam.isEmpty()) {
        dependsOn(
            project(":debian-platforms").tasks.named("publishAmd64DebianPublicationToMavenCentralRepository"),
            project(":debian-platforms").tasks.named("publishArm64v8DebianPublicationToMavenCentralRepository"),
            project(":alpine-platforms").tasks.named("publishAmd64AlpinePublicationToMavenCentralRepository"),
            project(":alpine-platforms").tasks.named("publishArm64v8AlpinePublicationToMavenCentralRepository"),
            tasks.named("publishBomPublicationToMavenCentralRepository"),
        )
    } else {
        dependsOn(allprojects.map { it.tasks.named("publish") })
    }
}

tasks.register("publishSnapshotToMavenCentral") {
    group = "publishing"
    dependsOn("publishToMavenCentral")
    doFirst {
        if (!version.toString().endsWith("SNAPSHOT")) {
            throw GradleException("Snapshots must use a version ending with '-SNAPSHOT'")
        }
    }
}

tasks.register("publishReleaseToMavenCentral") {
    group = "publishing"
    dependsOn("publishToMavenCentral")
    doFirst {
        if (version.toString().endsWith("SNAPSHOT")) {
            throw GradleException("Releases must not use a version ending with '-SNAPSHOT'")
        }
    }
}

tasks.register("publishAndReleaseToMavenCentral") {
    group = "publishing"
    dependsOn("publishReleaseToMavenCentral", releaseMavenCentralDeployment)
}

tasks.register("printResolvedVersions") {
    doLast {
        println("resolved_requested_pg_version=$requestedPgVersionParam")
        println("resolved_pg_version=$pgVersionParam")
        println("resolved_pg_bin_version=$pgBinVersionParam")
        println("resolved_requested_postgis_version=$requestedPostgisVersionParam")
        println("resolved_postgis_version=$postgisVersionParam")
        println("resolved_artifact_version=${project.version}")
    }
}

val downloadQemuExecutables = tasks.register("downloadQemuExecutables", Download::class.java) {
    dependsOn(validateInputs)
    onlyIfModified(true)
    onlyIf {
        qemuPathParam.isEmpty() && OperatingSystem.current().isLinux && System.getProperty("os.arch") == "amd64"
    }
    src(listOf("arm", "aarch64", "ppc64le").map { arch ->
        "https://github.com/multiarch/qemu-user-static/releases/download/v2.12.0/qemu-$arch-static"
    })
    overwrite(false)
    dest(file("$temporaryDir/downloads"))
}

val prepareQemuExecutables = tasks.register("prepareQemuExecutables", Copy::class.java) {
    dependsOn(downloadQemuExecutables)
    from(downloadQemuExecutables.map { it.dest })
    into(file("$temporaryDir/executables"))
    doLast {
        fileTree(destinationDir).files.forEach { file ->
            file.setExecutable(true, false)
        }
    }
}

val repackedPlatforms = listOf(
    PlatformDefinition("darwin", "amd64"),
    PlatformDefinition("windows", "amd64"),
    PlatformDefinition("darwin", "arm64v8"),
)

val debianPlatforms = listOf(
    PlatformDefinition("linux", "arm32v7"),
    PlatformDefinition("linux", "arm64v8"),
    PlatformDefinition("linux", "ppc64le"),
    PlatformDefinition("linux", "i386"),
    PlatformDefinition("linux", "amd64"),
)

val alpinePlatforms = listOf(
    PlatformDefinition("linux", "i386"),
    PlatformDefinition("linux", "amd64"),
    PlatformDefinition("linux", "arm32v6"),
    PlatformDefinition("linux", "arm64v8"),
    PlatformDefinition("linux", "ppc64le"),
)

val alpineVariants = listOf(
    AlpineVariantDefinition("", "", true),
)

project(":repacked-platforms") {
    if (distNameParam.isEmpty() && archNameParam.isEmpty() && dockerImageParam.isEmpty() && postgisVersionParam.isEmpty()) {
        repackedPlatforms.forEach { platform ->
            val buildTask = tasks.register("build${platform.arch.cap()}${platform.name.cap()}Bundle", Exec::class.java) {
                group = "build (${platform.arch})"
                dependsOn(validateInputs)

                inputs.property("pgBinVersionParam", pgBinVersionParam)
                inputs.property("platformName", platform.name)
                inputs.property("archName", platform.arch)
                inputs.file(rootDir.resolve("scripts/repack-postgres.sh"))
                outputs.dir(temporaryDir.resolve("bundle"))

                workingDir = temporaryDir
                commandLine("sh", rootDir.resolve("scripts/repack-postgres.sh"), "-v", pgBinVersionParam, "-p", platform.name, "-a", platform.arch)
            }

            val jarTask = tasks.register("${platform.arch}${platform.name.cap()}Jar", Jar::class.java) {
                group = "build (${platform.arch})"
                from(buildTask)
                include("postgres-${platform.name}-${normalizeArchName(platform.arch)}.txz")
                archiveAppendix.set("${platform.name}-${platform.arch}")
            }

            val testTask = tasks.register("test${platform.arch.cap()}${platform.name.cap()}Jar", Exec::class.java) {
                group = "build (${platform.arch})"
                dependsOn(validateInputs, jarTask)
                onlyIf {
                    val system = OperatingSystem.current()
                    val systemArch = System.getProperty("os.arch")
                    gradle.startParameter.taskNames.any { taskName -> taskName.endsWith(name) } ||
                        (system.isLinux && platform.name == "linux" && platform.arch == systemArch) ||
                        (system.isMacOsX && platform.name == "darwin" && platform.arch == systemArch) ||
                        (system.isWindows && platform.name == "windows")
                }

                val qemuBindings = { resolveQemuBindings(prepareQemuExecutables) }
                val dockerImage = { defaultDebianImage(platform.arch, qemuBindings().isNotEmpty()) }

                inputs.property("pgVersion", pgVersionParam)
                inputs.property("platformName", platform.name)
                inputs.property("archName", platform.arch)
                if (platform.name == "linux") {
                    inputs.property("dockerImage", providers.provider { dockerImage() })
                }
                inputs.file(rootDir.resolve("scripts/test-postgres-${platform.name}.sh"))

                workingDir = jarTask.get().destinationDirectory.get().asFile

                if (platform.name == "linux") {
                    configureLazyCommandLine(
                        this,
                        "sh", rootDir.resolve("scripts/test-postgres-${platform.name}.sh"),
                        "-j", "embedded-postgres-binaries-${platform.name}-${platform.arch}-${version}.jar",
                        "-z", "postgres-${platform.name}-${normalizeArchName(platform.arch)}.txz",
                        "-v", pgVersionParam, "-i", { dockerImage() }, "-o", { qemuBindings() },
                    )
                } else {
                    configureLazyCommandLine(
                        this,
                        "sh", rootDir.resolve("scripts/test-postgres-${platform.name}.sh"),
                        "-j", "embedded-postgres-binaries-${platform.name}-${platform.arch}-${version}.jar",
                        "-z", "postgres-${platform.name}-${normalizeArchName(platform.arch)}.txz",
                        "-v", pgVersionParam,
                    )
                }
            }

            registerBundlePublication(
                project,
                "${platform.arch}${platform.name.cap()}",
                "embedded-postgres-binaries-${platform.name}-${platform.arch}",
                jarTask,
                testTask,
            )
        }
    }
}

project(":debian-platforms") {
    if (distNameParam.isEmpty() && archNameParam.isEmpty() && dockerImageParam.isEmpty()) {
        debianPlatforms.forEach { platform ->
            val buildTask = tasks.register("build${platform.arch.cap()}DebianBundle", Exec::class.java) {
                group = "build (${platform.arch})"
                dependsOn(validateInputs, prepareQemuExecutables)
                val qemuBindings = { resolveQemuBindings(prepareQemuExecutables) }
                val dockerImage = { platform.image ?: defaultDebianImage(platform.arch, qemuBindings().isNotEmpty()) }

                doFirst {
                    println("dockerImage:   ${dockerImage()}")
                    println("qemuBindings:  ${qemuBindings()}")
                    println()
                    if (postgisVersionParam.isNotEmpty()) {
                        println("===== Extensions =====")
                        println("postgisVersion: $postgisVersionParam")
                        println("======================")
                        println()
                    }
                }

                inputs.property("pgVersion", pgVersionParam)
                inputs.property("archName", platform.arch)
                inputs.property("dockerImage", providers.provider { dockerImage() })
                inputs.property("postgisVersion", postgisVersionParam)
                inputs.file(rootDir.resolve("scripts/build-postgres-debian.sh"))
                outputs.dir(temporaryDir.resolve("bundle"))

                workingDir = temporaryDir
                configureLazyCommandLine(
                    this,
                    "sh", rootDir.resolve("scripts/build-postgres-debian.sh"),
                    "-v", pgVersionParam, "-i", { dockerImage() }, "-g", postgisVersionParam, "-o", { qemuBindings() },
                )
            }

            val jarTask = tasks.register("${platform.arch}DebianJar", Jar::class.java) {
                group = "build (${platform.arch})"
                from(buildTask)
                include("postgres-linux-debian.txz")
                rename("postgres-linux-debian.txz", "postgres-linux-${normalizeArchName(platform.arch)}.txz")
                archiveAppendix.set("linux-${platform.arch}")
            }

            val testTask = tasks.register("test${platform.arch.cap()}DebianJar", Exec::class.java) {
                group = "build (${platform.arch})"
                dependsOn(validateInputs, prepareQemuExecutables, jarTask)
                val qemuBindings = { resolveQemuBindings(prepareQemuExecutables) }
                val dockerImage = { platform.image ?: defaultDebianImage(platform.arch, qemuBindings().isNotEmpty()) }

                inputs.property("pgVersion", pgVersionParam)
                inputs.property("archName", platform.arch)
                inputs.property("dockerImage", providers.provider { dockerImage() })
                inputs.file(rootDir.resolve("scripts/test-postgres-linux.sh"))

                workingDir = jarTask.get().destinationDirectory.get().asFile
                configureLazyCommandLine(
                    this,
                    "sh", rootDir.resolve("scripts/test-postgres-linux.sh"),
                    "-j", "embedded-postgres-binaries-linux-${platform.arch}-${version}.jar",
                    "-z", "postgres-linux-${normalizeArchName(platform.arch)}.txz",
                    "-i", { dockerImage() }, "-v", pgVersionParam, "-g", postgisVersionParam, "-o", { qemuBindings() },
                )
            }

            registerBundlePublication(
                project,
                "${platform.arch}Debian",
                "embedded-postgres-binaries-linux-${platform.arch}",
                jarTask,
                testTask,
            )
        }
    }
}

alpineVariants.forEach { variant ->
    project(":alpine${if (variant.name.isNotEmpty()) "-${variant.name}" else ""}-platforms") {
        if (distNameParam.isEmpty() && archNameParam.isEmpty() && dockerImageParam.isEmpty() && variant.enabled) {
            alpinePlatforms.forEach { platform ->
                val buildTask = tasks.register("build${platform.arch.cap()}Alpine${variant.name.cap()}Bundle", Exec::class.java) {
                    group = "build (${platform.arch})"
                    dependsOn(validateInputs, prepareQemuExecutables)
                    val qemuBindings = { resolveQemuBindings(prepareQemuExecutables) }
                    val dockerImage = { platform.image ?: defaultAlpineImage(platform.arch, qemuBindings().isNotEmpty()) }

                    doFirst {
                        println("dockerImage:   ${dockerImage()}")
                        println("qemuBindings:  ${qemuBindings()}")
                        println()
                        if (postgisVersionParam.isNotEmpty()) {
                            println("===== Extensions =====")
                            println("postgisVersion: $postgisVersionParam")
                            println("======================")
                            println()
                        }
                    }

                    inputs.property("pgVersion", pgVersionParam)
                    inputs.property("archName", platform.arch)
                    inputs.property("dockerImage", providers.provider { dockerImage() })
                    inputs.property("postgisVersion", postgisVersionParam)
                    inputs.file(rootDir.resolve("scripts/build-postgres-alpine.sh"))
                    outputs.dir(temporaryDir.resolve("bundle"))

                    workingDir = temporaryDir
                    configureLazyCommandLine(
                        this,
                        "sh", rootDir.resolve("scripts/build-postgres-alpine.sh"),
                        "-v", pgVersionParam, "-i", { dockerImage() }, "-g", postgisVersionParam, "-o", { qemuBindings() }, variant.opt,
                    )
                }

                val jarTask = tasks.register("${platform.arch}Alpine${variant.name.cap()}Jar", Jar::class.java) {
                    group = "build (${platform.arch})"
                    from(buildTask)
                    include("postgres-linux-alpine_linux.txz")
                    rename("postgres-linux-alpine_linux.txz", "postgres-linux-${normalizeArchName(platform.arch)}-alpine_linux.txz")
                    archiveAppendix.set("linux-${platform.arch}-alpine${if (variant.name.isNotEmpty()) "-${variant.name}" else ""}")
                }

                val testTask = tasks.register("test${platform.arch.cap()}Alpine${variant.name.cap()}Jar", Exec::class.java) {
                    group = "build (${platform.arch})"
                    dependsOn(validateInputs, prepareQemuExecutables, jarTask)
                    val qemuBindings = { resolveQemuBindings(prepareQemuExecutables) }
                    val dockerImage = { platform.image ?: defaultAlpineImage(platform.arch, qemuBindings().isNotEmpty()) }

                    inputs.property("pgVersion", pgVersionParam)
                    inputs.property("archName", platform.arch)
                    inputs.property("dockerImage", providers.provider { dockerImage() })
                    inputs.file(rootDir.resolve("scripts/test-postgres-alpine.sh"))

                    workingDir = jarTask.get().destinationDirectory.get().asFile
                    configureLazyCommandLine(
                        this,
                        "sh", rootDir.resolve("scripts/test-postgres-alpine.sh"),
                        "-j", "embedded-postgres-binaries-linux-${platform.arch}-alpine${if (variant.name.isNotEmpty()) "-${variant.name}" else ""}-${version}.jar",
                        "-z", "postgres-linux-${normalizeArchName(platform.arch)}-alpine_linux.txz",
                        "-i", { dockerImage() }, "-v", pgVersionParam, "-g", postgisVersionParam, "-o", { qemuBindings() },
                    )
                }

                val artifactId = buildString {
                    append("embedded-postgres-binaries-linux-${platform.arch}-alpine")
                    if (variant.name.isNotEmpty()) {
                        append("-${variant.name}")
                    }
                }
                registerBundlePublication(project, "${platform.arch}Alpine${variant.name.cap()}", artifactId, jarTask, testTask)
            }
        }
    }
}

project(":custom-debian-platform") {
    if (distNameParam.isEmpty() && (archNameParam.isNotEmpty() || dockerImageParam.isNotEmpty())) {
        val archName = if (archNameParam.isNotEmpty()) archNameParam else "amd64"

        val buildTask = tasks.register("buildCustomDebianBundle", Exec::class.java) {
            group = "build (custom)"
            dependsOn(validateInputs, prepareQemuExecutables)
            val qemuBindings = { resolveQemuBindings(prepareQemuExecutables) }
            val dockerImage = { if (dockerImageParam.isNotEmpty()) dockerImageParam else defaultDebianImage(archName, qemuBindings().isNotEmpty()) }

            doFirst {
                println("archName:      $archName")
                println("distName:      debian-like")
                println("dockerImage:   ${dockerImage()}")
                println("qemuBindings:  ${qemuBindings()}")
                println()
                if (postgisVersionParam.isNotEmpty()) {
                    println("===== Extensions =====")
                    println("postgisVersion: $postgisVersionParam")
                    println("======================")
                    println()
                }
            }

            inputs.property("pgVersion", pgVersionParam)
            inputs.property("archName", archName)
            inputs.property("dockerImage", providers.provider { dockerImage() })
            inputs.property("postgisVersion", postgisVersionParam)
            inputs.file(rootDir.resolve("scripts/build-postgres-debian.sh"))
            outputs.dir(temporaryDir.resolve("bundle"))

            workingDir = temporaryDir
            configureLazyCommandLine(
                this,
                "sh", rootDir.resolve("scripts/build-postgres-debian.sh"),
                "-v", pgVersionParam, "-i", { dockerImage() }, "-g", postgisVersionParam, "-o", { qemuBindings() },
            )
        }

        val jarTask = tasks.register("customDebianJar", Jar::class.java) {
            group = "build (custom)"
            from(buildTask)
            include("postgres-linux-debian.txz")
            rename("postgres-linux-debian.txz", "postgres-linux-${normalizeArchName(archName)}.txz")
            archiveAppendix.set("linux-$archName")
        }

        val testTask = tasks.register("testCustomDebianJar", Exec::class.java) {
            group = "build (custom)"
            dependsOn(validateInputs, prepareQemuExecutables, jarTask)
            val qemuBindings = { resolveQemuBindings(prepareQemuExecutables) }
            val dockerImage = { if (dockerImageParam.isNotEmpty()) dockerImageParam else defaultDebianImage(archName, qemuBindings().isNotEmpty()) }

            inputs.property("pgVersion", pgVersionParam)
            inputs.property("archName", archName)
            inputs.property("dockerImage", providers.provider { dockerImage() })
            inputs.property("postgisVersion", postgisVersionParam)
            inputs.file(rootDir.resolve("scripts/test-postgres-linux.sh"))

            workingDir = jarTask.get().destinationDirectory.get().asFile
            configureLazyCommandLine(
                this,
                "sh", rootDir.resolve("scripts/test-postgres-linux.sh"),
                "-j", "embedded-postgres-binaries-linux-$archName-${version}.jar",
                "-z", "postgres-linux-${normalizeArchName(archName)}.txz",
                "-i", { dockerImage() }, "-v", pgVersionParam, "-g", postgisVersionParam, "-o", { qemuBindings() },
            )
        }

        registerBundlePublication(project, "customDebian", "embedded-postgres-binaries-linux-$archName", jarTask, testTask)
    }
}

project(":custom-darwin-platform") {
    if (distNameParam == "darwin") {
        val archName = if (archNameParam.isNotEmpty()) archNameParam else "arm64v8"

        val buildTask = tasks.register("buildCustomDarwinBundle", Exec::class.java) {
            group = "build (custom)"
            dependsOn(validateInputs)
            onlyIf {
                val system = OperatingSystem.current()
                system.isMacOsX && normalizeArchName(System.getProperty("os.arch")) == normalizeArchName(archName)
            }

            doFirst {
                println("archName:      $archName")
                println("distName:      darwin")
                println()
                println("===== Extensions =====")
                println("postgisVersion: $postgisVersionParam")
                println("======================")
                println()
            }

            inputs.property("pgVersion", pgVersionParam)
            inputs.property("archName", archName)
            inputs.property("postgisVersion", postgisVersionParam)
            inputs.file(rootDir.resolve("scripts/build-postgres-darwin.sh"))
            outputs.dir(temporaryDir.resolve("bundle"))

            workingDir = temporaryDir
            configureLazyCommandLine(
                this,
                "bash", rootDir.resolve("scripts/build-postgres-darwin.sh"),
                "-v", pgVersionParam, "-g", postgisVersionParam, "-a", archName,
            )
        }

        val jarTask = tasks.register("customDarwinJar", Jar::class.java) {
            group = "build (custom)"
            from(buildTask)
            include("postgres-darwin-${normalizeArchName(archName)}.txz")
            archiveAppendix.set("darwin-$archName")
        }

        val compatJarTask = tasks.register("compatDarwinAmd64Jar", Jar::class.java) {
            group = "build (custom)"
            from(buildTask)
            include("postgres-darwin-${normalizeArchName(archName)}.txz")
            archiveAppendix.set("darwin-amd64")
        }

        val testTask = tasks.register("testCustomDarwinJar", Exec::class.java) {
            group = "build (custom)"
            dependsOn(validateInputs, jarTask)
            onlyIf {
                val system = OperatingSystem.current()
                system.isMacOsX && normalizeArchName(System.getProperty("os.arch")) == normalizeArchName(archName)
            }

            inputs.property("pgVersion", pgVersionParam)
            inputs.property("archName", archName)
            inputs.property("postgisVersion", postgisVersionParam)
            inputs.file(rootDir.resolve("scripts/test-postgres-darwin.sh"))

            workingDir = jarTask.get().destinationDirectory.get().asFile
            configureLazyCommandLine(
                this,
                "bash", rootDir.resolve("scripts/test-postgres-darwin.sh"),
                "-j", "embedded-postgres-binaries-darwin-$archName-${version}.jar",
                "-z", "postgres-darwin-${normalizeArchName(archName)}.txz",
                "-v", pgVersionParam, "-g", postgisVersionParam,
            )
        }

        registerBundlePublication(project, "customDarwin", "embedded-postgres-binaries-darwin-$archName", jarTask, testTask)
        registerBundlePublication(project, "compatDarwinAmd64", "embedded-postgres-binaries-darwin-amd64", compatJarTask, null)
    }
}

project(":custom-windows-platform") {
    if (distNameParam == "windows") {
        val archName = if (archNameParam.isNotEmpty()) archNameParam else "amd64"
        val bashExecutable = resolveWindowsBashExecutable()
        val buildWindowsScript = if (archName == "arm64v8") {
            rootDir.resolve("scripts/build-postgres-windows-arm64.sh")
        } else {
            rootDir.resolve("scripts/build-postgres-windows-amd64.sh")
        }

        val buildTask = tasks.register("buildCustomWindowsBundle", Exec::class.java) {
            group = "build (custom)"
            dependsOn(validateInputs)

            doFirst {
                println("archName:      $archName")
                println("distName:      windows")
                println()
                println("===== Extensions =====")
                println("postgisVersion: $postgisVersionParam")
                println("======================")
                println()
            }

            inputs.property("pgVersion", pgVersionParam)
            inputs.property("pgBinVersion", pgBinVersionParam)
            inputs.property("archName", archName)
            inputs.property("postgisVersion", postgisVersionParam)
            inputs.file(buildWindowsScript)
            outputs.dir(temporaryDir.resolve("bundle"))

            workingDir = temporaryDir
            if (archName == "arm64v8") {
                configureLazyCommandLine(
                    this,
                    bashExecutable, buildWindowsScript,
                    "-v", pgVersionParam, "-g", postgisVersionParam,
                )
            } else {
                configureLazyCommandLine(
                    this,
                    bashExecutable, buildWindowsScript,
                    "-v", pgVersionParam, "-b", pgBinVersionParam, "-g", postgisVersionParam,
                )
            }
        }

        val jarTask = tasks.register("customWindowsJar", Jar::class.java) {
            group = "build (custom)"
            from(buildTask)
            include("postgres-windows-${normalizeArchName(archName)}.txz")
            archiveAppendix.set("windows-$archName")
        }

        val testTask = tasks.register("testCustomWindowsJar", Exec::class.java) {
            group = "build (custom)"
            dependsOn(validateInputs, jarTask)
            onlyIf {
                OperatingSystem.current().isWindows
            }

            inputs.property("pgVersion", pgVersionParam)
            inputs.property("archName", archName)
            inputs.property("postgisVersion", postgisVersionParam)
            inputs.file(rootDir.resolve("scripts/test-postgres-windows.sh"))

            workingDir = jarTask.get().destinationDirectory.get().asFile
            configureLazyCommandLine(
                this,
                bashExecutable, rootDir.resolve("scripts/test-postgres-windows.sh"),
                "-j", "embedded-postgres-binaries-windows-$archName-${version}.jar",
                "-z", "postgres-windows-${normalizeArchName(archName)}.txz",
                "-v", pgVersionParam, "-g", postgisVersionParam,
            )
        }

        registerBundlePublication(project, "customWindows", "embedded-postgres-binaries-windows-$archName", jarTask, testTask)
    }
}

alpineVariants.forEach { variant ->
    project(":custom-alpine${if (variant.name.isNotEmpty()) "-${variant.name}" else ""}-platform") {
        if (distNameParam == "alpine" && variant.enabled) {
            val archName = if (archNameParam.isNotEmpty()) archNameParam else "amd64"

            val buildTask = tasks.register("buildCustomAlpine${variant.name.cap()}Bundle", Exec::class.java) {
                group = "build (custom)"
                dependsOn(validateInputs, prepareQemuExecutables)
                val qemuBindings = { resolveQemuBindings(prepareQemuExecutables) }
                val dockerImage = { if (dockerImageParam.isNotEmpty()) dockerImageParam else defaultAlpineImage(archName, qemuBindings().isNotEmpty()) }

                doFirst {
                    println("archName:      $archName")
                    println("distName:      alpine")
                    println("dockerImage:   ${dockerImage()}")
                    println("qemuBindings:  ${qemuBindings()}")
                    println()
                    if (postgisVersionParam.isNotEmpty()) {
                        println("===== Extensions =====")
                        println("postgisVersion: $postgisVersionParam")
                        println("======================")
                        println()
                    }
                }

                inputs.property("pgVersion", pgVersionParam)
                inputs.property("archName", archName)
                inputs.property("dockerImage", providers.provider { dockerImage() })
                inputs.property("postgisVersion", postgisVersionParam)
                inputs.file(rootDir.resolve("scripts/build-postgres-alpine.sh"))
                outputs.dir(temporaryDir.resolve("bundle"))

                workingDir = temporaryDir
                configureLazyCommandLine(
                    this,
                    "sh", rootDir.resolve("scripts/build-postgres-alpine.sh"),
                    "-v", pgVersionParam, "-i", { dockerImage() }, "-g", postgisVersionParam, "-o", { qemuBindings() }, variant.opt,
                )
            }

            val jarTask = tasks.register("customAlpine${variant.name.cap()}Jar", Jar::class.java) {
                group = "build (custom)"
                from(buildTask)
                include("postgres-linux-alpine_linux.txz")
                rename("postgres-linux-alpine_linux.txz", "postgres-linux-${normalizeArchName(archName)}-alpine_linux.txz")
                archiveAppendix.set("linux-$archName-alpine${if (variant.name.isNotEmpty()) "-${variant.name}" else ""}")
            }

            val testTask = tasks.register("testCustomAlpine${variant.name.cap()}Jar", Exec::class.java) {
                group = "build (custom)"
                dependsOn(validateInputs, prepareQemuExecutables, jarTask)
                val qemuBindings = { resolveQemuBindings(prepareQemuExecutables) }
                val dockerImage = { if (dockerImageParam.isNotEmpty()) dockerImageParam else defaultAlpineImage(archName, qemuBindings().isNotEmpty()) }

                inputs.property("pgVersion", pgVersionParam)
                inputs.property("archName", archName)
                inputs.property("dockerImage", providers.provider { dockerImage() })
                inputs.property("postgisVersion", postgisVersionParam)
                inputs.file(rootDir.resolve("scripts/test-postgres-alpine.sh"))

                workingDir = jarTask.get().destinationDirectory.get().asFile
                configureLazyCommandLine(
                    this,
                    "sh", rootDir.resolve("scripts/test-postgres-alpine.sh"),
                    "-j", "embedded-postgres-binaries-linux-$archName-alpine${if (variant.name.isNotEmpty()) "-${variant.name}" else ""}-${version}.jar",
                    "-z", "postgres-linux-${normalizeArchName(archName)}-alpine_linux.txz",
                    "-i", { dockerImage() }, "-v", pgVersionParam, "-g", postgisVersionParam, "-o", { qemuBindings() },
                )
            }

            val artifactId = buildString {
                append("embedded-postgres-binaries-linux-$archName-alpine")
                if (variant.name.isNotEmpty()) {
                    append("-${variant.name}")
                }
            }
            registerBundlePublication(project, "customAlpine${variant.name.cap()}", artifactId, jarTask, testTask)
        }
    }
}

if (distNameParam.isEmpty() && archNameParam.isEmpty() && dockerImageParam.isEmpty()) {
    dependencies {
        constraints {
            resolveBomDependencies(project).forEach { archive ->
                api("${project.group}:${archive.artifactId}:${archive.version}")
            }
        }
    }

    extensions.configure<PublishingExtension> {
        publications.create("bom", MavenPublication::class.java) {
            artifactId = "embedded-postgres-binaries-bom"
            from(components["javaPlatform"])
            configurePom(this, artifactId, "Bill of Materials")
        }
    }

    tasks.named("install") {
        dependsOn("publishBomPublicationToMavenLocal")
    }
}
