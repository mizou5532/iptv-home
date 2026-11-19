import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "home.iptv.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "home.iptv.tv"
        minSdk = 25
        targetSdk = 36
        versionCode = 70
        versionName = "0.41.2"
    }
    signingConfigs {
        create("release") {
            val props = Properties().apply { rootProject.file("tv/signature/signing.properties").inputStream().use { load(it) } }
            storeFile = rootProject.file("tv/signature/" + props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            // 0.40 (enquête lenteur) : optimiseur R8. Compose est nettement plus fluide optimisé ; les noms ne sont pas
            // changés (piles lisibles dans les rapports de lenteur) et notre code n'est jamais retiré (proguard-rules.pro).
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        // Fire TV Stick 4K 1re génération (Fire OS 6 = Android 7.1) : java.time n'existe qu'à partir d'Android 8,
        // la bibliothèque standard d'Android l'ajoute à l'app.
        isCoreLibraryDesugaringEnabled = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Tests d'écran (Robolectric + Compose) : ressources et manifeste de l'app disponibles.
        unitTests.isIncludeAndroidResources = true
    }
    buildFeatures {
        compose = true
    }
}

// Android simulé par Robolectric : téléchargé par Gradle (avec ses caches et miroirs), jamais au lancement des tests.
val robolectricSdk: Configuration by configurations.creating

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    robolectricSdk("org.robolectric:android-all-instrumented:15-robolectric-14663667-2-i7")
    implementation(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.tv.material)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.sqldelight.sqlite.driver)
    testImplementation(libs.kotlinx.coroutines.test)
    // Tests d'écran à la télécommande (sans appareil) : Compose UI test sur Robolectric.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    val sdk = robolectricSdk
    inputs.files(sdk)
    doFirst {
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", sdk.singleFile.parentFile.absolutePath)
        // Contrôle visuel des thèmes de saison : -Pseason.shots=<dossier> enregistre une image par fête.
        project.findProperty("season.shots")?.let { systemProperty("season.shots", it.toString()) }
        // Captures de la grille du Sport : -Psport.shots=<dossier>.
        project.findProperty("sport.shots")?.let { systemProperty("sport.shots", it.toString()) }
    }
}
