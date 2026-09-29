package com.miiptv.app.api

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * Gson preparado para lo que devuelven de verdad los paneles Xtream, que no
 * siempre respetan el formato que prometen.
 *
 * Con el Gson por defecto, una sola respuesta rara tira abajo la lista entera
 * y la app muestra "Error cargando contenido" sin más pista. Los tres casos
 * que rompen en la práctica:
 *
 *  1. El panel devuelve un objeto en vez de una lista. Pasa cuando la cuenta
 *     está vencida, cuando se superó el máximo de conexiones o cuando la
 *     categoría no existe: en vez de `[]` llega `{"user_info":{...}}`.
 *     Gson lanza "Expected BEGIN_ARRAY but was BEGIN_OBJECT".
 *  2. Los números llegan como texto, o vacíos: `"stream_id": ""`. Un solo
 *     registro así invalida las 90.000 películas que venían detrás.
 *  3. `get_series_info` devuelve `"episodes": []` (lista vacía) en vez del
 *     objeto por temporadas cuando la serie todavía no tiene episodios.
 *
 * Acá se tratan los tres como "no hay datos" en lugar de como un error fatal.
 */
object XtreamGson {

    val instance: Gson = GsonBuilder()
        .registerTypeAdapterFactory(TolerantCollections)
        .registerTypeAdapter(Int::class.java, TolerantInt)
        .registerTypeAdapter(Int::class.javaObjectType, TolerantInt)
        .registerTypeAdapter(Long::class.java, TolerantLong)
        .registerTypeAdapter(Long::class.javaObjectType, TolerantLong)
        .create()
}

/**
 * Si donde se esperaba una lista o un mapa llega otra cosa, se descarta el
 * valor y se devuelve vacío en lugar de romper toda la respuesta.
 */
private object TolerantCollections : TypeAdapterFactory {

    override fun <T : Any?> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        val raw = type.rawType
        val esLista = List::class.java.isAssignableFrom(raw)
        val esMapa = Map::class.java.isAssignableFrom(raw)
        if (!esLista && !esMapa) return null

        val delegate = gson.getDelegateAdapter(this, type)
        val esperado = if (esLista) JsonToken.BEGIN_ARRAY else JsonToken.BEGIN_OBJECT

        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T) = delegate.write(out, value)

            @Suppress("UNCHECKED_CAST")
            override fun read(reader: JsonReader): T? {
                if (reader.peek() != esperado) {
                    reader.skipValue()
                    return (if (esLista) emptyList<Any?>() else emptyMap<Any?, Any?>()) as T
                }
                return delegate.read(reader)
            }
        }
    }
}

/** Acepta 12, "12", "12.0", "", null y true/false sin lanzar excepción. */
private object TolerantInt : TypeAdapter<Int>() {
    override fun write(out: JsonWriter, value: Int?) {
        if (value == null) out.nullValue() else out.value(value)
    }

    override fun read(reader: JsonReader): Int = when (reader.peek()) {
        JsonToken.NULL -> { reader.nextNull(); 0 }
        JsonToken.BOOLEAN -> if (reader.nextBoolean()) 1 else 0
        JsonToken.NUMBER, JsonToken.STRING ->
            reader.nextString().trim().substringBefore('.').toIntOrNull() ?: 0
        else -> { reader.skipValue(); 0 }
    }
}

private object TolerantLong : TypeAdapter<Long>() {
    override fun write(out: JsonWriter, value: Long?) {
        if (value == null) out.nullValue() else out.value(value)
    }

    override fun read(reader: JsonReader): Long = when (reader.peek()) {
        JsonToken.NULL -> { reader.nextNull(); 0L }
        JsonToken.BOOLEAN -> if (reader.nextBoolean()) 1L else 0L
        JsonToken.NUMBER, JsonToken.STRING ->
            reader.nextString().trim().substringBefore('.').toLongOrNull() ?: 0L
        else -> { reader.skipValue(); 0L }
    }
}
