# YI Action Controller · Hito 2: control y diagnóstico

Android nativo, Kotlin y Jetpack Compose. Hito limitado al socket TCP de la
Xiaomi YI original YDXJ01XY. Referencia física aportada: hardware `YDXJ_v23L`,
firmware `YDXJv25L_1.5.12`, teléfono Android 16. Estas versiones no se usan
como valores de la UI. Se conserva la arquitectura y el diagnóstico del Hito 1.

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

La sección **Control** añade **Hacer foto**, **Iniciar grabación** y **Detener grabación**. Solo admite acciones con
sesión conectada, token válido
y ninguna petición pendiente (incluidas las consultas en cola). Las pulsaciones
no admisibles se ignoran; no se acumulan acciones ni se reintentan capturas. **Última foto** muestra la ruta comunicada
en `photo_taken`, sin abrir ni descargar
el archivo. La aceptación de la foto no significa que el JPG ya esté creado.

## Comandos del Hito 2

| Operación         | `msg_id` | Respuesta de aceptación   |
|-------------------|----------|---------------------------|
| Hacer fotografía  | 769      | `{"rval":0,"msg_id":769}` |
| Iniciar grabación | 513      | `{"rval":0,"msg_id":513}` |
| Detener grabación | 514      | `{"rval":0,"msg_id":514}` |

Todos envían `{"msg_id":<id>,"token":<token de esta sesión>}`. Los
identificadores se centralizan en `CameraCommand`; `CameraAction` representa
explícitamente la acción reservada/en vuelo en `CameraState.pendingAction`.
El mismo lector continuo procesa tanto respuestas como eventos. Un evento `7`,
incluso con `rval`, nunca libera la petición pendiente. Una respuesta del comando
la libera tanto con `rval=0` como con un error. Los rechazos individuales quedan
visibles y mantienen el TCP abierto. No se atribuye un significado de «sesión
inválida» a códigos de error no especificados en el protocolo aportado.

### Estado de grabación y foto

- `STARTING` y `STOPPING` indican una solicitud pendiente, no un hecho confirmado.
- Tras aceptar o rechazar iniciar/detener se consulta configuración (`3`) por
  la misma cola. El ACK por sí solo deja el estado en `UNKNOWN`; se conserva
  una actualización real de `app_status` que haya llegado antes del ACK.
- `app_status=idle` se interpreta como `IDLE`; `record` y `recording`, como
  `RECORDING`. Otro valor o una configuración sin `app_status` produce `UNKNOWN`.
  Un evento `app_status` actualiza el mismo estado. `rec_mode` y `system_mode`
  siguen visibles en el diagnóstico, pero no prueban actividad de grabación.
- Iniciar requiere `IDLE`; detener admite `RECORDING` o `UNKNOWN`, este último
  como recuperación explícita. En `UNKNOWN` se puede consultar configuración.
- `lastPhotoEvent` refleja exclusivamente `start_photo_capture`,
  `precise_capture_data_ready` o `photo_taken`. No se infiere una fase de captura
  a partir del envío o del ACK. `lastPhotoPath` se actualiza solo con una cadena
  no vacía en `param` de `photo_taken`; eventos incompletos no borran la ruta.

La interpretación de los estados `record`/`recording` se apoya en el
[cliente de referencia para la YI original](https://github.com/deltaflyer4747/Xiaomi_Yi/blob/master/CC.pyw),
que reconoce `record` en `app_status`. Se usan coincidencias exactas, sin
considerar valores desconocidos como inactividad. Esa referencia no sustituye
la comprobación física del estado en este firmware.

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
Las consultas se envían de una en una: primero batería, y solo al recibir su
respuesta se envía configuración. Los eventos no liberan la petición pendiente.
Al fallar se conservan la última petición y el último mensaje/evento para
diagnóstico, aunque se borran el token y los valores de la sesión. El plazo
de respuesta sigue siendo de 5 segundos; no se oculta una respuesta ausente.

El token se obtiene de `param` del inicio de sesión y nunca se fija a `4`.
Los eventos `msg_id=7` no consumen las respuestas pendientes de las consultas.
La batería acepta el porcentaje textual o numérico y valida el rango 0–100.

La configuración se ha consultado físicamente desde el teléfono por USB/ADB.
Se aceptan objetos
`{"sw_version":"..."}` y `{"key":"sw_version","value":"..."}` dentro de
`param`. La primera clave de cada campo está verificada en este firmware;
las demás se conservan como alias:

| Campo            | Claves                                  |
|------------------|-----------------------------------------|
| Firmware         | `sw_version`, `firmware_version`        |
| Hardware         | `hw_version`, `hardware_version`        |
| SD               | `sd_card_status`, `sd_status`           |
| Vídeo            | `video_resolution`                      |
| Estado de cámara | `app_status`, `camera_status`, `status` |

Se muestran los valores originales, sin inferir que la cámara está inactiva o
que tiene SD por el hecho de estar conectada. Un campo ausente dice **Sin datos**.
Los eventos con esas claves actualizan los campos; los demás se conservan como
diagnóstico.

### Fallo de batería verificado en el dispositivo

En la prueba física del 6 de septiembre de 2026, enviar los JSON de `13` y `3`
concatenados produjo únicamente la respuesta de configuración; no llegó la
respuesta de batería durante el plazo de 5 segundos. Enviados secuencialmente,
esperando la respuesta entre ambos, los dos comandos respondieron. Por eso el
cliente mantiene una sola petición en vuelo. El soporte de JSON concatenados
en recepción no implica que la cámara los acepte como peticiones.

## Verificación

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Pruebas JVM del encuadre JSON, mapeo de mensajes y servidor TCP local: tokens
dinámicos, reconexión, peticiones secuenciales, respuestas concatenadas, eventos,
UTF-8 fragmentado, errores, EOF y cancelación. Se verificaron físicamente el
inicio de sesión, el evento y la consulta de batería y la consulta de configuración
desde el teléfono. El usuario ha confirmado el Hito 1 completo y validado
físicamente, incluida rotación, reconexión y desconexión física.

### Evidencia y límites del Hito 2

| Nivel                                                                | Evidencia                                                                                                                                                                                                                                       |
|----------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Validado físicamente, comunicado por el usuario                      | Protocolo de petición y aceptación de `769`, `513` y `514` para la cámara de referencia.                                                                                                                                                        |
| Cubierto por tests locales                                           | Envío de las tres acciones, token dinámico y nuevo token al reconectar, reserva inmediata, una petición en vuelo, ACK, rechazo sin cierre, eventos intercalados, ruta de foto, actualización de estado y timeout. Incluye regresión del Hito 1. |
| Documentado por el usuario; sin captura física aportada en este hito | Eventos de foto `start_photo_capture`, `precise_capture_data_ready`, `photo_taken`. Se procesan si llegan; no se exige que lleguen todos ni en un orden fijo.                                                                                   |
| Referencia externa y tests; pendiente de observar en esta cámara     | `app_status=record/recording` y eventos con `type=app_status`. No se inventan otros nombres de eventos de vídeo.                                                                                                                                |

No se ha realizado una nueva prueba física de la UI del Hito 2 durante su
implementación. Para validarla: conectar; hacer una foto y comprobar la ruta;
iniciar vídeo y observar la configuración recibida; detener y comprobar el
retorno a `idle`; repetir después de rotar y reconectar. Conservar los mensajes
reales para confirmar los estados/eventos de vídeo. Las rutas usadas por los
tests son ejemplos sintéticos, no archivos observados en la SD.

Limitaciones: no hay temporizador de finalización de foto ni polling periódico;
si no llega `photo_taken` no se inventa una ruta. Un estado no reconocido mantiene
`UNKNOWN` y bloquea iniciar. Un timeout cierra la sesión como en el Hito 1,
incluido un timeout de la consulta de configuración posterior a una acción;
esto no asegura que la acción no se ejecutase, por lo que no se reintenta sola.

La configuración de Compose usa
su [plugin oficial de compilación](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler),
alineado con Kotlin 2.2.10 incluido por el AGP del proyecto.

No incluye RTSP, vista previa, Media3, galería, listado/descarga de archivos,
cambios de configuración, conexión automática al Wi-Fi, persistencia, base de
datos ni DI. No incorpora funcionalidades del Hito 3.
