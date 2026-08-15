plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.miiptv.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.miiptv.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // Media3 marca varias APIs (DefaultLoadControl, resizeMode) como "inestables"
        freeCompilerArgs = freeCompilerArgs + "-opt-in=androidx.media3.common.util.UnstableApi"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Reproductor de video (HLS / TS) - Media3 ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.0")
    // Más contenedores: DASH, SmoothStreaming y RTSP además de HLS/TS/MP4
    implementation("androidx.media3:media3-exoplayer-dash:1.5.0")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:1.5.0")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.5.0")
    implementation("androidx.media3:media3-ui:1.5.0")
    // DefaultHttpDataSource: necesario para fijar el User-Agent del reproductor
    implementation("androidx.media3:media3-datasource:1.5.0")
    // DefaultExtractorsFactory / flags del extractor TS
    implementation("androidx.media3:media3-extractor:1.5.0")

    /*
     * Decodificador FFmpeg por software: agrega AC-3, E-AC-3, DTS, TrueHD, MLP,
     * AMR, ALAC y otros que muchos aparatos no traen por hardware. Es la causa
     * habitual de "se ve pero no se oye" en películas de IPTV.
     *
     * Google no publica este módulo compilado, así que se usa la compilación
     * oficial que mantiene el proyecto Jellyfin. Su versión debe coincidir con
     * la de Media3 (acá 1.5.0).
     *
     * OJO CON LA LICENCIA: este AAR es GPL-3.0. Si distribuís la app con esta
     * dependencia, tenés que ofrecer el código fuente bajo GPL-3.0. Para uso
     * personal no hay problema. Si preferís evitarlo, comentá esta línea: la
     * app compila igual, solo que sin soporte AC-3/DTS por software.
     */
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

    // Networking hacia la API Xtream Codes
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Carga de imágenes (logos de canales)
    implementation("com.squareup.picasso:picasso:2.71828")
}
