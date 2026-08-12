plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val isEnvironmentGithubCI = providers.environmentVariable("GITHUB_ACTIONS").isPresent
val shouldSkipSigning = providers.environmentVariable("SKIP_SIGNING").orNull?.toBoolean() ?: false

tasks.register("writeVersionForCi") {
    val outputFile = layout.buildDirectory.file("outputs/version.txt")
    outputs.file(outputFile)
    doLast {
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(android.defaultConfig.versionName!!)
        logger.lifecycle("Successfully wrote FMZlinkR versionName to ${file.absolutePath}")
    }
}

android {
    namespace = "com.fumizo07.fmzlinkr"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fumizo07.fmzlinkr"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("ci-release") {
            if (isEnvironmentGithubCI && !shouldSkipSigning) {
                storeFile = file(
                    System.getenv("KEYSTORE_FILE")
                        ?: throw GradleException("Keystore file not provided. env: KEYSTORE_FILE")
                )
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?: throw GradleException("Keystore password not provided. env: KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                    ?: throw GradleException("Key alias not provided. env: KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?: throw GradleException("Key password not provided. env: KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (isEnvironmentGithubCI && !shouldSkipSigning) {
                signingConfig = signingConfigs.getByName("ci-release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)

    implementation(libs.shizukuApi)
    implementation(libs.shizukuProvider)
}
