import com.google.protobuf.gradle.id
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jlleitschuh.gradle.ktlint")
    id("com.google.protobuf")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps =
    Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }

fun readLocalProperty(name: String): String? {
    val localProps = rootProject.file("local.properties")
    if (!localProps.exists()) return null
    val p = Properties()
    localProps.inputStream().use { p.load(it) }
    return p.getProperty(name)
}

fun prop(name: String): String? =
    ((project.findProperty(name) as? String) ?: readLocalProperty(name))
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

val atakBaseline = prop("atakBaselineVersion") ?: "5.6.0"
val devKitMode = prop("takrepo.url") != null

extra["ATAK_VERSION"] = atakBaseline
extra["PLUGIN_VERSION"] = "0.1.0"
extra["takrepoUrl"] = prop("takrepo.url") ?: "https://localhost/"
extra["takrepoUser"] = prop("takrepo.user") ?: "invalid"
extra["takrepoPassword"] = prop("takrepo.password") ?: "invalid"
extra["takdevPlugin"] = prop("takdev.plugin")
    ?: "$rootDir/../ATAK-CIV-5.6.0.17-SDK/atak-gradle-takdev.jar"
// TakDev injects <meta-data android:name="plugin-id"> into the merged
// manifest only when this is set. Without it, ATAK CIV recognises the
// package but refuses to surface a Load button.
extra["takdevMetadataPluginId"] = "XV"

project.extensions.extraProperties.set(
    "isDevKitEnabled",
    object : groovy.lang.Closure<Boolean>(this) {
        @Suppress("unused")
        fun doCall(): Boolean = devKitMode
    },
)

apply(plugin = "atak-takdev-plugin")

// proguard-rules.pro contains `-applymapping <atak.proguard.mapping>` —
// in dev-kit mode the takdev plugin sets the property to ATAK's published
// mapping. In offline mode we point it at an empty no-op file so
// assembleCivRelease can succeed locally for sanity-checking; the
// resulting APK won't be loadable into ATAK (submit through TPP for that).
if (!devKitMode) {
    val emptyMapping =
        layout.buildDirectory
            .file("empty-atak-proguard-mapping.txt")
            .get()
            .asFile
    emptyMapping.parentFile.mkdirs()
    if (!emptyMapping.exists()) {
        emptyMapping.writeText("# empty proguard mapping for offline builds\n")
    }
    System.setProperty("atak.proguard.mapping", emptyMapping.absolutePath)
}

repositories {
    google()
    mavenCentral()
    // JitPack hosts Concentus (pure-Java Opus). Used until we move to
    // native Opus via Humla/wrapped-opus in a later phase.
    maven { url = uri("https://jitpack.io") }
    if (devKitMode) {
        maven {
            url = uri(prop("takrepo.url")!!)
            credentials {
                username = prop("takrepo.user") ?: "invalid"
                password = prop("takrepo.password") ?: "invalid"
            }
        }
    }
}

android {
    namespace = "com.atakmap.android.xv"
    compileSdk = 34

    // Pin to an NDK version pre-installed on the TPP build hosts. TPP
    // refuses to install NDK versions at build time, so the AGP default
    // (which can change between AGP releases) is risky to rely on. The
    // current TPP host list (2026-05) ships:
    //   12.1.2977051, 21.0.6113669, 21.4.7075529, 23.0.7599858, 25.1.8937393
    // 25.1.8937393 is also AGP 8.0–8.7's default, so this matches what
    // we already build against locally — no JNI in XV (Opus is pure-Java
    // via Concentus), so the choice doesn't affect runtime; we only
    // need a value AGP can find on disk.
    ndkVersion = "25.1.8937393"

    defaultConfig {
        // ATAK CIV's plugin loader only surfaces plugins under the
        // com.atakmap.android.* namespace.
        applicationId = "com.atakmap.android.xv.plugin"
        minSdk = 26
        targetSdk = 34
        // BUMP BOTH on every TPP submission. Skipping the bump means
        // TPP / portal listings can't tell the new APK from the
        // previous one, and devices may keep the cached old APK on
        // plugin sync.
        versionCode = 18
        versionName = "0.1.17"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Override with -PatakBaselineVersion=5.7.0 if testing against 5.7.
        manifestPlaceholders["atakApiVersion"] = "com.atakmap.app@$atakBaseline.CIV"
    }

    // AIDL is the cross-process boundary between the plugin (running
    // in ATAK's UID) and XvVoiceService (running in our APK's UID).
    // Required so AudioRecord / SCO / etc happen in OUR UID where the
    // FOREGROUND_SERVICE_TYPE_MICROPHONE privilege actually applies.
    buildFeatures {
        aidl = true
        // BuildConfig.DEBUG gates the DebugReceiver wiring in
        // XvMapComponent so the broadcast surface ships only in
        // debug APKs.
        buildConfig = true
    }

    // Signing: TPP pipeline signs release builds with the Untrusted Plugin
    // Release cert. Local debug uses AGP's debug keystore (won't load on
    // stock ATAK CIV). Drop a keystore.properties to use a real key.
    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig =
                if (keystoreProps.isNotEmpty()) {
                    signingConfigs.getByName("release")
                } else {
                    null
                }
        }
    }

    flavorDimensions += "application"
    productFlavors {
        create("civ") {
            dimension = "application"
        }
    }

    // Unique APK output name for TPP-built release APKs. Without this,
    // every build emits `app-civ-release-unsigned.apk`, so two uploads
    // (different versions, or 5.6 vs 5.7) collide by filename on OTS
    // and the second overwrites the first. Mirrors the convention
    // used by datasync / vns on the same server. Debug intentionally
    // left at AGP's default so adb install / test scripts keep working.
    applicationVariants.all {
        val variant = this
        if (variant.buildType.name != "release") return@all
        outputs.all {
            val variantOutput =
                this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            variantOutput.outputFileName =
                "ATAK-Plugin-xv-${variant.versionName}-$atakBaseline-${variant.flavorName}-release.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes +=
            setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "META-INF/version-control-info.textproto",
                "META-INF/com/android/build/gradle/app-metadata.properties",
            )
        jniLibs.useLegacyPackaging = true
    }

    bundle {
        storeArchive {
            enable = false
        }
    }
}

// Kotlin 2.4 removed the `kotlinOptions { jvmTarget = "17" }` DSL that lived
// inside `android {}`. Configure the JVM target via the top-level `kotlin`
// extension's `compilerOptions`, which is the supported path from KGP 2.0
// onward and is stable through 2.4.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    if (!devKitMode) {
        compileOnly(files("libs/main.jar"))
        // Also visible at unit-test compile time. Several tests need
        // ATAK type symbols in scope (e.g. TakServerDiscovery.TakHost
        // declares a TAKServer field; the test ctor references the
        // field's type even when passing null). Test runtime doesn't
        // load the jar — tests that exercise ATAK runtime classes
        // must use Robolectric and stub or mock the calls.
        testCompileOnly(files("libs/main.jar"))
    }

    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // core-ktx is intentionally held at 1.13.1: the Dependabot bump to 1.19.0
    // demands compileSdk 37 + AGP 9.1.0, which is well outside the scope of a
    // chore(deps) group. Revisit alongside a coordinated AGP/compileSdk bump.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.1")

    // Pure-Java Opus codec. Slow vs native opus but no NDK setup; fine
    // for Phase 1 RX validation. Replace with native build later.
    //
    // Pinned 2026-05-21 (audit L7) to commit 3885c4e46513ef0fc81fca100189e54f1714c6ca
    // (HEAD of master as of 2025-09-27 — the last commit before the
    // Resampler.silk_resampler AssertionError class that drove the
    // ring-buffer disable was already present in this revision, so
    // pinning here doesn't regress on the screech fix). JitPack
    // resolves a commit-hash version by checking out that exact SHA;
    // no more "master-SNAPSHOT moved under us" surprises.
    implementation("com.github.lostromb:concentus:3885c4e46513ef0fc81fca100189e54f1714c6ca")

    // Mumble wire protocol uses protobuf. Lite runtime keeps APK small;
    // we don't need reflection-based features.
    implementation("com.google.protobuf:protobuf-javalite:4.35.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
}

ktlint {
    version.set("1.3.1")
    android.set(true)
    ignoreFailures.set(false)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
    filter {
        exclude("**/generated/**")
    }
}

// takdevLint reads default proguard files when scanning our config; on debug
// builds those files aren't extracted by AGP's normal task graph, so the lint
// throws FileNotFoundException for proguard-android-optimize.txt. Force the
// dependency so any takdevLint invocation extracts proguard files first.
afterEvaluate {
    tasks.findByName("takdevLint")?.dependsOn("extractProguardFiles")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.35.1"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("java") {
                    option("lite")
                }
            }
        }
    }
}
