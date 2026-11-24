import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.io.FileInputStream
import java.util.Properties

// Change this line to change the installed application ID.
val appId = "io.github.twisteroidambassador.geomys"

// Production signing material lives in the parent project directory, outside this
// repository. See ../SIGNING.md. CI can provide the same values through environment
// variables instead.
val projectRoot = rootProject.projectDir.parentFile
val signingPropertiesFile = projectRoot.resolve("keystore.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.isFile) {
        FileInputStream(signingPropertiesFile).use { load(it) }
    }
}

fun signingValue(propertyName: String, environmentName: String): String? =
    signingProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }
        ?: System.getenv(environmentName)?.takeIf { it.isNotBlank() }

val releaseStoreEntry = signingValue("storeFile", "GEOMYS_KEYSTORE_FILE")
val releaseStoreFile = releaseStoreEntry?.let {
    File(it).let { candidate -> if (candidate.isAbsolute) candidate else projectRoot.resolve(candidate) }
}
val releaseStorePassword = signingValue("storePassword", "GEOMYS_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "GEOMYS_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "GEOMYS_KEY_PASSWORD")
val releaseSigningConfigured = releaseStoreFile?.isFile == true &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()
val unsignedRelease = providers.gradleProperty("unsignedRelease").orNull.toBoolean()

if (!releaseSigningConfigured) {
    logger.warn(
        "Release signing is not configured. Create ${signingPropertiesFile.path} " +
            "or set GEOMYS_KEYSTORE_FILE, GEOMYS_KEYSTORE_PASSWORD, GEOMYS_KEY_ALIAS, " +
            "and GEOMYS_KEY_PASSWORD. Release builds will be unsigned.",
    )
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.geomys"
    compileSdk = 37

    defaultConfig {
        applicationId = appId
        manifestPlaceholders["appId"] = appId
        minSdk = 37
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    signingConfigs {
        getByName("debug") {
            val local = rootProject.file("debug.keystore")
            if (local.exists()) {
                storeFile = local
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (unsignedRelease) null else signingConfigs.findByName("release")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
