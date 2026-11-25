plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "space.owork.draw.renderer"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
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
}

dependencies {
    api(project(":engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.graphics.core)
    implementation(libs.androidx.input.motionprediction)

    testImplementation(libs.junit)
}
