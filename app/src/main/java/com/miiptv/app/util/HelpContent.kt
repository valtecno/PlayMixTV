package com.miiptv.app.util

import java.text.Normalizer

/**
 * Contenido del asistente de ayuda ("Vanessa"): preguntas frecuentes sobre
 * cómo usar la app, y el buscador que encuentra la respuesta a partir de lo
 * que escribe el usuario.
 *
 * Todo funciona sin internet y sin servicios externos: las respuestas están
 * escritas acá. Para agregar o corregir una, basta con editar [preguntas].
 * `claves` son palabras extra (sinónimos, formas en que la gente pregunta)
 * que no aparecen en el texto pero deberían encontrar esa respuesta.
 *
 * Es código puro, sin Android: el buscador se prueba en HelpContentTest.
 */
object HelpContent {

    enum class Tema(val etiqueta: String) {
        PRIMEROS_PASOS("Primeros pasos"),
        CANALES("Canales y deportes"),
        PELICULAS("Películas y series"),
        FAVORITOS("Favoritos"),
        NINOS("Niños y control parental"),
        REPRODUCTOR("Reproductor"),
        CUENTA("Cuenta y ajustes"),
        PROBLEMAS("Problemas")
    }

    data class Pregunta(
        val tema: Tema,
        val pregunta: String,
        val respuesta: String,
        val claves: List<String> = emptyList()
    )

    /** Número de WhatsApp de soporte (el mismo que usa "Enviar contacto"). */
    const val WHATSAPP_SOPORTE = "56948714030"

    val preguntas: List<Pregunta> = listOf(
        // ---------------- Primeros pasos ----------------
        Pregunta(
            Tema.PRIMEROS_PASOS,
            "¿Cómo me muevo por la app con el control remoto?",
            "Usa las flechas para moverte y OK para elegir. El botón Atrás retrocede de a un paso: " +
                "primero borra lo que escribiste en un buscador, después vuelve a la primera categoría, " +
                "y después al Inicio. Desde el Inicio, Atrás te pregunta si quieres cerrar la app.",
            listOf("control", "mando", "flechas", "navegar", "volver", "atras", "salir")
        ),
        Pregunta(
            Tema.PRIMEROS_PASOS,
            "¿Qué muestra la pantalla de Inicio?",
            "En el Inicio ves las Novedades y lo Agregado recientemente: las últimas películas y series " +
                "que subió el servidor. Van rotando solas; elige una para verla.",
            listOf("inicio", "novedades", "estrenos", "nuevo", "agregados")
        ),
        Pregunta(
            Tema.PRIMEROS_PASOS,
            "¿Cómo busco un canal, una película o una serie?",
            "Toca la lupa de la barra superior y escribe al menos 2 letras. Busca canales, películas y " +
                "series a la vez, y guarda tus búsquedas recientes para repetirlas con un toque.",
            listOf("buscar", "busqueda", "lupa", "encontrar", "donde esta")
        ),
        Pregunta(
            Tema.PRIMEROS_PASOS,
            "¿Qué son Sistema L y Sistema XL?",
            "Son los dos servidores de PlayMix TV. Cada uno tiene su propio catálogo de canales, películas " +
                "y series. El sistema se elige al iniciar sesión; para pasar al otro ve a Cuenta (el ícono " +
                "de persona) → Cambiar de cuenta.",
            listOf("sistema", "servidor", "l", "xl", "cambiar servidor")
        ),
        Pregunta(
            Tema.PRIMEROS_PASOS,
            "¿Cómo cambio entre modo TV y modo celular?",
            "Ve a Cuenta (el ícono de persona) → Modo de pantalla. TV muestra las secciones lado a lado " +
                "y está pensado para control remoto; Móvil las apila y usa grillas más angostas.",
            listOf("modo", "tv", "celular", "movil", "tablet", "pantalla")
        ),

        // ---------------- Canales y deportes ----------------
        Pregunta(
            Tema.CANALES,
            "¿Cómo veo un canal?",
            "Entra a Canales y elige una categoría arriba. Al elegir un canal se reproduce en el mini " +
                "reproductor de la derecha, con lo que están dando ahora y lo que viene. Para verlo en " +
                "pantalla completa, elígelo otra vez o toca Ampliar.",
            listOf("canal", "tv en vivo", "ver canal", "en vivo", "pantalla completa", "ampliar")
        ),
        Pregunta(
            Tema.CANALES,
            "¿Cómo encuentro rápido un canal dentro de Canales?",
            "Usa el cuadro \"Filtrar canales por nombre\" que aparece en Canales: la lista se acota " +
                "mientras escribes. Si no sabes en qué categoría está, usa la lupa de la barra superior.",
            listOf("filtrar", "filtro", "nombre del canal")
        ),
        Pregunta(
            Tema.CANALES,
            "¿Qué hay en Deportes - PPV?",
            "Tiene dos carpetas: Canales, con los canales de deportes, y PPV Eventos, con los eventos " +
                "pagados del momento. En Canales puedes filtrar por deporte (Fútbol, Básquet, Tenis, " +
                "Boxeo / UFC, Fórmula 1...) con los botones de arriba, y el buscador encuentra por evento, " +
                "equipo o canal.",
            listOf("deportes", "ppv", "futbol", "partido", "evento", "ufc", "boxeo", "tenis", "f1", "nba")
        ),
        Pregunta(
            Tema.CANALES,
            "¿Cómo silencio el mini reproductor?",
            "Toca el botón redondo amarillo con el parlante, arriba a la derecha del mini reproductor.",
            listOf("silenciar", "mute", "sonido", "volumen", "vista previa", "mini reproductor")
        ),
        Pregunta(
            Tema.CANALES,
            "¿Cómo pongo un recordatorio de un programa?",
            "Elige el canal y, debajo del mini reproductor, mantén apretado \"A continuación\". Te llega " +
                "una notificación cuando esté por empezar. La hora es aproximada: se calcula desde el " +
                "programa actual.",
            listOf("recordatorio", "alarma", "aviso", "programa", "notificacion", "guia", "epg")
        ),
        Pregunta(
            Tema.CANALES,
            "¿Cómo escucho radios?",
            "Entra a Radios. Arriba eliges la carpeta (Países, Tomorrowland o Electrónica) y debajo el " +
                "país o género. Con los botones de emisora anterior y siguiente pasas de una radio a otra " +
                "sin volver a la lista.",
            listOf("radio", "radios", "musica", "emisora", "tomorrowland", "electronica")
        ),
        Pregunta(
            Tema.CANALES,
            "¿Cómo veo varios canales a la vez?",
            "Usa Multi-pantalla (en la barra superior): muestra 4 canales al mismo tiempo. Toca un " +
                "recuadro para escuchar su audio y mantenlo apretado para cambiar su canal. Ojo: abre 4 " +
                "conexiones a la vez, y algunas cuentas tienen un límite de conexiones simultáneas.",
            listOf("multi", "multipantalla", "varios canales", "ver 4 canales", "cuatro", "pantalla dividida", "a la vez")
        ),

        // ---------------- Películas y series ----------------
        Pregunta(
            Tema.PELICULAS,
            "¿Cómo veo una película?",
            "Entra a Películas, elige una categoría y después la película. Según cómo lo tengas " +
                "configurado, empieza a reproducirse o primero muestra la ficha con sinopsis, reparto y " +
                "director. Se cambia en Cuenta → Personalizar apariencia → Al tocar una película.",
            listOf("pelicula", "peliculas", "ver pelicula", "ficha", "sinopsis")
        ),
        Pregunta(
            Tema.PELICULAS,
            "¿Cómo veo una serie?",
            "Entra a Series, elige la serie, la temporada y el episodio. Al terminar un episodio, el " +
                "siguiente arranca solo si tienes activado \"Reproducir siguiente episodio\" en Cuenta.",
            listOf("serie", "series", "temporada", "capitulo", "episodio", "siguiente episodio")
        ),
        Pregunta(
            Tema.PELICULAS,
            "¿Cómo cambio el idioma del audio o pongo subtítulos?",
            "Mientras miras, usa los botones de pista de audio y de subtítulos del reproductor. Para " +
                "dejar un idioma preferido fijo, ve a Cuenta → Audio: ahí eliges el idioma de audio y los " +
                "subtítulos que se usan al abrir películas y series.",
            listOf("idioma", "audio", "subtitulos", "español", "ingles", "latino", "doblaje")
        ),
        Pregunta(
            Tema.PELICULAS,
            "¿Puedo cambiar cuántas películas o series se ven por fila?",
            "Sí. Ve a Cuenta → Personalizar apariencia y elige las columnas de la vista de películas y " +
                "de series.",
            listOf("columnas", "grilla", "tamaño", "fila", "vista")
        ),

        // ---------------- Favoritos ----------------
        Pregunta(
            Tema.FAVORITOS,
            "¿Cómo agrego algo a Favoritos?",
            "Toca la estrella de la fila del canal, película o serie. Con control remoto, párate encima y " +
                "mantén apretado OK. En Radios, usa el botón Favorito de la emisora.",
            listOf("favorito", "favoritos", "estrella", "guardar", "marcar")
        ),
        Pregunta(
            Tema.FAVORITOS,
            "¿Dónde encuentro mis favoritos?",
            "En la sección Favoritos del menú. Arriba puedes filtrar por Canales, Radios, Películas o " +
                "Series. Para quitar uno, toca de nuevo su estrella.",
            listOf("favoritos", "mis canales", "quitar favorito", "borrar favorito")
        ),

        // ---------------- Niños y control parental ----------------
        Pregunta(
            Tema.NINOS,
            "¿Cómo activo el Perfil de niños?",
            "Toca Niños en el menú. Si todavía no tienes PIN, primero te pide crear uno. Después eliges " +
                "la edad: Hasta 5 años o Hasta 10 años. Mientras está activo solo se ve contenido " +
                "infantil en Canales, Películas y Series, y se ocultan Inicio, Deportes - PPV, Radios y " +
                "Favoritos.",
            listOf("niños", "ninos", "infantil", "hijos", "kids", "perfil", "edad", "dibujos")
        ),
        Pregunta(
            Tema.NINOS,
            "¿Cómo salgo del Perfil de niños?",
            "Toca Salir en el menú (donde antes decía Niños) e ingresa tu PIN.",
            listOf("salir", "desactivar", "quitar perfil", "niños", "ninos")
        ),
        Pregunta(
            Tema.NINOS,
            "¿Cómo bloqueo una categoría con PIN?",
            "Toca el candado de la barra superior (Control parental). Crea un PIN de 4 dígitos si no " +
                "tienes uno y elige las categorías que quieres bloquear. Desde ahí, para entrar a esas " +
                "categorías se pide el PIN.",
            listOf("bloquear", "candado", "pin", "control parental", "clave", "adultos", "proteger")
        ),
        Pregunta(
            Tema.NINOS,
            "Olvidé mi PIN, ¿qué hago?",
            "El PIN queda guardado solo en este aparato y no se puede ver. Escríbenos por WhatsApp con el " +
                "botón de abajo y te ayudamos.",
            listOf("olvide", "pin", "clave", "contraseña", "recuperar")
        ),

        // ---------------- Reproductor ----------------
        Pregunta(
            Tema.REPRODUCTOR,
            "¿Qué puedo hacer desde el reproductor?",
            "Pausar, adelantar y retroceder, elegir pista de audio y subtítulos, subir o bajar el " +
                "volumen, enviar a un Chromecast y bloquear la pantalla para que no se toque por " +
                "accidente (toca dos veces para desbloquear). En series también está el botón Siguiente.",
            listOf("reproductor", "controles", "pausa", "adelantar", "retroceder", "bloquear pantalla")
        ),
        Pregunta(
            Tema.REPRODUCTOR,
            "La imagen se ve cortada o estirada",
            "Ve a Cuenta → Relación de aspecto y prueba Ajustar (se ve completa), Rellenar, Zoom o Estirar.",
            listOf("cortada", "estirada", "aspecto", "bordes", "negro", "zoom", "tamaño imagen")
        ),
        Pregunta(
            Tema.REPRODUCTOR,
            "El video se corta o se queda cargando",
            "Ve a Cuenta → Buffer y elige Alto (conexión inestable): tarda un poco más en empezar pero " +
                "se corta menos. Deja también activado Reconectar automáticamente. Si pasa con un solo " +
                "canal, puede que ese canal esté caído en el servidor: prueba otro.",
            listOf("se corta", "cargando", "buffer", "lento", "congela", "pausa sola", "trabado", "lag")
        ),
        Pregunta(
            Tema.REPRODUCTOR,
            "Se escucha muy bajo o no se escucha",
            "Primero revisa el volumen del reproductor y que el mini reproductor no esté silenciado. En " +
                "Cuenta → Audio está Normalizar volumen del sistema. Si aparece un aviso de audio no " +
                "compatible, elige otra pista con el botón de audio.",
            listOf("bajo", "sin sonido", "no se escucha", "volumen", "audio")
        ),
        Pregunta(
            Tema.REPRODUCTOR,
            "¿Puedo seguir escuchando al salir de la app?",
            "Sí. Activa en Cuenta la opción Seguir sonando en segundo plano.",
            listOf("segundo plano", "fondo", "salir", "musica", "background")
        ),
        Pregunta(
            Tema.REPRODUCTOR,
            "¿Cómo lo veo en el televisor con Chromecast?",
            "En el reproductor toca el botón de Chromecast y elige tu aparato. Después se controla desde " +
                "la app Google Home o desde la notificación.",
            listOf("chromecast", "cast", "transmitir", "televisor", "enviar")
        ),
        Pregunta(
            Tema.REPRODUCTOR,
            "El canal trae un solo idioma",
            "En el botón de pista de audio elige \"Buscar más idiomas (probar otra señal)\". Algunos " +
                "canales traen más idiomas por esa otra vía; el canal se reconecta al probarla.",
            listOf("un idioma", "mas idiomas", "otra señal", "ingles", "español")
        ),

        // ---------------- Cuenta y ajustes ----------------
        Pregunta(
            Tema.CUENTA,
            "¿Cómo actualizo el contenido?",
            "Toca la flecha circular amarilla de la barra superior (Actualizar) o ve a Cuenta → " +
                "Actualizar. Igual, la app baja el catálogo nuevo sola cada madrugada.",
            listOf("actualizar", "refrescar", "nuevo contenido", "recargar", "catalogo")
        ),
        Pregunta(
            Tema.CUENTA,
            "¿Cómo actualizo la aplicación?",
            "La app te avisa sola cuando hay una versión nueva. También puedes buscarla en Cuenta → " +
                "Buscar actualizaciones. La primera vez Android te pide permiso para instalar aplicaciones " +
                "desde PlayMix TV.",
            listOf("version", "actualizacion", "actualizar app", "app", "nueva version", "instalar", "update")
        ),
        Pregunta(
            Tema.CUENTA,
            "¿Cómo cambio de cuenta o agrego otra?",
            "Ve a Cuenta → Cambiar de cuenta. Ahí están las cuentas guardadas (toca una para usarla) y " +
                "la opción Agregar otra cuenta.",
            listOf("cuenta", "otra cuenta", "usuario", "cambiar cuenta", "agregar cuenta")
        ),
        Pregunta(
            Tema.CUENTA,
            "¿Puedo cambiar los colores de la app?",
            "Sí. En Cuenta → Personalizar apariencia eliges el color principal, el tamaño de los " +
                "subtítulos y cómo se ven películas y series.",
            listOf("color", "colores", "tema", "apariencia", "personalizar", "subtitulos grandes")
        ),
        Pregunta(
            Tema.CUENTA,
            "¿Cómo cierro sesión?",
            "Ve a Cuenta → Cerrar sesión, al final de la pantalla.",
            listOf("cerrar sesion", "logout", "salir cuenta")
        ),
        Pregunta(
            Tema.CUENTA,
            "¿Cómo renuevo mi acceso o contacto a soporte?",
            "Escríbenos por WhatsApp con el botón de abajo. La fecha de vencimiento de tu acceso aparece " +
                "en la pantalla de bienvenida.",
            listOf("renovar", "renovar cuenta", "mi cuenta vence", "acceso", "vence", "vencimiento", "pagar", "soporte", "contacto", "whatsapp")
        ),

        // ---------------- Problemas ----------------
        Pregunta(
            Tema.PROBLEMAS,
            "La app no carga el contenido",
            "Revisa que el aparato tenga internet y toca Actualizar en la barra superior. Si sigue igual, " +
                "en Cuenta mantén apretado el recuadro de Actualizar: corre un diagnóstico del servidor " +
                "que puedes copiar y mandarnos por WhatsApp.",
            listOf("no carga", "no carga nada", "nada", "no funciona", "vacio", "pantalla vacia", "sin contenido", "error", "no aparece", "diagnostico")
        ),
        Pregunta(
            Tema.PROBLEMAS,
            "Dice que el panel rechazó la conexión",
            "Tu cuenta tiene un límite de conexiones al mismo tiempo. Pasa si la estás usando en otro " +
                "aparato o si tienes Multi-pantalla abierta. Cierra los otros y vuelve a intentar.",
            listOf("rechazo", "conexiones", "limite", "otro aparato", "simultaneas")
        ),
        Pregunta(
            Tema.PROBLEMAS,
            "Un canal no se reproduce",
            "Puede que ese canal esté caído en el servidor en este momento: prueba con otro o vuelve más " +
                "tarde. Si no se reproduce ninguno, revisa tu internet y el límite de conexiones de tu " +
                "cuenta.",
            listOf("no reproduce", "no se ve", "negro", "error reproducir", "caido", "no abre")
        ),
        Pregunta(
            Tema.PROBLEMAS,
            "No se ven las imágenes o se ven raras",
            "Ve a Cuenta → Vaciar caché de imágenes. Se vuelven a bajar la próxima vez que las veas.",
            listOf("imagenes", "caratulas", "logos", "fotos", "cache", "no cargan imagenes")
        )
    )

    // ---------------- Buscador ----------------

    /** Palabras que no ayudan a encontrar nada ("cómo", "de", "la"...). */
    private val vacias = setOf(
        "como", "que", "el", "la", "los", "las", "lo", "un", "una", "unos", "unas", "de", "del",
        "al", "a", "en", "y", "o", "u", "por", "para", "con", "sin", "mi", "mis", "tu", "tus", "su",
        "se", "me", "te", "es", "son", "esta", "estan", "hay", "puedo", "hago", "hacer", "quiero",
        "cual", "donde", "cuando", "porque", "pero", "si", "ya", "muy", "mas", "hola", "ayuda",
        "necesito", "favor", "no"
    )

    private val DIACRITICOS = Regex("\\p{InCombiningDiacriticalMarks}+")

    fun normalizar(texto: String): String {
        val minusculas = texto.lowercase()
        if (minusculas.all { it.code < 128 }) return minusculas
        return DIACRITICOS.replace(Normalizer.normalize(minusculas, Normalizer.Form.NFD), "")
    }

    private fun palabras(texto: String): List<String> =
        normalizar(texto).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    /**
     * Raíz simple para que "canal" encuentre "canales" y "subtitulo"
     * encuentre "subtitulos": se comparan por el comienzo de la palabra.
     */
    private fun raiz(palabra: String): String = when {
        palabra.length > 6 -> palabra.dropLast(2)
        palabra.length > 4 -> palabra.dropLast(1)
        else -> palabra
    }

    private class Indexada(val p: Pregunta) {
        val enPregunta = palabras(p.pregunta)
        val enClaves = p.claves.flatMap { palabras(it) }
        val enRespuesta = palabras(p.respuesta)
    }

    private val indice by lazy { preguntas.map { Indexada(it) } }

    private fun aparece(raiz: String, en: List<String>): Boolean =
        en.any { it.startsWith(raiz) || (it.length >= 3 && raiz.startsWith(it)) }

    /**
     * Las preguntas que mejor responden a [consulta], de la más a la menos
     * relevante. Cada palabra de la consulta suma más si aparece en la
     * pregunta o en sus claves que si solo aparece en la respuesta. Sin
     * palabras útiles, devuelve todo.
     */
    fun buscar(consulta: String, tema: Tema? = null): List<Pregunta> {
        val candidatas = indice.filter { tema == null || it.p.tema == tema }
        val raices = palabras(consulta)
            .filter { it !in vacias && (it.length >= 2 || it.all(Char::isDigit)) }
            .map { raiz(it) }
            .distinct()
        if (raices.isEmpty()) return candidatas.map { it.p }

        return candidatas
            .map { ix ->
                var puntos = 0
                var encontradas = 0
                for (r in raices) {
                    var suma = 0
                    if (aparece(r, ix.enPregunta)) suma += 3
                    if (aparece(r, ix.enClaves)) suma += 3
                    if (aparece(r, ix.enRespuesta)) suma += 1
                    if (suma > 0) encontradas++
                    puntos += suma
                }
                // Premio a las que responden TODAS las palabras de la consulta
                if (encontradas == raices.size) puntos += 2 * raices.size
                ix.p to puntos
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }
}
