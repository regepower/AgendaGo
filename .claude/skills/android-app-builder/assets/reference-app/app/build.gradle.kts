plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.regepower.bootdelay"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.regepower.bootdelay"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    val keystorePath: String? = System.getenv("KEYSTORE_FILE")
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without release secrets, sign with the debug key so the APK stays installable.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // Size: no Kotlin null-check intrinsics, plain StringBuilder concat (measured −1.5 KB on AgendaGo).
            freeCompilerArgs.addAll("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions", "-Xstring-concat=inline")
        }
    }

    // Size (measured): deflate classes.dex in the APK (AGP stores it uncompressed for minSdk >= 28): -116 KB;
    // drop unused Kotlin builtins/metadata resources: -13 KB.
    packaging {
        dex { useLegacyPackaging = true }
        resources {
            excludes += setOf("kotlin/**", "kotlin-tooling-metadata.json", "META-INF/*.version")
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
