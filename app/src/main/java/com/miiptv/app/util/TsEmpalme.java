package com.miiptv.app.util;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Empalma las reconexiones de un canal en vivo .ts para que el reproductor
 * vea una sola transmisión continua.
 *
 * Los paneles Xtream cortan la conexión de un canal .ts cada tanto (o la red
 * se cae un instante). Al volver a conectar, el panel no retoma exactamente
 * donde se cortó: reenvía los últimos segundos y empieza en medio de un grupo
 * de imágenes. Pegar eso tal cual hace fallar al decodificador o desfasa audio
 * e imagen; por eso antes la app terminaba la reproducción y la volvía a
 * armar desde cero (corte visible de varios segundos).
 *
 * Esta clase recibe los bytes tal como llegan de cada conexión y entrega:
 *  - solo paquetes TS completos y alineados (una reconexión empieza en cualquier byte);
 *  - cada PES (imagen o trozo de audio) recién cuando está completo, así la
 *    imagen que quedó a medias al cortarse la conexión se descarta;
 *  - tras una reconexión, desde la primera imagen clave que sirve (la última
 *    ya vista, para repetir apenas un instante y no gastar el margen), con el
 *    audio retomado desde ese mismo instante;
 *  - con los tiempos (PTS/DTS/PCR) corridos para que la continuación quede
 *    pegada a lo anterior.
 * Ante cualquier cosa rara se rinde y deja pasar los datos tal cual.
 *
 * Es Java puro (sin Android) para poder probarla con un .ts real en los tests.
 */
public final class TsEmpalme {

  private static final int P = 188;
  private static final long MOD33 = 1L << 33;
  private static final long CLAVE_REPETIDA_MAX = 90000L * 3; // hasta 3 s repetidos
  private static final long ESPERA_MAX_MS = 8000;
  private static final int COLA_MAX_BYTES = 12 * 1024 * 1024;

  private static final Set<Integer> TIPOS_VIDEO = new HashSet<>();
  private static final Set<Integer> TIPOS_AUDIO = new HashSet<>();

  static {
    for (int t : new int[] {0x01, 0x02, 0x10, 0x1b, 0x24, 0x42}) TIPOS_VIDEO.add(t);
    for (int t : new int[] {0x03, 0x04, 0x0f, 0x11, 0x81, 0x87}) TIPOS_AUDIO.add(t);
  }

  // Alineación
  private byte[] resto = null;
  private boolean alineado = false;

  // Tablas
  private int pmtPid = -1;
  private int pidVideo = -1;
  private int tipoVideo = 0;
  private Set<Integer> pidsAudio = new HashSet<>();

  // Tiempos (reloj de 90 kHz)
  private long ultCrudo = -1;     // último PTS de video tal como vino del servidor
  private long ultDtsCrudo = -1;  // último DTS de video tal como vino
  private long ultSalida = -1;    // último DTS de video entregado (ya corrido)
  private long durCuadro = 3600;  // 40 ms; se ajusta con lo observado
  private long desfase = 0;       // lo que se resta a los tiempos de la conexión actual

  // PES que todavía se están armando: pid -> paquetes
  private Map<Integer, List<byte[]>> pendientes = new HashMap<>();

  // Estado de una reconexión en curso
  private boolean activo = false;
  private final List<byte[]> cola = new ArrayList<>();
  private int bytesCola = 0;
  private long desde = 0;
  private long clave = -1;
  private long claveDts = -1;
  private boolean videoListo = false;
  private final Map<Integer, Boolean> audioListo = new HashMap<>();

  /** Para elegir: true = repetir un instante ya visto antes que saltar hacia adelante. */
  public boolean preferirRepetir = true;

  public long desfase() { return desfase; }
  public int pidVideo() { return pidVideo; }
  public boolean enReconexion() { return activo; }

  /** Se llama justo antes de empezar a leer una conexión nueva (no la primera). */
  public void nuevaConexion() {
    resto = null;
    alineado = false;
    pendientes = new HashMap<>(); // lo que quedó a medias en la conexión que se cortó
    if (pidVideo < 0 || ultSalida < 0) return; // todavía no sabemos qué es cada cosa
    activo = true;
    cola.clear();
    bytesCola = 0;
    desde = System.currentTimeMillis();
    clave = -1;
    claveDts = -1;
    videoListo = false;
    audioListo.clear();
  }

  /** Procesa lo recibido y devuelve lo que hay que entregar al reproductor (puede venir vacío). */
  public byte[] procesar(byte[] datos, int off, int len) {
    byte[] d = alinear(datos, off, len);
    if (d.length == 0) return d;
    d = completarPes(d);
    if (d.length == 0) return d;
    return procesarCompleto(d);
  }

  // ---------------- Alineación a paquetes de 188 bytes ----------------

  private byte[] alinear(byte[] datos, int off, int len) {
    byte[] d;
    if (resto != null) {
      d = new byte[resto.length + len];
      System.arraycopy(resto, 0, d, 0, resto.length);
      System.arraycopy(datos, off, d, resto.length, len);
      resto = null;
    } else {
      d = new byte[len];
      System.arraycopy(datos, off, d, 0, len);
    }
    int ini = 0;
    if (!alineado) {
      ini = buscarSincronia(d);
      if (ini < 0) {
        if (d.length < 4 * 1024 * 1024) resto = d;
        return new byte[0];
      }
      alineado = true;
    }
    int util = ((d.length - ini) / P) * P;
    if (ini + util < d.length) {
      resto = new byte[d.length - ini - util];
      System.arraycopy(d, ini + util, resto, 0, resto.length);
    }
    if (ini == 0 && util == d.length) return d;
    byte[] r = new byte[util];
    System.arraycopy(d, ini, r, 0, util);
    return r;
  }

  private static int buscarSincronia(byte[] d) {
    for (int i = 0; i + P * 2 < d.length && i < P * 4; i++) {
      if (d[i] == 0x47 && d[i + P] == 0x47 && d[i + P * 2] == 0x47) return i;
    }
    return -1;
  }

  // ---------------- Lectura de paquetes ----------------

  private static int u(byte[] d, int i) { return d[i] & 0xff; }

  private static int pid(byte[] d, int i) { return ((u(d, i + 1) & 0x1f) << 8) | u(d, i + 2); }

  private static boolean pusi(byte[] d, int i) { return (u(d, i + 1) & 0x40) != 0; }

  private static int afc(byte[] d, int i) { return (u(d, i + 3) >> 4) & 3; }

  private static int largoAf(byte[] d, int i) { return (afc(d, i) & 2) != 0 ? u(d, i + 4) : -1; }

  /** Inicio del payload, o -1 si no hay. */
  private static int payload(byte[] d, int i) {
    int a = afc(d, i);
    int p = (a & 2) != 0 ? i + 5 + u(d, i + 4) : i + 4;
    return (a & 1) != 0 && p < i + P ? p : -1;
  }

  private static long leerTiempo(byte[] d, int q) {
    return ((long) ((u(d, q) >> 1) & 7) << 30)
        | ((long) u(d, q + 1) << 22)
        | ((long) (u(d, q + 2) >> 1) << 15)
        | ((long) u(d, q + 3) << 7)
        | (u(d, q + 4) >> 1);
  }

  private static void escribirTiempo(byte[] d, int q, long v) {
    v = ((v % MOD33) + MOD33) % MOD33;
    d[q] = (byte) ((u(d, q) & 0xf0) | (int) (((v >> 30) & 7) << 1) | 1);
    d[q + 1] = (byte) ((v >> 22) & 0xff);
    d[q + 2] = (byte) ((((v >> 15) & 0x7f) << 1) | 1);
    d[q + 3] = (byte) ((v >> 7) & 0xff);
    d[q + 4] = (byte) (((v & 0x7f) << 1) | 1);
  }

  /** PTS de un paquete que empieza un PES, o -1. */
  private static long ptsDe(byte[] d, int p) {
    if (p < 0 || p + 14 > d.length || d[p] != 0 || d[p + 1] != 0 || d[p + 2] != 1) return -1;
    return (u(d, p + 7) & 0x80) != 0 ? leerTiempo(d, p + 9) : -1;
  }

  private static boolean esImagenClave(byte[] d, int p, int fin, int tipo, boolean rai) {
    if (rai) return true;
    if (p + 9 > fin || d[p] != 0 || d[p + 1] != 0 || d[p + 2] != 1) return false;
    int es = p + 9 + u(d, p + 8);
    for (int k = es; k + 3 < fin; k++) {
      if (d[k] != 0 || d[k + 1] != 0 || d[k + 2] != 1) continue;
      int b = u(d, k + 3);
      if (tipo == 0x1b) {
        int n = b & 0x1f;
        if (n == 5 || n == 7) return true;
      } else if (tipo == 0x24) {
        int n = (b >> 1) & 0x3f;
        if ((n >= 16 && n <= 21) || n == 32 || n == 33) return true;
      } else if (tipo == 0x01 || tipo == 0x02) {
        if (b == 0xb3) return true;
      }
    }
    return false;
  }

  // ---------------- Tablas PAT / PMT ----------------

  private void tablas(byte[] d, int i) {
    if (!pusi(d, i)) return;
    int p = payload(d, i);
    if (p < 0) return;
    int t = p + 1 + u(d, p);
    if (t + 12 > i + P) return;
    int largo = ((u(d, t + 1) & 0x0f) << 8) | u(d, t + 2);
    int fin = Math.min(t + 3 + largo - 4, i + P);
    int id = pid(d, i);
    if (id == 0 && u(d, t) == 0x00) {
      for (int q = t + 8; q + 4 <= fin; q += 4) {
        if (((u(d, q) << 8) | u(d, q + 1)) != 0) {
          pmtPid = ((u(d, q + 2) & 0x1f) << 8) | u(d, q + 3);
          break;
        }
      }
    } else if (id == pmtPid && u(d, t) == 0x02) {
      int q = t + 12 + (((u(d, t + 10) & 0x0f) << 8) | u(d, t + 11));
      Set<Integer> audios = new HashSet<>();
      while (q + 5 <= fin) {
        int tipo = u(d, q);
        int epid = ((u(d, q + 1) & 0x1f) << 8) | u(d, q + 2);
        if (TIPOS_VIDEO.contains(tipo) && pidVideo < 0) {
          pidVideo = epid;
          tipoVideo = tipo;
        } else if (TIPOS_AUDIO.contains(tipo)) {
          audios.add(epid);
        }
        q += 5 + (((u(d, q + 3) & 0x0f) << 8) | u(d, q + 4));
      }
      if (!audios.isEmpty()) pidsAudio = audios;
    }
  }

  // ---------------- PES completos ----------------

  private byte[] completarPes(byte[] d) {
    if (pidVideo < 0) {
      for (int i = 0; i + P <= d.length; i += P) {
        int id = pid(d, i);
        if (id == 0 || id == pmtPid) tablas(d, i);
      }
      return d;
    }
    ByteArrayOutputStream salida = new ByteArrayOutputStream(d.length + P * 8);
    for (int i = 0; i + P <= d.length; i += P) {
      int id = pid(d, i);
      if (id != pidVideo && !pidsAudio.contains(id)) {
        salida.write(d, i, P);
        continue;
      }
      byte[] pkt = new byte[P];
      System.arraycopy(d, i, pkt, 0, P);
      if (pusi(d, i)) {
        List<byte[]> previo = pendientes.get(id);
        if (previo != null) for (byte[] x : previo) salida.write(x, 0, P);
        List<byte[]> nuevo = new ArrayList<>();
        nuevo.add(pkt);
        pendientes.put(id, nuevo);
      } else {
        List<byte[]> previo = pendientes.get(id);
        if (previo != null) previo.add(pkt); // sin inicio conocido: cola de un PES perdido, se descarta
      }
    }
    return salida.toByteArray();
  }

  // ---------------- Tiempos ----------------

  /** Corre los tiempos de un paquete (PCR y PTS/DTS) y anota el último DTS de video. */
  private void pasar(byte[] d, int i) {
    int id = pid(d, i);
    if (id == 0 || id == pmtPid) {
      tablas(d, i);
      return;
    }
    long des = desfase;
    int laf = largoAf(d, i);
    if (des != 0 && laf >= 7 && (u(d, i + 5) & 0x10) != 0) {
      long base = ((long) u(d, i + 6) << 25) | ((long) u(d, i + 7) << 17) | ((long) u(d, i + 8) << 9)
          | ((long) u(d, i + 9) << 1) | (u(d, i + 10) >> 7);
      long v = (((base - des) % MOD33) + MOD33) % MOD33;
      d[i + 6] = (byte) ((v >> 25) & 0xff);
      d[i + 7] = (byte) ((v >> 17) & 0xff);
      d[i + 8] = (byte) ((v >> 9) & 0xff);
      d[i + 9] = (byte) ((v >> 1) & 0xff);
      d[i + 10] = (byte) ((u(d, i + 10) & 0x7f) | (int) ((v & 1) << 7));
    }
    if (!pusi(d, i)) return;
    int p = payload(d, i);
    if (p < 0 || p + 14 > i + P || d[p] != 0 || d[p + 1] != 0 || d[p + 2] != 1) return;
    int flags = u(d, p + 7);
    if ((flags & 0x80) == 0) return;
    long crudo = leerTiempo(d, p + 9);
    boolean hayDts = (flags & 0x40) != 0 && p + 19 <= i + P;
    long dtsCrudo = hayDts ? leerTiempo(d, p + 14) : crudo;
    if (des != 0) {
      escribirTiempo(d, p + 9, crudo - des);
      if (hayDts) escribirTiempo(d, p + 14, dtsCrudo - des);
    }
    if (id == pidVideo) {
      if (ultDtsCrudo >= 0) {
        long dif = dtsCrudo - ultDtsCrudo;
        if (dif >= 900 && dif < 9000 && dif < durCuadro) durCuadro = dif;
      }
      ultCrudo = crudo;
      ultDtsCrudo = dtsCrudo;
      ultSalida = (((dtsCrudo - des) % MOD33) + MOD33) % MOD33;
    }
  }

  // ---------------- Reconexión ----------------

  private byte[] procesarCompleto(byte[] d) {
    if (!activo) {
      for (int i = 0; i + P <= d.length; i += P) pasar(d, i);
      return d;
    }
    if (clave < 0) {
      cola.add(d);
      bytesCola += d.length;
      boolean rendirse = System.currentTimeMillis() - desde > ESPERA_MAX_MS || bytesCola > COLA_MAX_BYTES;
      if (!buscarClave(rendirse)) {
        if (!rendirse) return new byte[0];
        // No apareció una imagen clave: se entrega todo como antes.
        activo = false;
        desfase = 0;
        byte[] todo = unir();
        for (int i = 0; i + P <= todo.length; i += P) pasar(todo, i);
        return todo;
      }
      // La continuación queda pegada a lo último que se vio.
      desfase = claveDts - (ultSalida + durCuadro);
      return filtrar(unir());
    }
    return filtrar(d);
  }

  private byte[] unir() {
    ByteArrayOutputStream o = new ByteArrayOutputStream(bytesCola);
    for (byte[] x : cola) o.write(x, 0, x.length);
    cola.clear();
    bytesCola = 0;
    return o.toByteArray();
  }

  /** Elige la imagen clave desde donde retomar (ver preferirRepetir). */
  private boolean buscarClave(boolean cualquiera) {
    long[] ultima = null;
    long[] nueva = null;
    for (byte[] d : cola) {
      for (int i = 0; i + P <= d.length; i += P) {
        if (!pusi(d, i) || pid(d, i) != pidVideo) continue;
        int p = payload(d, i);
        long pts = ptsDe(d, p);
        if (pts < 0) continue;
        int laf = largoAf(d, i);
        boolean rai = laf > 0 && (u(d, i + 5) & 0x40) != 0;
        if (!esImagenClave(d, p, i + P, tipoVideo, rai)) continue;
        long dts = (u(d, p + 7) & 0x40) != 0 ? leerTiempo(d, p + 14) : pts;
        if (cualquiera && nueva == null && ultima == null) {
          nueva = new long[] {pts, dts};
          continue;
        }
        if (pts > ultCrudo) {
          if (nueva == null) nueva = new long[] {pts, dts};
        } else if (ultCrudo - pts <= CLAVE_REPETIDA_MAX) {
          ultima = new long[] {pts, dts};
        }
      }
    }
    long[] elegida = preferirRepetir && ultima != null ? ultima : nueva;
    if (elegida == null) return false;
    clave = elegida[0];
    claveDts = elegida[1];
    return true;
  }

  /** Deja pasar desde la imagen clave elegida; el audio, desde su mismo instante. */
  private byte[] filtrar(byte[] d) {
    ByteArrayOutputStream salida = new ByteArrayOutputStream(d.length);
    byte[] pkt = new byte[P];
    for (int i = 0; i + P <= d.length; i += P) {
      int id = pid(d, i);
      boolean pasa = true;
      if (id == pidVideo) {
        if (!videoListo) {
          if (pusi(d, i) && ptsDe(d, payload(d, i)) == clave) videoListo = true;
          pasa = videoListo;
        }
      } else if (pidsAudio.contains(id)) {
        Boolean listo = audioListo.get(id);
        if (listo == null || !listo) {
          if (pusi(d, i)) {
            long pts = ptsDe(d, payload(d, i));
            listo = pts >= 0 && pts >= clave && pts - clave < 90000L * 10;
            audioListo.put(id, listo);
          }
          pasa = listo != null && listo;
        }
      }
      if (pasa) {
        System.arraycopy(d, i, pkt, 0, P);
        pasar(pkt, 0);
        salida.write(pkt, 0, P);
      }
    }
    boolean audiosListos = true;
    for (int id : pidsAudio) if (!Boolean.TRUE.equals(audioListo.get(id))) audiosListos = false;
    if ((videoListo && audiosListos) || System.currentTimeMillis() - desde > 15000) activo = false;
    return salida.toByteArray();
  }
}
