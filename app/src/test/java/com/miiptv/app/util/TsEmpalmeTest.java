package com.miiptv.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Simula un canal .ts en vivo cuyo panel corta la conexión dos veces: al
 * reconectar reenvía segundos ya vistos y empieza desalineado, en medio de un
 * grupo de imágenes. Lo que sale de TsEmpalme tiene que ser una sola
 * transmisión continua: paquetes alineados, tiempos de video siempre
 * crecientes y sin saltos, y cada continuación empezando en una imagen clave.
 */
public class TsEmpalmeTest {

  private static final int P = 188;

  private static byte[] leerRecurso() throws IOException {
    try (InputStream in = TsEmpalmeTest.class.getClassLoader().getResourceAsStream("vivo_prueba.ts")) {
      ByteArrayOutputStream o = new ByteArrayOutputStream();
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) > 0) o.write(b, 0, n);
      return o.toByteArray();
    }
  }

  /** Pasa [ini, fin) del archivo en trozos de tamaño irregular, como llega por la red. */
  private static void alimentar(TsEmpalme e, byte[] src, int ini, int fin, ByteArrayOutputStream salida) {
    int trozo = 7 * P + 13;
    for (int i = ini; i < fin; i += trozo) {
      byte[] r = e.procesar(src, i, Math.min(trozo, fin - i));
      salida.write(r, 0, r.length);
    }
  }

  private static long tiempo(byte[] d, int q) {
    return ((long) (((d[q] & 0xff) >> 1) & 7) << 30) | ((long) (d[q + 1] & 0xff) << 22)
        | ((long) ((d[q + 2] & 0xff) >> 1) << 15) | ((long) (d[q + 3] & 0xff) << 7) | ((d[q + 4] & 0xff) >> 1);
  }

  @Test
  public void empalmaReconexionesSinSaltosNiRepeticionesDeTiempo() throws IOException {
    byte[] src = leerRecurso();
    int paquetes = src.length / P;
    TsEmpalme e = new TsEmpalme();
    ByteArrayOutputStream salida = new ByteArrayOutputStream();

    alimentar(e, src, 0, (paquetes / 3) * P, salida);
    e.nuevaConexion(); // reenvía ~1/6 del archivo ya visto y llega desalineado
    alimentar(e, src, (paquetes / 6) * P + 77, (2 * paquetes / 3) * P, salida);
    e.nuevaConexion(); // se perdió un tramo
    alimentar(e, src, (3 * paquetes / 4) * P + 31, paquetes * P, salida);

    byte[] out = salida.toByteArray();
    assertEquals("la salida son paquetes completos", 0, out.length % P);
    int pidVideo = e.pidVideo();
    assertTrue("se reconoció el video", pidVideo > 0);

    List<Long> dts = new ArrayList<>();
    for (int i = 0; i + P <= out.length; i += P) {
      assertEquals("cada paquete empieza con 0x47", 0x47, out[i] & 0xff);
      int pid = ((out[i + 1] & 0x1f) << 8) | (out[i + 2] & 0xff);
      boolean pusi = (out[i + 1] & 0x40) != 0;
      if (pid != pidVideo || !pusi) continue;
      int afc = ((out[i + 3] & 0xff) >> 4) & 3;
      int p = (afc & 2) != 0 ? i + 5 + (out[i + 4] & 0xff) : i + 4;
      if (out[p] != 0 || out[p + 1] != 0 || out[p + 2] != 1) continue;
      int flags = out[p + 7] & 0xff;
      dts.add((flags & 0x40) != 0 ? tiempo(out, p + 14) : tiempo(out, p + 9));
    }
    assertTrue("hay imágenes", dts.size() > 100);
    for (int k = 1; k < dts.size(); k++) {
      long dif = dts.get(k) - dts.get(k - 1);
      assertTrue("el tiempo de video avanza sin repetirse ni saltar (" + k + ": " + dif + ")",
          dif > 0 && dif < 90000 / 5);
    }
  }

  @Test
  public void sinReconexionLosDatosPasanIntactos() throws IOException {
    byte[] src = leerRecurso();
    int largo = (src.length / P) * P;
    TsEmpalme e = new TsEmpalme();
    ByteArrayOutputStream salida = new ByteArrayOutputStream();
    alimentar(e, src, 0, largo, salida);
    byte[] out = salida.toByteArray();
    // Solo pueden faltar los últimos PES (todavía "a medio armar") al final.
    assertTrue(out.length <= largo && out.length > largo * 0.95);
    assertEquals(0, e.desfase());
  }
}
