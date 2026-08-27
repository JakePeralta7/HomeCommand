// Semantic versioning: update these values for releases
// major.minor.patch - increment patch for bug fixes, minor for features, major for breaking changes
object Versions {
    const val MAJOR = 0
    const val MINOR = 1
    const val PATCH = 3
    val VERSION_NAME = "$MAJOR.$MINOR.$PATCH"
    val VERSION_CODE = (MAJOR * 10000) + (MINOR * 100) + PATCH
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

android {
    namespace = "net.elad.homecommand"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "net.elad.homecommand"
        minSdk = 37
        targetSdk = 37
        versionCode = Versions.VERSION_CODE
        versionName = Versions.VERSION_NAME

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            storeFile = file("homecommand.jks")
            storePassword = "android"
            keyAlias = "homecommand"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // R8 shrinking/obfuscation on: Gson 2.11 consumer rules + @SerializedName on all
            // persisted models keep reflection working; verify with a release-build smoke test.
            optimization {
                enable = true
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging {
        resources {
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.hivemq.mqtt.client)
    implementation(libs.gson)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.fragment)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint()
    }
    kotlinGradle {
        target("*.gradle.kts")
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}
