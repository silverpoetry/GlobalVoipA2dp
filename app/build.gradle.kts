plugins {
    id("com.android.application")
}

val releaseStorePath = providers.environmentVariable("GLOBALVOIPA2DP_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("GLOBALVOIPA2DP_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("GLOBALVOIPA2DP_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("GLOBALVOIPA2DP_KEY_PASSWORD").orNull
val releaseSigningConfigured = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "dev.a2dprouter.system"
    compileSdk = 37
    enableKotlin = false

    defaultConfig {
        applicationId = "dev.a2dprouter.system"
        minSdk = 36
        targetSdk = 36
        versionCode = 10100
        versionName = "1.1.0"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(releaseStorePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            versionNameSuffix = "-debug"
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        // The toolchain is intentionally pinned to the API-102-validated versions.
        disable += setOf("AndroidGradlePluginVersion", "OldTargetApi", "ObsoleteSdkInt")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("com.google.android.material:material:1.14.0")
    testImplementation("junit:junit:4.13.2")
}
