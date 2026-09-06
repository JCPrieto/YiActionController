# YI Action Controller · diagnóstico

Android nativo, Kotlin y Jetpack Compose. Hito limitado al socket TCP de la
Xiaomi YI original YDXJ01XY. Referencia física aportada: hardware `YDXJ_v23L`,
firmware `YDXJv25L_1.5.12`. Estas versiones no se usan como valores de la UI.

## Uso

1. Instala `app/build/outputs/apk/debug/app-debug.apk`.
2. Conecta el teléfono manualmente al Wi-Fi de la cámara. Acepta permanecer en
   esa red aunque Android indique que no tiene Internet.
3. Pulsa **Conectar**. La app abre `192.168.42.1:7878`, obtiene un token con
   `msg_id=257` y consulta batería (`13`) y configuración (`3`).
4. **Consultar batería y configuración** repite ambas consultas. El socket sigue
   leyendo eventos incluso sin pulsar este botón.
5. **Desconectar** cierra el socket y borra el estado de la sesión. La siguiente
   conexión siempre solicita un token nuevo.

Si el teléfono enruta la conexión por datos móviles, desactívalos durante la
prueba. La app no selecciona redes ni conecta automáticamente al Wi-Fi.

## Estructura y protocolo

- `CameraClient`: un único trabajador IO por sesión, socket, envío, lectura,
  plazos de respuesta y `StateFlow<CameraState>` de solo lectura.
- `JsonObjectFramer`: separa objetos concatenados o fragmentados manteniendo
  profundidad, cadenas y escapes. No usa `readLine()`. Límite de 1 MiB por
  objeto en el transporte. Decodifica UTF-8 después de completar cada objeto.
- `CameraProtocol`: DTO con kotlinx.serialization, configuración y eventos.
- `CameraViewModel`: propietario del cliente; conserva la sesión al rotar la
  pantalla y la cierra al destruirse. Sin servicio de segundo plano.
- `MainActivity`: diagnóstico Compose con recogida de estado ligada al ciclo
  de vida, errores, configuración completa y último mensaje/evento seleccionables.

Conexión y respuestas tienen un plazo de 5 segundos. El timeout de lectura de
250 ms permite atender botones y plazos; no desconecta por inactividad cuando
no hay comandos pendientes. EOF, JSON inválido, respuesta ausente y fallo de
autenticación cierran la sesión. No hay reconexión automática. Un `rval` distinto
de cero en las consultas se muestra como error sin aplicar el contenido.

El token se obtiene de `param` del inicio de sesión y nunca se fija a `4`.
Los eventos `msg_id=7` no consumen las respuestas pendientes de las consultas.
La batería acepta el porcentaje textual o numérico y valida el rango 0–100.

La respuesta real de configuración todavía no se ha aportado. Se aceptan objetos
`{"sw_version":"..."}` y `{"key":"sw_version","value":"..."}` dentro de
`param`. Los alias provisionales de la UI son:

| Campo            | Claves                           |
|------------------|----------------------------------|
| Firmware         | `sw_version`, `firmware_version` |
| Hardware         | `hw_version`, `hardware_version` |
| SD               | `sd_card_status`, `sd_status`    |
| Vídeo            | `video_resolution`               |
| Estado de cámara | `camera_status`, `status`        |

Se muestran los valores originales, sin inferir que la cámara está inactiva o
que tiene SD por el hecho de estar conectada. Un campo ausente dice **Sin datos**.
Los eventos con esas claves actualizan los campos; los demás se conservan como
diagnóstico. Hay que contrastar estos alias con el `msg_id=3` real del dispositivo.

## Verificación

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Pruebas JVM del encuadre JSON, mapeo de mensajes y servidor TCP local: tokens
dinámicos, reconexión, respuestas concatenadas y fuera de orden, eventos,
UTF-8 fragmentado, errores, EOF y cancelación. La validación física pendiente
consiste en comprobar los ocho campos, bajar la batería y observar su evento,
rotar la pantalla, desconectar/reconectar y apagar la cámara durante la sesión.

La configuración de Compose usa
su [plugin oficial de compilación](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler),
alineado con Kotlin 2.2.10 incluido por el AGP del proyecto.

No incluye RTSP, captura, grabación, galería, persistencia, base de datos ni DI.
