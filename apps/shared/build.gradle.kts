plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("CatalogDatabase") {
            packageName.set("home.iptv.shared.catalog")
        }
    }
}

kotlin {
    jvmToolchain(21)
    jvm()
    androidTarget()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            api(libs.supabase.auth)
            api(libs.supabase.postgrest)
            api(libs.sqldelight.runtime)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "home.iptv.shared"
    compileSdk = 36
    defaultConfig {
        minSdk = 25
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// Les deux moteurs partagent la fixture racine : ses changements doivent relancer les tests JVM.
tasks.named("jvmTest") {
    inputs.file(rootProject.file("../fixtures/live-groups-v2.json"))
        .withPropertyName("liveGroupsFixture")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
tasks.named("jvmTest") {
    inputs.file(rootProject.file("../fixtures/seasons.json"))
        .withPropertyName("seasonsFixture")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
