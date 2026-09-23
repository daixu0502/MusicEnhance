plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.jaco.musicenhance"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.jaco.musicenhance"
        minSdk = 34
        targetSdk = 37
        versionCode = 43
        versionName = "2.1.5"
    }

    val releaseKeystore = providers.environmentVariable("MUSICENHANCE_KEYSTORE").orNull
    val releaseStorePassword = providers.environmentVariable("MUSICENHANCE_STORE_PASSWORD").orNull
    val releaseKeyAlias = providers.environmentVariable("MUSICENHANCE_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.environmentVariable("MUSICENHANCE_KEY_PASSWORD").orNull
        ?: releaseStorePassword
    if (releaseKeystore != null && releaseStorePassword != null && releaseKeyAlias != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            optimization {
                // AGP 9.3 enables R8 code shrinking, obfuscation and resource optimization together.
                enable = true
            }
            signingConfig = signingConfigs.findByName("release")
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
}

kotlin {
    jvmToolchain(17)
}

// CI reads the resolved Android configuration instead of duplicating version/SDK values in YAML.
val releaseMetadata = mapOf(
    "versionName" to requireNotNull(android.defaultConfig.versionName),
    "versionCode" to requireNotNull(android.defaultConfig.versionCode),
    "compileSdk" to requireNotNull(android.compileSdk),
    "compileSdkMinor" to (android.compileSdkMinor ?: 0),
    "buildToolsVersion" to android.buildToolsVersion,
)
val releaseMetadataFile = layout.buildDirectory.file("release-metadata.json")
tasks.register("writeReleaseMetadata") {
    group = "publishing"
    description = "Exports the app version and SDK requirements for GitHub Releases."
    inputs.properties(releaseMetadata)
    outputs.file(releaseMetadataFile)
    doLast {
        releaseMetadataFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(groovy.json.JsonOutput.toJson(releaseMetadata))
        }
    }
}

// Keep Miuix and the complete Compose runtime family on one locally available version.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group in setOf(
                "androidx.compose.ui",
                "androidx.compose.foundation",
                "androidx.compose.animation",
                "androidx.compose.runtime",
            )
        ) {
            useVersion("1.11.4")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    debugImplementation(libs.androidx.compose.ui.tooling)
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
}
