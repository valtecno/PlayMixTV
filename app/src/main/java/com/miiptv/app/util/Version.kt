package com.miiptv.app.util

/**
 * Comparación de números de versión.
 *
 * Estaba adentro de [Updater], que arrastra Context, Handler, OkHttp y
 * BuildConfig. Acá afuera es código puro de Kotlin: se puede probar en la JVM
 * sin emulador ni dispositivo, que es justo lo que hace VersionTest.
 *
 * Importa que esté bien: si la comparación falla, o la app no ofrece nunca la
 * actualización, o la ofrece en bucle sobre una versión que ya está instalada.
 */
object Version {

    /** "v1.4.2" y "V1.4.2 " son la misma versión que "1.4.2". */
    fun normalize(v: String): String = v.trim().trimStart('v', 'V').trim()

    /**
     * Compara por tramos numéricos: 1.10 es MAYOR que 1.9, cosa que una
     * comparación de texto entendería al revés.
     *
     * Los tramos se separan por "." y por "-", y de cada uno se toman solo los
     * dígitos, así que "1.2.3-rc1" y "1.2.3" comparan igual en los tres
     * primeros tramos. Los tramos que faltan valen 0: "1.2" == "1.2.0".
     *
     * @return negativo si a < b, cero si son iguales, positivo si a > b.
     */
    fun compare(a: String, b: String): Int {
        val pa = a.split('.', '-')
        val pb = b.split('.', '-')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val na = pa.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val nb = pb.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            if (na != nb) return na - nb
        }
        return 0
    }

    /** ¿[publicada] es más nueva que [instalada]? Acepta los dos con o sin "v". */
    fun isNewer(publicada: String, instalada: String): Boolean =
        compare(normalize(publicada), normalize(instalada)) > 0
}
