import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { stream ->
        keystoreProperties.load(stream)
    }
}

val envKeystoreFile = providers.environmentVariable("SHHH_KEYSTORE_FILE").orNull?.takeIf { it.isNotBlank() }
val envKeystorePassword = providers.environmentVariable("SHHH_KEYSTORE_PASSWORD").orNull?.takeIf { it.isNotBlank() }
val envKeyAlias = providers.environmentVariable("SHHH_KEY_ALIAS").orNull?.takeIf { it.isNotBlank() }
val envKeyPassword = providers.environmentVariable("SHHH_KEY_PASSWORD").orNull?.takeIf { it.isNotBlank() }

val hasEnvReleaseSigning = envKeystoreFile != null &&
    rootProject.file(envKeystoreFile).exists() &&
    envKeystorePassword != null &&
    envKeyAlias != null &&
    envKeyPassword != null

val hasReleaseSigning = keystorePropertiesFile.exists() || hasEnvReleaseSigning

abstract class GitReleaseTagsValueSource : ValueSource<String, GitReleaseTagsValueSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val repoDirPath: Property<String>
    }

    override fun obtain(): String {
        val path = parameters.repoDirPath.orNull ?: return ""
        val dir = File(path)
        if (!File(dir, ".git").exists()) return ""
        return try {
            val process = ProcessBuilder("git", "tag", "--points-at", "HEAD", "--list", "v*")
                .directory(dir)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            process.outputStream.close()
            val output = StringBuilder()
            val readerThread = Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { reader ->
                        output.append(reader.readText())
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                readerThread.join(1_000)
                return ""
            }
            readerThread.join(2_000)
            if (process.exitValue() == 0) output.toString() else ""
        } catch (_: Throwable) {
            ""
        }
    }
}

data class AppVersionInfo(
    val code: Int,
    val name: String,
    val major: Long = 0L,
    val minor: Long = 0L,
    val patch: Long = 0L,
    val preRelease: String = "",
)

val fallbackAppVersion = AppVersionInfo(code = 1, name = "1.0.0")
val semverTagRegex = Regex(
    "^v(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$"
)

fun compareSemVerPreRelease(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return 1
    if (b.isEmpty()) return -1
    val aParts = a.split('.')
    val bParts = b.split('.')
    val minLen = minOf(aParts.size, bParts.size)
    for (i in 0 until minLen) {
        val pa = aParts[i]
        val pb = bParts[i]
        if (pa == pb) continue
        val aNum = pa.all { it in '0'..'9' }
        val bNum = pb.all { it in '0'..'9' }
        return when {
            aNum && bNum -> {
                val aTrim = pa.trimStart('0')
                val bTrim = pb.trimStart('0')
                if (aTrim.length != bTrim.length) {
                    aTrim.length.compareTo(bTrim.length)
                } else {
                    aTrim.compareTo(bTrim)
                }
            }
            aNum -> -1
            bNum -> 1
            else -> pa.compareTo(pb)
        }
    }
    return aParts.size.compareTo(bParts.size)
}

fun parseReleaseVersionTag(rawTag: String): AppVersionInfo? {
    val tag = rawTag.trim()
    val match = semverTagRegex.matchEntire(tag) ?: return null
    val major = match.groupValues[1].toLongOrNull() ?: return null
    val minor = match.groupValues[2].toLongOrNull() ?: return null
    val patch = match.groupValues[3].toLongOrNull() ?: return null
    val preRelease = match.groupValues[4]

    if (major > 214_748L || minor > 21_474_836L || patch > Int.MAX_VALUE.toLong()) return null
    val rawCode = major * 10_000L + minor * 100L + patch
    if (rawCode > Int.MAX_VALUE.toLong()) return null

    if (minor >= 100L || patch >= 100L) {
        logger.warn(
            "Release tag '{}' has minor/patch >= 100; versionCode ({}) may collide under major*10000+minor*100+patch.",
            tag,
            rawCode
        )
    }

    return AppVersionInfo(
        code = rawCode.coerceAtLeast(1L).toInt(),
        name = tag.removePrefix("v"),
        major = major,
        minor = minor,
        patch = patch,
        preRelease = preRelease,
    )
}

fun resolveAppVersion(): AppVersionInfo {
    val propTag = providers.gradleProperty("versionTag").orNull?.trim()?.takeIf { it.isNotEmpty() }
    val envTag = providers.environmentVariable("SHHH_VERSION_TAG").orNull?.trim()?.takeIf { it.isNotEmpty() }
    val githubRef = providers.environmentVariable("GITHUB_REF").orNull?.trim().orEmpty()
    val githubRefType = providers.environmentVariable("GITHUB_REF_TYPE").orNull?.trim().orEmpty()
    val githubRefName = providers.environmentVariable("GITHUB_REF_NAME").orNull?.trim().orEmpty()
    val githubTag = when {
        githubRef.startsWith("refs/tags/") ->
            githubRef.removePrefix("refs/tags/").trim().takeIf { it.isNotEmpty() }
        githubRefType == "tag" && githubRefName.isNotEmpty() -> githubRefName
        else -> null
    }

    val explicitTag = propTag ?: envTag ?: githubTag
    if (explicitTag != null) {
        return parseReleaseVersionTag(explicitTag) ?: run {
            logger.warn(
                "Ignoring non-semver release tag '{}' and falling back to {} ({}).",
                explicitTag,
                fallbackAppVersion.name,
                fallbackAppVersion.code
            )
            fallbackAppVersion
        }
    }

    val rootDir = rootProject.projectDir
    if (!rootProject.file(".git").exists()) {
        return fallbackAppVersion
    }

    val gitTagsOutput = providers.of(GitReleaseTagsValueSource::class.java) {
        parameters.repoDirPath.set(rootDir.absolutePath)
    }.get()

    return gitTagsOutput
        .lineSequence()
        .mapNotNull(::parseReleaseVersionTag)
        .sortedWith { a, b ->
            b.major.compareTo(a.major).takeIf { it != 0 }
                ?: b.minor.compareTo(a.minor).takeIf { it != 0 }
                ?: b.patch.compareTo(a.patch).takeIf { it != 0 }
                ?: compareSemVerPreRelease(b.preRelease, a.preRelease)
        }
        .firstOrNull() ?: fallbackAppVersion
}

val appVersion = resolveAppVersion()

android {
    namespace = "io.github.wg2038.shhh"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.wg2038.shhh"
        minSdk = 33
        targetSdk = 35
        versionCode = appVersion.code
        versionName = appVersion.name

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = true
        // Work around lifecycle 2.8.0 / Compose 1.7 lint detector crashes under Kotlin 2.0.
        disable += setOf(
            "AutoboxingStateCreation",
            "FrequentlyChangingValue",
            "NullSafeMutableLiveData",
            "RememberInComposition",
        )
    }

    // Read release signing config from keystore.properties or environment variables; fall back to debug key.
    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            } else if (hasEnvReleaseSigning) {
                storeFile = rootProject.file(envKeystoreFile!!)
                storePassword = envKeystorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.0")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.graphics:graphics-shapes:1.0.1")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("com.materialkolor:material-kolor:2.0.0")
    testImplementation("junit:junit:4.13.2")
}
