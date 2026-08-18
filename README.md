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

### Desde la terminal
El proyecto trae el **wrapper de Gradle**, así que no hace falta tener Gradle
instalado: el propio script descarga la versión exacta (8.7) la primera vez.

```bash
./gradlew test            # tests unitarios (segundos, sin emulador)
./gradlew assembleDebug   # APK de depuración
./gradlew assembleRelease # APK de publicación (minificado con R8)
```

En Windows es `gradlew.bat` en vez de `./gradlew`.

> `gradle/wrapper/gradle-wrapper.jar` **tiene que estar en el repositorio**. Sin
> ese archivo `./gradlew` no arranca en una máquina limpia ni en GitHub Actions.
> El `.gitignore` lo protege explícitamente.

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
La app NO trae credenciales precargadas — al abrirla por primera vez pide
**usuario** y **contraseña** de la cuenta Xtream Codes, y con qué sistema
conectarse. Esos datos se guardan solo en el dispositivo (SharedPreferences).

La URL nunca se le muestra ni se le pide al usuario: elige "Sistema L" o
"Sistema XL" y listo.

### Editar la lista de servidores

Se edita en **`gradle.properties`**, propiedad `playmix.servers`. No hay que
tocar ni una línea de Kotlin, y la pantalla de login se adapta sola a la
cantidad de servidores que haya (uno, dos o cinco).

```properties
playmix.servers=\
  l|Sistema L|http://xdplayer.tv:8080|cinema hd hq|sudamericano,sudamericana|vod estrenos,estrenos;\
  xl|Sistema XL|http://moontools.site:8080|cinema latino|chile primera|2026
```

Un servidor por bloque, separados por `;`, con los campos separados por `|`:

| Campo | Qué es |
|---|---|
| `id` | Clave interna estable. **No la cambies una vez publicada**: es lo que usa el código para reconocer al servidor. |
| `etiqueta` | Lo único que ve el usuario. |
| `url` | Base del panel Xtream, sin barra final. |
| `preferidas_canales` | Carpetas que se abren primero en Canales (separadas por comas). |
| `preferidas_ppv` | Ídem para PPV. |
| `preferidas_peliculas` | Ídem para Películas. |

Los últimos tres son opcionales. Una entrada mal escrita se descarta sola sin
tumbar a las demás (hay tests que lo verifican).

**Para no dejar las direcciones en un repositorio público:** borrá el valor de
`gradle.properties` y cargalo como secret `PLAYMIX_SERVERS` en GitHub. El
workflow de publicación lo detecta y lo pasa con `-Pplaymix.servers=...`.

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

## 6.1.1 Manejo con control remoto (modo TV)

**Cuándo se activa.** Se deriva del modo elegido en la primera pantalla, no es
un ajuste aparte:

| Situación | Control remoto |
|---|---|
| Eligió **TV** | Activo, y **queda guardado** en el dispositivo |
| Eligió **Móvil** | Inactivo: se usa el dedo |
| Todavía no eligió | Se decide por hardware, así que en un televisor **funciona desde el primer arranque** |

La detección mira tres señales, no una: que el aparato se declare televisor,
que traiga la interfaz de Android TV (`FEATURE_LEANBACK`), o que **no tenga
pantalla táctil**. Esta última salva a los decos baratos, que a veces no
declaran ninguna de las dos primeras y solo se manejan con remoto.

La pantalla de elección es la excepción: ahí el foco se resalta **siempre**,
sin consultar nada. Es la única donde equivocarse deja al usuario encerrado —
sin táctil y sin ver el foco, no hay forma de llegar hasta "TV" y confirmar.

**Cómo se ve el foco.** El elemento sobre el que está el control remoto se pinta
con un **lavado difuminado** del color de acento (el que el usuario eligió en
Personalizar): un degradado que va de opaco a casi transparente, para que la
carátula o el logo se sigan viendo por debajo, más un borde sólido y un leve
relieve. Antes no se veía nada: los fondos se asignaban con un color plano, sin
estado enfocado, y `selectableItemBackground` sobre una tarjeta de vidrio oscuro
es invisible en un televisor.

Cubre Películas, Series, PPV, Radios, Canales, Historial, Favoritos y el
buscador (todo pasa por `ContentAdapter`), más los chips de categoría, los
países de Radios, el menú superior y la pantalla de login.

**Favoritos en TV.** Con el remoto la tarjeta se enfoca entera: la estrella deja
de robarse el foco, que era lo que convertía la grilla en un laberinto (derecha
te llevaba a la estrella de la misma película en vez de a la de al lado). A
cambio, **pulsación larga del botón central** marca o desmarca el favorito.

## 6.1.2 Imagen de marca en el Inicio (solo móvil)

En **modo móvil** el Inicio muestra una imagen de marca a pantalla completa en
lugar de los carruseles. En **modo TV** no cambia nada: siguen las dos columnas
de "Novedades" y "Agregado recientemente" rotando como siempre.

Va traslúcida (`alpha` 0.35) y con un velo que la funde con el fondo de la app
por arriba y por abajo, para que no compita con el menú ni con la barra de
título.

**Dos garantías que no dependen del código.** La imagen vive dentro de
`homeArea`, que en el layout es hermano del menú y va *después* de él en el
LinearLayout vertical raíz. O sea:

1. Empieza físicamente debajo del menú → no puede taparlo nunca.
2. `homeArea` entero se oculta al salir del Inicio → no puede asomarse en otra
   sección.

Ninguna de las dos depende de que un `if` se acuerde de apagarla.

**Cambiar la imagen:** reemplazá `app/src/main/res/drawable-nodpi/home_backdrop.jpg`.
Conviene formato vertical (la actual es 768×1376) y JPEG, no PNG: la misma
imagen pesa 140 KB en JPEG contra 1 MB en PNG.

**Cambiar cuánto se ve:** `android:alpha` del `ImageView` con id `ivHomeBackdrop`
en `activity_main.xml`. Referencia: 0.20 apenas insinuada · 0.35 actual ·
0.60 bien presente.

## 6.1.3 Intro y menú superior

### Intro al abrir
La intro pasó de **2200 ms a 900 ms**, y sobre todo dejó de ser tiempo muerto:
mientras se ve, **ya se está bajando el catálogo**. Antes eso arrancaba recién
al abrir el Inicio, o sea después de la intro; ahora las dos cosas pasan a la
vez y el Inicio suele estar listo cuando la animación termina.

La animación también es más suave: el logo entra al 88% de su tamaño en vez del
60%, así se asienta en lugar de saltar, con un resplandor que se abre y se apaga
detrás. Se sale con un fundido en vez de un corte seco, y se puede tocar la
pantalla para saltearla (antes eso solo funcionaba con el video).

Si existe `res/raw/intro.mp4` se reproduce ese video y nada de esto aplica.

### Menú superior en móvil
Quedan **tres iconos**: actualizar, lupa y engranaje. El engranaje reemplaza a
los tres puntos, y detrás de él aparecen **candado** (control parental),
**multipantalla** y **cuenta**, en fila y sin texto.

Los tres puntos no hay que ocultarlos: al no quedar ningún ítem fuera de la
barra, Android deja de dibujarlos solo.

Un icono sin etiqueta se entiende igual, pero hay que dar la forma de
averiguarlo: cada uno lleva `contentDescription` para lectores de pantalla y un
mensaje emergente al mantenerlo pulsado.

**En modo TV no cambia nada**: siguen los cinco iconos sueltos en la barra. En
una pantalla ancha entran de sobra, y con control remoto esconder cosas detrás
de un menú agrega pulsaciones en vez de ahorrarlas.

## 6.1.4 Radios en carpetas

La sección pasó de una fila única a **dos niveles**:

```
🌎 Países  ·  🎪 Tomorrowland  ·  🎛️ Electrónica
└─ 🇪🇸 España · 🇺🇸 EE.UU. · 🇲🇽 México · 🇧🇷 Brasil · ...
```

Antes era una sola fila con España, Loca FM, Tomorrowland y doce países
seguidos: marcas y lugares mezclados en la misma línea, y había que desplazarse
un buen rato para llegar al final.

**Países** se abre por defecto. **Tomorrowland** queda igual que estaba; al
tener una sola fuente, no muestra la segunda fila (no hay nada que elegir).
**Electrónica** agrupa Loca FM con cinco géneros: House, Techno, Trance, Dance
y Electrónica general.

Los géneros se buscan por **etiqueta** del directorio, no por nombre. Para eso
se agregó `byTag` a `RadioApi`: una emisora de techno casi nunca se llama
"techno", y buscar por nombre traería cualquiera que tenga esa palabra suelta.
La etiqueta la carga la comunidad de Radio Browser y describe lo que la emisora
realmente pincha.

Las listas de marca (Loca FM, Tomorrowland) se ordenan alfabéticamente y se
limpian de repetidos, porque ahí funcionan como un índice de estilos. Países y
géneros no: ahí el orden por votos es información útil, las más escuchadas
primero.

**Agregar una carpeta o un género** es editar `RadioCatalog.folders`. No hay que
tocar `MainActivity`: las dos filas se dibujan solas a partir de esa lista.

### Favorito en el reproductor de radio
Al lado de "Inicio" hay ahora un botón de favorito con etiqueta, que se rellena
con el color de acento y cambia a "Guardada" cuando la emisora está marcada.
Hace lo mismo que la estrella de la barra superior, que en una tele es diminuta
y queda lejos de la mano. Los dos se mantienen sincronizados y siguen a la
emisora que suena al pasar de una a otra.

## 6.1.5 Iconos

Todo sale de `logo_PM.png`. El script que los genera está descrito acá para
poder rehacerlos si cambia el logo.

### Escritorio del móvil (icono adaptativo)
El `ic_launcher_foreground.png` que había medía **192 px** en su densidad más
alta. Ese es el tamaño de un icono *legacy*; uno adaptativo se dibuja sobre un
lienzo de 108dp, o sea **432 px** en xxxhdpi. Android lo estaba ampliando x2,25
en cada pantalla, y de ahí venía el aspecto pixelado.

Además llenaba el lienzo entero. De los 108dp solo se garantizan los 72dp
centrales, y con máscara circular (la del lanzador de Pixel) los vértices del
triángulo se perdían.

Ahora:

| | Antes | Ahora |
|---|---|---|
| Tamaño real | 192 px | 432 px (mdpi 108 · hdpi 162 · xhdpi 216 · xxhdpi 324) |
| Fondo | color plano | degradado diagonal del proyecto (`ic_launcher_background.xml`) |
| Máscara circular | recortaba el logo | entra completo |
| Android 13+ | — | versión monocroma para iconos temáticos |

El logo ocupa 55,3dp de los 108. Ese número **no es a ojo**: se midió el radio
real de los píxeles sólidos del logo y se calculó el máximo que entra en el
círculo de 34dp de radio que toda máscara respeta. Verificado después:
0 píxeles sólidos recortados. Por eso se ve algo más chico que antes — antes se
veía más grande porque se salía del área segura.

Si lo querés más grande asumiendo que en lanzadores circulares se recorten los
bordes, es una constante en el script (`ALTO_LOGO_DP`).

### Android TV (banner)
`android:banner` apuntaba al icono **cuadrado** del escritorio. Por eso PlayMix
TV aparecía como un cuadradito con el nombre debajo, mientras Prime Video, TLTV
y YouTube mostraban una tarjeta ancha.

Ahora hay un banner propio de **320×180** (`drawable-xhdpi/banner.png`, el
tamaño que pide Google) más una versión de 480×270 en xxhdpi para pantallas
grandes: fondo con el degradado de la app, dos halos cruzados en naranja y rosa,
y el logo centrado al 80% del alto.

No lleva texto añadido: el logo ya trae "play mix TV" con su tipografía. Un
segundo texto en otra fuente se notaría.

## 6.1.6 Foco en modo TV: reproductor, Cuenta y grillas

### Reproductor
Los botones del reproductor son el peor caso de toda la app: flotan sobre el
video, que puede ser de cualquier color y estar en movimiento, y su único fondo
era `selectableItemBackgroundBorderless` — un destello pensado para el dedo, que
en un televisor no se ve.

Ahí el resalte es **más fuerte** que en las listas: relleno **opaco** del color
de acento, anillo blanco de 3dp y la vista un 18% más grande. Se ve igual sobre
una escena negra que sobre una nevada.

Cubre la barra superior (favorito, audio, subtítulos, calidad, encuadre, buffer,
bloqueo), el zapping, desbloquear, el aviso de siguiente episodio, los tres
botones de la barra de radio y los controles centrales — retroceder, reproducir
y adelantar, que los infla Media3 y se resaltan recorriendo el árbol en vez de
depender de los ids internos de la librería.

Los botones con estilo `NavItem` (Volver, Inicio, Favorito de radio) ya cambiaban
de color al enfocarse; ahora además **se levantan**. En una tele, a tres metros,
el color solo se nota poco: el movimiento se ve enseguida.

### Cuenta y Personalizar
Estas pantallas se arman con filas que comparten un `style` con fondo fijo, sin
estado enfocado: moverse por ellas con el mando no cambiaba un solo píxel.

Se resuelve **recorriendo el árbol de vistas**, no listando ids: una fila que se
agregue mañana hereda el resalte sin tocar nada. El recorrido además:

- **Bloquea el foco de los hijos** en las filas con interruptor. Antes el
  interruptor se lo robaba y había que pasar dos veces por cada fila; la fila
  entera ya lo alterna al pulsarla.
- **Saca del recorrido las filas que no hacen nada.** El estilo marca todas las
  filas como enfocables, incluidas las que solo muestran un dato (servidor,
  usuario). Con el mando obligaban a pasar por ellas sin resultado, y al
  iluminarse prometerían una acción que no existe.

### Grillas en TV: 6 columnas
Películas y Series arrancan en **6 columnas** en TV (antes 4). En una pantalla
de 40 pulgadas o más, 4 carátulas quedaban enormes y obligaban a desplazarse
mucho; a esa distancia el título se lee igual. En móvil siguen siendo 2.

Es solo el valor inicial: quien ya eligió su densidad en Personalizar conserva
la suya.

## 6.1.7 Búsqueda con carátula · PIN · favorito de radio

### Carátula en los resultados de búsqueda
La búsqueda usaba la fila de canales: un cuadrado de 48dp pensado para el logo
de una emisora, donde un póster de película se veía diminuto y con franjas.

Ahora tiene fila propia (`item_search_result.xml`) con miniatura **vertical** de
54×74dp y esquinas redondeadas, más una etiqueta que dice si es canal, película
o serie — buscando "Titanic" pueden salir las tres cosas y antes no había forma
de distinguirlas sin abrirlas.

El recorte depende del tipo: las carátulas van con `centerCrop` (son verticales
como el hueco, lo llenan sin deformarse) y los logos de canal con `fitCenter`
(son apaisados; con `centerCrop` se les comerían los costados).

De paso se corrigió que `loadImage` no cancelaba la descarga anterior al
reciclar una fila: si la nueva no tenía imagen, la descarga vieja terminaba
después y pintaba la carátula sobre la fila equivocada.

### PIN con control remoto
El estilo `PinKey` traía estado `pressed` pero no `focused`: con el dedo se veía
al tocar, con el mando había que escribir el PIN **a ciegas**, contando
posiciones. Es de las peores pantallas donde puede pasar, porque un dígito mal
solo se nota en que el PIN no funciona.

Ahora las teclas se resaltan al enfocarse y el foco arranca en el **5**, desde
donde se llega a cualquier dígito en dos pulsaciones.

### Botón de favorito de radio: por qué aparecía a veces
No era del botón nuevo: el de la barra superior tenía el mismo problema desde
antes.

`readFavoriteItem()` comprobaba `if (id < 0) return`, usando el −1 por defecto de
`getIntExtra` como señal de "no vino el dato". Pero los ids de las emisoras
salen de `String.hashCode()` (ver `RadioCatalog`), y un hashCode es **negativo
más o menos la mitad de las veces**.

O sea que en una emisora de cada dos, un id perfectamente válido se confundía
con "falta el dato", `favoriteItem` quedaba en null y los dos botones
desaparecían. Y como al cambiar de emisora se hace `favoriteItem?.copy(...)`, un
null seguía siendo null: no se recuperaba ni pasando a la siguiente.

Se cambió por `intent.hasExtra(...)`, que responde exactamente lo que hay que
preguntar sin reservarse ningún valor. **Los ids guardados no cambian**, así que
los favoritos que ya existan se siguen reconociendo.

## 6.2 Calidad del proyecto

### Tests
```bash
./gradlew test
```
Corren en la JVM, en segundos, sin emulador ni dispositivo. Cubren la lógica
que puede fallar **en silencio**, que es la peligrosa:

| Suite | Qué protege |
|---|---|
| `VersionTest` | Comparación de versiones del actualizador. Si se rompe, o la app nunca avisa que hay versión nueva, o avisa en bucle sobre una ya instalada. Incluye el caso clásico `1.10 > 1.9`. |
| `ServersTest` | El parseo de `playmix.servers`. Un error de tipeo en `gradle.properties` no debe dejar la app sin servidores. |
| `PpvFilterTest` | Qué categorías entran en PPV Fútbol. Cada palabra nueva en las listas puede meter o sacar carpetas enteras sin que se note. |
| `KidsFilterTest` | Perfil de niños. Un falso positivo mete contenido no apto: los tests fijan que las exclusiones ganan sobre las palabras infantiles. |
| `ModelsTest` | Mapeo Xtream → `ContentItem`, incluida la conversión del campo `added` que ordena "Novedades" y que llega como texto (a veces vacío). |

El workflow de publicación **no publica si los tests están en rojo**.

### Minificación (R8)
El APK de release va minificado y ofuscado (`isMinifyEnabled` +
`isShrinkResources`). Las reglas están en `app/proguard-rules.pro`, cada una con
el motivo escrito al lado. Las dos que no se pueden tocar:

1. **Decodificador FFmpeg.** Media3 lo carga por reflexión, así que ninguna línea
   del proyecto lo nombra y R8 lo borraría. Sin la regla vuelve el "se ve pero no
   se oye" — y solo en release, así que en depuración parecería que todo anda.
2. **Modelos de `api/`.** `Favorites` e `History` guardan `ContentItem` en
   SharedPreferences con los nombres reales de los campos. Si R8 los renombra,
   los favoritos y el historial del usuario se pierden al actualizar.

El mapa para desofuscar stack traces queda en
`app/build/outputs/mapping/release/`; el workflow lo guarda como artifact
privado (90 días), no lo adjunta a la Release pública.

> Si aparece un fallo que **solo** pasa en release, el primer paso para
> descartarlo es poner `isMinifyEnabled = false` en `app/build.gradle.kts`.

## 7. Próximos pasos posibles
- Pantallas de VOD (películas) y Series (la API Xtream también las soporta:
  `get_vod_categories`, `get_vod_streams`, `get_series`, etc.)
- Favoritos / EPG (guía de programación)
- Interfaz optimizada para control remoto de Android TV (Leanback)
- Splash con video/animación en vez de imagen estática

Decime cuál de estos querés y seguimos construyendo sobre esta base.
