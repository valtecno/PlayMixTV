# ===========================================================================
# Reglas de R8 (minificación del APK de release)
#
# R8 borra todo el código que no encuentra referenciado. Eso está bien, salvo
# cuando algo se usa por REFLEXIÓN: ahí R8 no ve la referencia y lo elimina, y
# la app compila y arranca pero falla en tiempo de ejecución.
#
# Cada bloque de abajo existe por un motivo concreto de este proyecto.
# Si algún día hay que desactivar la minificación para depurar, alcanza con
# poner isMinifyEnabled = false en app/build.gradle.kts.
# ===========================================================================


# ---------------------------------------------------------------------------
# 1. DECODIFICADOR FFmpeg  ← LA REGLA MÁS IMPORTANTE DEL ARCHIVO
#
# PlayerFactory usa EXTENSION_RENDERER_MODE_PREFER. Con esa opción, Media3
# busca los decodificadores extra con Class.forName("...FfmpegAudioRenderer"),
# es decir por reflexión: NINGUNA línea del proyecto los nombra directamente.
#
# Sin esta regla R8 los da por muertos y los borra. Resultado: el APK de
# release pierde AC-3 / E-AC-3 / DTS y vuelve el clásico "se ve pero no se
# oye" en las películas — y solo en release, así que en depuración parecería
# que todo está bien.
# ---------------------------------------------------------------------------
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-dontwarn androidx.media3.decoder.ffmpeg.**

# Otros decodificadores por extensión que Media3 también resuelve por reflexión.
# No están como dependencia hoy, pero si mañana se agregan (AV1, VP9, Opus...)
# la regla ya está puesta y no hay que volver a diagnosticar lo mismo.
-keep class androidx.media3.decoder.av1.** { *; }
-keep class androidx.media3.decoder.vp9.** { *; }
-keep class androidx.media3.decoder.opus.** { *; }
-keep class androidx.media3.decoder.flac.** { *; }
-dontwarn androidx.media3.decoder.**

# Los decodificadores por software son JNI: el código nativo llama de vuelta a
# estas clases por nombre. Si R8 las renombra, el puente JNI no las encuentra.
-keepclasseswithmembernames class * {
    native <methods>;
}


# ---------------------------------------------------------------------------
# 2. MODELOS DE DATOS (Gson)
#
# Dos usos distintos, los dos rotos por la ofuscación:
#
#   a) Retrofit deserializa las respuestas de Xtream sobre estas clases.
#      Tienen @SerializedName, así que el nombre del campo Java podría
#      renombrarse... pero solo si la anotación sobrevive (ver -keepattributes).
#
#   b) MÁS DELICADO: Favorites e History guardan ContentItem en
#      SharedPreferences con gson.toJson(), SIN @SerializedName. Ahí el JSON
#      usa el nombre real del campo. Si R8 renombra "name" a "a", los
#      favoritos y el historial que el usuario ya tenía guardados dejan de
#      leerse tras actualizar: se pierden en silencio.
#
# Por eso se conserva el paquete api completo. Son ~15 data classes chicas:
# el costo en tamaño es despreciable frente a perder datos del usuario.
# ---------------------------------------------------------------------------
-keep class com.miiptv.app.api.** { *; }
# "Continuar viendo" se guarda con Gson: sin esto R8 renombra los campos y,
# tras una actualización, la lista guardada se leería vacía.
-keep class com.miiptv.app.util.ContinueWatching$Entry { *; }

# c) EL BUG REAL detrás de "no abre la aplicación, se cierra sola" reportado
#    por un usuario (confirmado con el texto exacto que guardó CrashLogger):
#    DataSync.Payload -el blob que se sube/baja de la nube en Mi Espacio- es
#    exactamente el mismo caso que (b) pero se quedó afuera de esta lista.
#    Sin este -keep, R8 puede renombrar sus campos Y, más grave, perder la
#    firma genérica de sus campos List<ContentItem>: Gson entonces no sabe a
#    qué tipo deserializar cada elemento de la lista y arma un LinkedTreeMap
#    en vez de un ContentItem. Eso no se nota al escribir (Gson igual arma
#    algún JSON), pero revienta con ClassCastException apenas algo intenta
#    usar esos elementos como ContentItem -en DataSync.restore(), que corre
#    ANTES de abrir Inicio, así que el proceso entero moría justo después de
#    ingresar el código de acceso, sin llegar nunca a la pantalla principal.
-keep class com.miiptv.app.util.DataSync$Payload { *; }


# NO agregar -keep para Accounts$Account ni Profiles$Profile.
# Se guardan con Gson sin @SerializedName y los datos YA guardados en los
# aparatos usan los nombres de campo ofuscados por R8. Cambiar eso (con un
# -keep) hace que Gson no encuentre ningún campo al leer lo guardado y la app
# se cierra al abrir (pasó en la v1.0.150). Si algún día se quiere protegerlos,
# primero hay que migrar los datos guardados.

# Igual para el modelo de las radios, que también viaja por Gson.
-keep class com.miiptv.app.api.RadioStation { *; }

# Los enums serializados (ContentType) se resuelven por nombre.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Sin esto R8 borra las anotaciones y @SerializedName deja de existir.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# Gson usa TypeToken con genéricos (Favorites/History: List<ContentItem>).
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# Los TypeAdapter propios de XtreamGson se registran a mano; que no se toquen.
-keep class com.miiptv.app.api.XtreamGson { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * extends com.google.gson.TypeAdapter


# ---------------------------------------------------------------------------
# 3. RETROFIT / OkHttp
#
# Retrofit crea las implementaciones de las interfaces por reflexión (Proxy)
# y lee los tipos genéricos de retorno, que la ofuscación borra.
# ---------------------------------------------------------------------------
-keep,allowobfuscation interface com.miiptv.app.api.XtreamApi
-keep,allowobfuscation interface com.miiptv.app.api.RadioApi

-keepattributes Exceptions
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**


# ---------------------------------------------------------------------------
# 4. Picasso
# ---------------------------------------------------------------------------
-dontwarn com.squareup.picasso.**
-dontwarn com.squareup.okhttp.**


# ---------------------------------------------------------------------------
# 5. Android / Kotlin
# ---------------------------------------------------------------------------
# El layout XML instancia vistas personalizadas por nombre de clase.
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# android:onClick="..." resuelve el método por reflexión.
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}

# Parcelables y recursos generados.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Metadatos de Kotlin: los necesita la reflexión de Gson sobre data classes.
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**


# ---------------------------------------------------------------------------
# 6. Diagnóstico
#
# Se conservan los números de línea para que un stack trace de un usuario siga
# siendo legible, pero se borran los nombres de archivo del código fuente.
# El mapa para desofuscar queda en app/build/outputs/mapping/release/.
# ---------------------------------------------------------------------------
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# android.util.Log.d/v desaparecen del APK publicado: no dejan rastro de URLs
# de stream ni de datos de la cuenta en el logcat del dispositivo.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
