<?php
/**
 * sync.php — Copia de seguridad de Favoritos/Continuar viendo/Historial
 * de PlayMix TV, un JSON por cuenta.
 *
 * Sube esto a la MISMA carpeta donde ya está api.php (el que valida el
 * código de acceso), por ejemplo:
 *   https://valtecno.cl/disc/paneltv/sync.php
 *
 * No requiere base de datos: cada cuenta se guarda como un archivo de texto
 * plano dentro de la carpeta "datos_sync" (se crea sola la primera vez). Si
 * el hosting ya tiene MySQL y se prefiere usar eso, lo único que hay que
 * conservar son los dos casos de uso de más abajo (leer/guardar un string
 * por "cuenta"); el resto es intercambiable.
 *
 * Uso (mismo estilo que api.php, sin autenticación aparte: "cuenta" ya es
 * server+usuario del panel Xtream, que solo conoce quien ya inició sesión):
 *
 *   GET  sync.php?accion=obtener&cuenta=XXXX
 *        -> {"status":"ok","datos":"<json tal cual se guardó, o null>"}
 *
 *   POST sync.php   (application/x-www-form-urlencoded)
 *        accion=guardar
 *        cuenta=XXXX
 *        datos=<json>
 *        -> {"status":"ok"}
 */

header('Content-Type: application/json; charset=utf-8');

// Tope de tamaño por cuenta: favoritos/continuar/historial de una sola
// cuenta no deberían pasar de esto ni de cerca. Corta un abuso o un bug
// que mande basura en vez de JSON.
const TAMANO_MAXIMO = 2 * 1024 * 1024; // 2 MB

$carpetaDatos = __DIR__ . '/datos_sync';
if (!is_dir($carpetaDatos)) {
    @mkdir($carpetaDatos, 0755, true);
    // Que nadie liste ni descargue los archivos entrando directo por URL.
    // "Require all denied" es Apache 2.4+ (lo normal en hostings actuales).
    // Si el hosting fuera un Apache 2.2 viejo, esta línea no aplicaría y
    // habría que cambiarla a mano por "Deny from all" (no se ponen las dos
    // juntas: mezclarlas puede romper el .htaccess entero en 2.4 si no está
    // el módulo mod_access_compat).
    @file_put_contents($carpetaDatos . '/.htaccess', "Require all denied\n");
}

function responder($datos, int $codigoHttp = 200): void {
    http_response_code($codigoHttp);
    echo json_encode($datos);
    exit;
}

/** Igual que el saneo que ya hace la app en Kotlin: solo letras, números y guion bajo. */
function cuentaValida(string $cuenta): ?string {
    $limpio = preg_replace('/[^A-Za-z0-9_]/', '_', $cuenta);
    $limpio = substr($limpio, 0, 80);
    return $limpio === '' ? null : $limpio;
}

function rutaPara(string $cuenta): string {
    global $carpetaDatos;
    return $carpetaDatos . '/' . $cuenta . '.json';
}

$accion = $_REQUEST['accion'] ?? '';
$cuentaCruda = $_REQUEST['cuenta'] ?? '';
$cuenta = cuentaValida($cuentaCruda);

if ($cuenta === null) {
    responder(['status' => 'error', 'mensaje' => 'Falta o es inválida la cuenta'], 400);
}

if ($accion === 'obtener' && $_SERVER['REQUEST_METHOD'] === 'GET') {
    $ruta = rutaPara($cuenta);
    $datos = is_file($ruta) ? file_get_contents($ruta) : null;
    responder(['status' => 'ok', 'datos' => $datos === false ? null : $datos]);
}

if ($accion === 'guardar' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $datos = $_POST['datos'] ?? '';
    if ($datos === '') {
        responder(['status' => 'error', 'mensaje' => 'Falta "datos"'], 400);
    }
    if (strlen($datos) > TAMANO_MAXIMO) {
        responder(['status' => 'error', 'mensaje' => 'Los datos superan el tamaño permitido'], 413);
    }
    // Validar que sea JSON de verdad antes de guardarlo: mejor rechazar acá
    // que guardar basura que después no se puede volver a leer.
    json_decode($datos);
    if (json_last_error() !== JSON_ERROR_NONE) {
        responder(['status' => 'error', 'mensaje' => 'Los datos no son JSON válido'], 400);
    }
    $ok = file_put_contents(rutaPara($cuenta), $datos, LOCK_EX) !== false;
    responder(['status' => $ok ? 'ok' : 'error']);
}

responder(['status' => 'error', 'mensaje' => 'Acción o método inválido'], 400);
