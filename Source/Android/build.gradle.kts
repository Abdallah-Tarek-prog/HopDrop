// HopDrop for Android. Build with Source/Android/build.ps1 (-Debug, -Release, -Install), or ./gradlew directly.
// The transfer engine (src/com/hop/drop/core, net, store) is plain Java and is also tested on the desktop JVM by
// build.ps1 -Test / -Interop; the screens are Kotlin + Jetpack Compose (src/com/hop/drop/ui).
plugins {
    id("com.android.application") version "9.4.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    // Screenshot tests: ./gradlew recordRoborazziDebug draws every screen (light, dark, colour themes) into screenshots/.
    id("io.github.takahirom.roborazzi") version "1.76.0"
}

val signingDir = rootDir.resolve("../../Private signing key")

android {
    namespace = "com.hop.drop"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hop.drop"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        manifestPlaceholders["appLabel"] = "HopDrop"
    }

    signingConfigs {
        // The release key stays on the owner's PC ("Private signing key/", never in git). Without it, release builds
        // fall back to the debug key so anyone can still build and install.
        if (signingDir.resolve("hopdrop-release.p12").exists()) {
            create("release") {
                storeFile = signingDir.resolve("hopdrop-release.p12")
                storePassword = signingDir.resolve("signing-password.txt").readText().trim()
                keyAlias = "hopdrop"
                keyPassword = storePassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        // Debug builds install next to the real app, so testing never replaces it or its pairings.
        debug {
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "HopDrop Dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        getByName("test") {
            kotlin.directories.add("uitests")
        }
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.directories.add("src")
            kotlin.directories.add("src")
            res.directories.add("res")
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "DebugProbesKt.bin")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation(files("libs/core-3.5.4.jar"))
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

roborazzi {
    outputDir.set(file("screenshots"))
}
