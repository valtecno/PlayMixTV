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
        // El workflow de publicación las pasa desde el tag (-PversionName=1.2.3),
        // así la versión que muestra la app coincide con la Release de GitHub.
        versionCode = (project.findProperty("versionCode") as String? ?: "1").toInt()
        versionName = project.findProperty("versionName") as String? ?: "1.0"

        // Repositorio desde el que la app busca actualizaciones.
        // Se define en gradle.properties para no tocar código al cambiarlo.
        buildConfigField(
            "String",
            "GITHUB_REPO",
            "\"" + (project.findProperty("playmix.repo") as String? ?: "") + "\""
        )

        /*
         * Lista de servidores (ver gradle.properties → playmix.servers).
         *
         * Antes estaba escrita dentro de Servers.kt. Al pasarla acá:
         *  - agregar o mover un servidor no toca ni una línea de Kotlin;
         *  - se puede sobreescribir en la compilación con -Pplaymix.servers=...,
         *    así las direcciones reales no tienen por qué quedar en el repo.
         *
         * El escapado importa: el valor lleva "|" y ";" pero también podría
         * llevar comillas, y esto termina inyectado tal cual dentro de un
         * literal de Java en BuildConfig.
         */
        buildConfigField(
            "String",
            "SERVERS",
            "\"" + (project.findProperty("playmix.servers") as String? ?: "")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\""
        )
    }

    /*
     * Firma de publicación.
     *
     * Android solo permite actualizar una app si el APK nuevo está firmado con
     * la MISMA clave que el instalado. GitHub Actions genera un keystore de
     * depuración distinto en cada ejecución, así que sin esto la actualización
     * descargaría bien y luego fallaría con "aplicación no instalada".
     *
     * Las credenciales llegan por variables de entorno desde los secrets del
     * repositorio. Si no están (compilación local), se ignora y se firma con la
     * clave de depuración de siempre.
     */
    val keystoreFile = System.getenv("PLAYMIX_KEYSTORE")?.let { file(it) }
    storeFile = file(System.getenv("PLAYMIX_KEYSTORE") ?: "playmix-release.keystore");storePassword = System.getenv("PLAYMIX_STORE_PASSWORD");keyAlias = System.getenv("PLAYMIX_KEY_ALIAS") ?: "playmix";keyPassword = System.getenv("PLAYMIX_STORE_PASSWORD") } }

    buildTypes {
        release {
            /*
             * R8: borra el código y los recursos que nadie usa, y renombra lo
             * que queda. Sobre este proyecto son unos 25-40% menos de APK,
             * porque Media3 + Retrofit + Gson + Picasso traen muchísimo más de
             * lo que la app realmente toca.
             *
             * Lo que R8 NO puede adivinar es lo que se usa por reflexión: los
             * modelos que viajan por Gson y el decodificador FFmpeg que Media3
             * carga por nombre. Todo eso está protegido en proguard-rules.pro,
             * con el motivo escrito al lado de cada regla.
             *
             * Si alguna vez hay un fallo que solo aparece en release, el primer
             * paso para descartarlo es poner estas dos líneas en false.
             */
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            if (keystoreFile != null && keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        debug {
            // Depuración siempre sin minificar: compila más rápido y los stack
            // traces salen con los nombres reales.
            isMinifyEnabled = false
            isShrinkResources = false
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
        // Necesario para BuildConfig.GITHUB_REPO y BuildConfig.SERVERS (AGP 8 lo pide explícito)
        buildConfig = true
    }

    testOptions {
        unitTests {
            /*
             * Los tests corren en la JVM, sin emulador. android.jar es un stub:
             * cualquier método de Android lanza "Not mocked" al llamarse. Con
             * esto devuelven el valor por defecto en vez de explotar, lo que
             * evita tener que envolver todo en Robolectric para probar lógica
             * que en realidad no toca Android.
             */
            isReturnDefaultValues = true
        }
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
     *
     * OJO CON R8: este módulo se carga por reflexión. Ver la regla número 1 de
     * proguard-rules.pro; sin ella el release se queda mudo.
     */
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

    // Networking hacia la API Xtream Codes
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Carga de imágenes (logos de canales)
    implementation("com.squareup.picasso:picasso:2.71828")

    // ---- Tests de JVM (./gradlew test) ----
    testImplementation("junit:junit:4.13.2")
}
