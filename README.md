# Mi IPTV — App personalizada (Android)

App nativa en Kotlin para conectarte a tu servidor **Xtream Codes** privado.
Incluye: intro/splash con logo, login, listado de categorías y canales en vivo,
y reproductor (Media3 ExoPlayer, soporta HLS/TS). Funciona en celular y en
Android TV (aparece en el launcher de TV gracias al filtro LEANBACK_LAUNCHER).

## 1. Requisitos
- [Android Studio](https://developer.android.com/studio) (versión reciente, Koala o superior)
- JDK 17 (Android Studio ya lo trae embebido)

## 2. Cómo abrir el proyecto
1. Abre Android Studio → **Open** → selecciona la carpeta `MiIPTV`.
2. Deja que Gradle sincronice (puede tardar unos minutos la primera vez, descarga dependencias).
3. Conecta un celular Android (o usa un emulador) y presiona **Run ▶**.

## 3. Personalizar con TU marca

### Nombre de la app
Edita `app/src/main/res/values/strings.xml`:
```xml
<string name="app_name">TuNombreAquí</string>
```

### Logo / ícono
Reemplaza `app/src/main/res/drawable/ic_launcher.xml` por tu logo real.
Lo más fácil: en Android Studio, clic derecho en `res` → **New → Image Asset**,
sube tu PNG/SVG y te genera automáticamente todos los tamaños e íconos
(incluyendo el adaptive icon). Eso reemplaza este placeholder.

### Colores de marca
Edita `app/src/main/res/values/colors.xml` (color primario, acento, fondo).

### Intro / Splash
Por defecto, la app muestra un **intro animado por código**: tu logo aparece
con fade + zoom, y debajo el nombre **"PlayMix TV"** se desliza con fade
(2.2 segundos, sin depender de ningún archivo externo).

Si preferís usar un **video** en su lugar, agregá tu archivo en:
```
app/src/main/res/raw/intro.mp4
```
- El nombre debe ser exactamente `intro.mp4` (minúsculas, sin espacios ni guiones).
- Formato recomendado: MP4 (H.264 + AAC), corto (2-5 segundos).
- Apenas exista ese archivo, la app lo reproduce automáticamente en vez de la
  animación (tocar la pantalla durante el video lo salta).
- Si vas a usar un video, asegurate de que el texto/logo que aparezca sea el
  tuyo (por ejemplo, edita cualquier plantilla reemplazando el texto de
  ejemplo por "PlayMix TV" antes de usarla) y que tengas los derechos del
  material (grabado por vos, comprado con licencia, o de bancos libres).

## 4. Conectar tu servidor privado
La app NO trae credenciales precargadas — al abrirla por primera vez pide:
- **URL del servidor**: ej. `http://tuservidor.com:8080` (sin barra final)
- **Usuario** y **Contraseña** de tu cuenta Xtream Codes

Estos datos se guardan localmente en el dispositivo (SharedPreferences).

> Si en vez de Xtream Codes usas una lista M3U simple, decime y te agrego
> una segunda pantalla de login que solo pida la URL del .m3u — es un módulo aparte.

## 5. Estructura del proyecto
```
app/src/main/java/com/miiptv/app/
 ├─ api/
 │   ├─ Models.kt        → estructuras de datos de la API
 │   ├─ XtreamApi.kt      → definición de los endpoints (Retrofit)
 │   └─ Session.kt        → guarda credenciales y arma URLs de stream
 └─ ui/
     ├─ SplashActivity.kt → intro/logo
     ├─ LoginActivity.kt  → pantalla de conexión al servidor
     ├─ MainActivity.kt   → categorías + lista de canales
     ├─ ChannelAdapter.kt → adaptador del listado
     └─ PlayerActivity.kt → reproductor de video
```

## 6. Publicar la app (opcional)
Para generar el `.apk` firmado o subirla a Google Play:
`Build → Generate Signed Bundle / APK` en Android Studio.
Ten en cuenta que Google Play tiene políticas estrictas sobre apps IPTV:
deben dejar claro que el usuario aporta su propio servicio/contenido y no
deben facilitar contenido pirata. Si tu contenido es legal (tu propia
programación, cámaras propias, contenido con licencia, etc.) estás en regla;
si no, lo más simple es distribuir el APK directamente (sideload) sin pasar
por la tienda.

## 6.1 Funciones agregadas

- **Películas y Series (VOD)**: pestañas dedicadas en la pantalla principal, con
  categorías, y para series un detalle con temporadas/episodios.
- **Buscador**: ícono de lupa en la barra superior, busca en vivo + películas +
  series a la vez (`SearchActivity.kt`).
- **Favoritos**: tocá la estrella en cualquier ítem para guardarlo; aparecen en
  la pestaña "Favoritos" (persisten en el dispositivo, `util/Favorites.kt`).
- **Control parental**: ícono de candado en el menú → crear un PIN y elegir qué
  categorías bloquear. Al abrir una categoría bloqueada o sus ítems, pide el PIN
  (`util/Parental.kt`, `ParentalSettingsActivity.kt`).
- **Multi-pantalla**: ícono en la barra superior abre una grilla de 4 canales en
  vivo simultáneos. Tocar un recuadro le da el audio; mantener presionado deja
  elegir qué canal va ahí (`MultiScreenActivity.kt`).

## 7. Próximos pasos posibles
- Pantallas de VOD (películas) y Series (la API Xtream también las soporta:
  `get_vod_categories`, `get_vod_streams`, `get_series`, etc.)
- Favoritos / EPG (guía de programación)
- Interfaz optimizada para control remoto de Android TV (Leanback)
- Splash con video/animación en vez de imagen estática

Decime cuál de estos querés y seguimos construyendo sobre esta base.
