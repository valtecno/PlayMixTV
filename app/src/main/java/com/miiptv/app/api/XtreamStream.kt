package com.miiptv.app.api

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import java.io.IOException
import java.lang.reflect.Type

/**
 * Lectura en streaming de los tres listados grandes del panel.
 *
 * ---------------------------------------------------------------------------
 * POR QUÉ EXISTE
 *
 * Con el converter normal de Gson, pedir el catálogo de películas hacía esto:
 *
 *   JSON (80 MB)  ->  List<VodStream> (~50 MB)  ->  List<ContentItem> (~50 MB)
 *
 * Las dos listas quedaban vivas al mismo tiempo, porque la segunda se arma
 * recorriendo la primera. En el emulador de PC entra sin problema; en un
 * teléfono real, con el heap recortado y Picasso ocupando su parte, ese pico
 * es justo lo que hace que el bloque más pesado (películas) muera y quede en 0.
 *
 * Acá se lee el JSON de a un objeto por vez y se construye directamente el
 * ContentItem final. Nunca existe la lista intermedia, así que el pico baja a
 * la mitad. Además se reutilizan las cadenas que se repiten (category_id y
 * container_extension son unos pocos valores distintos repartidos entre cien
 * mil registros), lo que ahorra varios MB más.
 *
 * El otro cambio importante: si el panel devuelve un objeto en vez de una
 * lista, esto lanza una excepción con nombre propio en lugar de devolver una
 * lista vacía en silencio. Ese silencio era el motivo de que la app mostrara
 * "0 películas" sin ningún mensaje de error.
 * ---------------------------------------------------------------------------
 */
object XtreamStream {

    /** Tipos envoltorio: Retrofit elige el converter según el tipo de retorno. */
    class LiveList(val items: List<ContentItem>)
    class MovieList(val items: List<ContentItem>)
    class SeriesList(val items: List<ContentItem>)

    /** El panel respondió algo que no es una lista (cuenta vencida, tope de conexiones…). */
    class ShapeException(val token: String) :
        IOException("El panel devolvió $token donde se esperaba una lista")

    object Factory : Converter.Factory() {
        override fun responseBodyConverter(
            type: Type,
            annotations: Array<out Annotation>,
            retrofit: Retrofit
        ): Converter<ResponseBody, *>? = when (type) {
            LiveList::class.java -> converter(ContentType.LIVE) { LiveList(it) }
            MovieList::class.java -> converter(ContentType.MOVIE) { MovieList(it) }
            SeriesList::class.java -> converter(ContentType.SERIES) { SeriesList(it) }
            else -> null
        }

        private fun converter(
            kind: ContentType,
            wrap: (List<ContentItem>) -> Any
        ): Converter<ResponseBody, Any> = object : Converter<ResponseBody, Any> {
            override fun convert(value: ResponseBody): Any = value.use { wrap(parse(it, kind)) }
        }
    }

    // ---------------- Parser ----------------

    private fun parse(body: ResponseBody, kind: ContentType): List<ContentItem> {
        val out = ArrayList<ContentItem>(4096)
        // Cadenas repetidas: en un panel XL hay ~100.000 registros repartidos
        // entre unas pocas decenas de categorías y 3 o 4 extensiones distintas.
        val pool = HashMap<String, String>(256)

        JsonReader(body.charStream()).use { r ->
            r.isLenient = true

            val primero = r.peek()
            if (primero != JsonToken.BEGIN_ARRAY) throw ShapeException(describe(primero))

            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }

                var id = 0
                var name: String? = null
                var icon: String? = null
                var categoryId: String? = null
                var extension: String? = null
                var added = 0L

                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "stream_id", "series_id" -> id = asInt(r)
                        "name" -> name = asString(r)
                        "stream_icon", "cover" -> icon = asString(r)
                        "category_id" -> categoryId = intern(pool, asString(r))
                        "container_extension" -> extension = intern(pool, asString(r))
                        "added", "last_modified" -> added = asLong(r)
                        else -> r.skipValue()
                    }
                }
                r.endObject()

                val titulo = name?.trim()
                if (!titulo.isNullOrBlank()) {
                    out.add(ContentItem(id, titulo, icon, categoryId, kind, extension, added))
                }
            }
            r.endArray()
        }
        out.trimToSize()
        return out
    }

    private fun describe(token: JsonToken): String = when (token) {
        JsonToken.BEGIN_OBJECT -> "un objeto"
        JsonToken.STRING -> "texto suelto"
        JsonToken.NULL -> "nulo"
        JsonToken.BOOLEAN -> "un booleano"
        JsonToken.END_DOCUMENT -> "una respuesta vacía"
        else -> token.name
    }

    private fun intern(pool: HashMap<String, String>, value: String?): String? {
        if (value == null) return null
        return pool.getOrPut(value) { value }
    }

    /** Tolera null, texto, número y booleano en cualquier campo. */
    private fun asString(r: JsonReader): String? = when (r.peek()) {
        JsonToken.NULL -> { r.nextNull(); null }
        JsonToken.BOOLEAN -> if (r.nextBoolean()) "true" else "false"
        JsonToken.NUMBER, JsonToken.STRING -> r.nextString()
        else -> { r.skipValue(); null }
    }

    private fun asInt(r: JsonReader): Int =
        asString(r)?.trim()?.substringBefore('.')?.toIntOrNull() ?: 0

    private fun asLong(r: JsonReader): Long =
        asString(r)?.trim()?.substringBefore('.')?.toLongOrNull() ?: 0L
}
