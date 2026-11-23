plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.parques.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.parques.client"
        minSdk = 24
        targetSdk = 34
        // En GitHub Actions el número de ejecución sube en cada build: la app lo compara con el del último Release
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = build
        versionName = "1.$build"
    }
    // Firma fija (no la «debug» que se genera distinta en cada ejecución de Actions): así Android deja instalar
    // cada versión nueva encima de la anterior, sin desinstalar. Es un proyecto de clase: la clave va en el repo.
    signingConfigs {
        getByName("debug") {
            storeFile = file("parques.keystore")
            storePassword = "parques123"
            keyAlias = "parques"
            keyPassword = "parques123"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // WebSocket
}
