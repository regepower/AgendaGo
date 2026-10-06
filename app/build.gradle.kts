import java.security.KeyStore

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.regepower.agendago"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.regepower.agendago"
        // 31: RemoteViews.setViewLayoutWidth / setColor / RemoteCollectionItems and dynamic colors.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    // Release key from CI secrets. The alias is optional: without KEY_ALIAS the first alias in
    // the keystore is used, so the same keystore as other apps works without extra setup.
    val keystorePath: String? = System.getenv("KEYSTORE_FILE")
    if (keystorePath != null) {
        val storePw = System.getenv("KEYSTORE_PASSWORD").orEmpty()
        val alias =
            System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }
                ?: KeyStore.getInstance(KeyStore.getDefaultType()).run {
                    file(keystorePath).inputStream().use { load(it, storePw.toCharArray()) }
                    aliases().nextElement()
                }
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = storePw
                keyAlias = alias
                // keytool's default PKCS12 keystores use the store password for the key.
                keyPassword = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: storePw
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Without secrets: debug key, so the APK stays installable.
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
        }
    }

    // Deflate classes.dex and drop Kotlin metadata nobody reads at runtime (measured in the
    // android-app-builder skill).
    packaging {
        dex { useLegacyPackaging = true }
        resources {
            excludes += setOf("kotlin/**", "kotlin-tooling-metadata.json", "META-INF/*.version")
        }
    }

    lint {
        abortOnError = true
        textReport = true
        warningsAsErrors = false
    }
}

// No dependencies on purpose: framework APIs only (RemoteViews, JobScheduler, CalendarContract, views).
