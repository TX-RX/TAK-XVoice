// Top-level build file. Subproject configuration lives in app/build.gradle.kts.
//
// Build wiring required for the plugin to load on Play Store ATAK:
//   - atak-gradle-takdev plugin on the buildscript classpath
//   - -applymapping in proguard-rules.pro
//   - activity-meta-data MapComponent path in AndroidManifest.xml
//     (no IPlugin extension path)
// Drop any of these and the plugin loader rejects the APK at install.
buildscript {
    fun readLocalProperty(name: String): String? {
        val localProps = java.io.File(rootDir, "local.properties")
        if (!localProps.exists()) return null
        val p = java.util.Properties()
        localProps.inputStream().use { p.load(it) }
        return p.getProperty(name)
    }
    fun prop(name: String): String? =
        ((project.findProperty(name) as? String) ?: readLocalProperty(name))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    val takrepoUrl = prop("takrepo.url") ?: "https://localhost/"
    val takrepoUser = prop("takrepo.user") ?: "invalid"
    val takrepoPassword = prop("takrepo.password") ?: "invalid"
    // Default to the 5.6.0.17 SDK the user has locally — XV targets ATAK 5.6.
    val takdevPlugin = prop("takdev.plugin")
        ?: "$rootDir/../ATAK-CIV-5.6.0.17-SDK/atak-gradle-takdev.jar"
    val isDevKitMode = prop("takrepo.url") != null
    val takdevVersion = "3.+"

    repositories {
        google()
        mavenCentral()
        if (isDevKitMode) {
            maven {
                url = uri(takrepoUrl)
                credentials {
                    username = takrepoUser
                    password = takrepoPassword
                }
            }
        }
    }

    dependencies {
        if (isDevKitMode) {
            classpath("com.atakmap.gradle:atak-gradle-takdev:$takdevVersion")
        } else {
            classpath(files(takdevPlugin))
        }
    }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
    id("com.google.protobuf") version "0.10.0" apply false
}

// =====================================================================
// tppArchive — packages source for the TAK Third-Party Plugin (TPP)
// signing pipeline. TPP requires a single root folder inside the zip;
// the folder name becomes the APK name on the other side, so we use
// "xv".
//
// TPP doesn't accept a single archive that covers multiple ATAK
// baselines — the takdev plugin pulls the SDK + proguard mapping for
// one specific baseline at build time, and the resulting APK is
// (effectively) targeted at that baseline only. So we emit two
// per-baseline source zips on every submission: one for ATAK 5.6 and
// one for ATAK 5.7. The selection is locked inside each archive via
// gradle.properties → `atakBaselineVersion=...`, so TPP's plain
// `./gradlew assembleCivRelease` invocation picks the right SDK
// without needing -P overrides.
//
// Every release ships both flavors. Play Store may push 5.7 to user
// devices and a 5.6-only APK risks load failure due to obfuscated-
// class drift between baselines.
// =====================================================================
val tppExcludes =
    listOf(
        "build",
        "build/**",
        "*/build",
        "*/build/**",
        ".gradle",
        ".gradle/**",
        ".kotlin",
        ".kotlin/**",
        ".idea",
        ".idea/**",
        ".vscode",
        ".vscode/**",
        ".claude",
        ".claude/**",
        ".cxx",
        ".cxx/**",
        ".takdev",
        ".takdev/**",
        ".git",
        ".git/**",
        "out",
        "out/**",
        "release",
        "release/**",
        "captures",
        "captures/**",
        "research",
        "research/**",
        "tools",
        "tools/**",
        // Local diagnostic captures: logcat / dumpsys / batterystats
        // pulls produced during field testing. Gitignored locally but
        // not committed; nothing here is needed by TPP and the dumps
        // routinely run 30-80 MB each, bloating the archive past the
        // portal's upload limits and leaking field-capture context.
        ".logs",
        ".logs/**",
        ".tmp",
        ".tmp/**",
        "diagnostics",
        "diagnostics/**",
        // ENTIRE app/libs — TPP fetches the SDK via takdev. We had
        // the vendored main.jar deliberately listed, but the dir
        // also accumulates loose decompiled .class files from local
        // dev workflows that we must not ship.
        "app/libs",
        "app/libs/**",
        // Secrets / local-only.
        "local.properties",
        "keystore.properties",
        "signing.properties",
        "*.jks",
        "*.keystore",
        "app/atak_keystore",
        // Build artifacts & logs.
        "*.apk",
        "*.aab",
        "*.ap_",
        "*.dex",
        "**/*.class",
        "*.log",
        "hs_err_pid*",
        // OS noise.
        ".DS_Store",
        "Thumbs.db",
        // Any previous archives, if stale ones exist.
        "xv-tpp-source.zip",
        "xv-tpp-source-*.zip",
    )

// Read versionName from app/build.gradle.kts so the TPP zip filename
// carries the version. Lets the operator distinguish 0.1.16 from
// 0.1.15 in the TPP portal upload dialog at a glance, instead of
// inferring from upload date. Falls back to "unversioned" if the
// pattern doesn't match — TPP itself doesn't care about the filename,
// but the operator's "did I upload the right one?" workflow does.
fun readAppVersionName(rootDir: File): String {
    val gradleFile = File(rootDir, "app/build.gradle.kts")
    if (!gradleFile.exists()) return "unversioned"
    val rx = Regex("""versionName\s*=\s*"([^"]+)"""")
    return gradleFile
        .readLines()
        .firstNotNullOfOrNull { rx.find(it)?.groupValues?.get(1) }
        ?: "unversioned"
}

fun Project.registerTppArchive(
    taskName: String,
    baseline: String,
    shortLabel: String,
): TaskProvider<Zip> =
    tasks.register<Zip>(taskName) {
        group = "distribution"
        description = "Build a TPP-ready source zip targeting ATAK $baseline."
        val versionName = readAppVersionName(rootDir)
        archiveFileName.set("xv-tpp-source-$versionName-$shortLabel.zip")
        destinationDirectory.set(layout.buildDirectory.dir("tpp"))

        // Single root folder inside the zip. TPP names the resulting APK
        // after this folder; keep it short and stable.
        val rootName = "xv"

        // Stage every committed source path under <rootName>/, excluding
        // gradle.properties — we re-emit a baseline-specific copy below.
        from(rootDir) {
            into(rootName)
            exclude(tppExcludes + "gradle.properties")
        }

        // Rewrite gradle.properties so the archive carries the right
        // atakBaselineVersion for this variant. TPP's bare
        // `./gradlew assembleCivRelease` honors it without -P overrides.
        from(rootDir) {
            into(rootName)
            include("gradle.properties")
            filter { line ->
                if (line.startsWith("atakBaselineVersion=")) {
                    "atakBaselineVersion=$baseline"
                } else {
                    line
                }
            }
        }

        // Make the wrapper scripts executable inside the archive — TPP
        // builds on Linux and a non-executable gradlew breaks the run.
        filePermissions {
            unix("0644")
        }
        eachFile {
            if (name == "gradlew") permissions { unix("0755") }
        }
        dirPermissions {
            unix("0755")
        }

        doLast {
            logger.lifecycle("TPP source archive (ATAK $baseline): ${archiveFile.get().asFile.absolutePath}")
        }
    }

val tppArchive56 = registerTppArchive("tppArchive56", baseline = "5.6.0", shortLabel = "5.6")
val tppArchive57 = registerTppArchive("tppArchive57", baseline = "5.7.0", shortLabel = "5.7")

// Convenience lifecycle task — `./gradlew tppArchive` produces both
// per-baseline zips in one shot. CI / release workflow points here.
tasks.register("tppArchive") {
    group = "distribution"
    description = "Build TPP source zips for every ATAK baseline (5.6 + 5.7)."
    dependsOn(tppArchive56, tppArchive57)
}
