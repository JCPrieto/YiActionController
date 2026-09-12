# YI Action Controller · Hito 4B: conexión persistente connectedDevice

Android nativo, Kotlin y Jetpack Compose. Control y vista previa de la
Xiaomi YI original YDXJ01XY. Referencia física aportada: hardware `YDXJ_v23L`,
firmware `YDXJv25L_1.5.12`, teléfono Android 16. Estas versiones no se usan
como valores de la UI. Se conserva la arquitectura y el diagnóstico del Hito 1.

## Uso

1. Instala `app/build/outputs/apk/debug/app-debug.apk`.
2. Enciende la cámara y su Wi-Fi. Desde Android 10 introduce el SSID exacto y
   la contraseña en la app. En Android 8/9 conecta manualmente desde Ajustes.
3. Pulsa **Conectar a cámara** y concede los permisos y la autorización del sistema.
   Se inicia el servicio de conexión con una notificación. En Android 13+ se pide
   permiso para mostrar notificaciones; denegarlo no impide la conexión ni el FGS.
   Tras confirmar una ruta local, la app abre `192.168.42.1:7878`, obtiene un token con
   `msg_id=257` y consulta batería (`13`) y configuración (`3`).
4. **Consultar batería y configuración** repite ambas consultas. El socket sigue
   leyendo eventos incluso sin pulsar este botón.
5. **Desconectar**, en la app o la notificación, libera preview, socket y solicitud
   Wi-Fi, detiene el servicio y borra el estado de la sesión. La siguiente
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
  una actualización real recibida por evento antes del ACK.
- `app_status=idle` o `vf` (viewfinder) se interpreta como `IDLE`; `record` y `recording`, como
  `RECORDING`. Otro valor o una configuración sin `app_status` produce `UNKNOWN`.
  Un evento `app_status` actualiza el mismo estado. `rec_mode` y `system_mode`
  siguen visibles en el diagnóstico, pero no prueban actividad de grabación.
- Los eventos físicamente observados `start_video_record` y `vf_start` actualizan
  a `RECORDING` e `IDLE`, respectivamente. `vf_stop` no significa que empiece
  una grabación y no se usa como prueba de ello.
- Una revisión local del estado protege los eventos que llegan durante una
  consulta: su respuesta no puede sobrescribir el estado más reciente. La
  consulta automática tras una acción protege también eventos anteriores al
  ACK. Una nueva consulta manual puede reconciliar el estado normalmente.
- «Último evento de grabación» conserva el evento de vídeo aunque después
  llegue un evento de batería. No implica almacenamiento persistente.
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
`vf` aparece también en
las [capturas de protocolo publicadas](https://gist.github.com/pbaja/f57e6cff7fa14601f6b256926aa33437).
Su mapeo de configuración se cubre por tests; las capturas locales aportadas
confirman el evento `vf_start`, pero no muestran `app_status` tras detener.

Los datos móviles pueden permanecer activos: si se identifica una Wi-Fi con
ruta específica hacia la YI, la app liga los sockets de control y de RTSP a esa
Network. Desde el Hito 4 solicita la Wi-Fi local-only al pulsar el botón; no
conecta al arrancar ni modifica la red predeterminada. Internet simultáneo
requiere la prueba física descrita al final.

## Estructura y protocolo

- `CameraClient`: un único trabajador IO por sesión, socket, envío, lectura,
  plazos de respuesta y `StateFlow<CameraState>` de solo lectura.
- `JsonObjectFramer`: separa objetos concatenados o fragmentados manteniendo
  profundidad, cadenas y escapes. No usa `readLine()`. Límite de 1 MiB por
  objeto en el transporte. Decodifica UTF-8 después de completar cada objeto.
- `CameraProtocol`: DTO con kotlinx.serialization, configuración y eventos.
- `CameraConnectionService`: servicio started + bound, propietario del único
  `CameraClient`, petición Wi-Fi y binding. Mantiene la conexión sin depender de la UI.
- `CameraSessionRecovery`: política testeable de recuperación TCP tras desbloqueo.
- `CameraViewModel`: observa el Binder local y posee exclusivamente preview/UI;
  al destruirse libera el reproductor y se desvincula, sin desconectar el servicio.
- `MainActivity`: diagnóstico Compose con recogida de estado ligada al ciclo
  de vida, errores, configuración completa y último mensaje/evento seleccionables.

Conexión y respuestas tienen un plazo de 5 segundos. El timeout de lectura de
250 ms permite atender botones y plazos; no desconecta por inactividad cuando
no hay comandos pendientes. EOF, JSON inválido, respuesta ausente y fallo de
autenticación cierran la sesión. Solo hay un intento automático TCP por desbloqueo
de la misma Network (Hito 4B), nunca reconexión Wi-Fi automática. Un `rval` distinto
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
| Observado en capturas del usuario (06/09/2026)                       | `start_video_record` al iniciar, `app_status=record` durante la grabación y `vf_start` después de detener; eventos de batería posteriores.                                                                                                      |
| Referencia externa y tests; pendiente de observar en esta cámara     | `app_status=recording/vf` y eventos con `type=app_status`.                                                                                                                                                                                      |

El usuario ha probado físicamente foto (ruta mostrada bajo `/tmp/fuse_d/DCIM/`),
inicio y parada de vídeo (actividad confirmada mediante los LED). Las capturas
revelaron que el diagnóstico ignoraba los eventos de vídeo y se quedaba con
la configuración anterior. Se ha corregido el procesamiento de esos eventos y
añadido regresión TCP para eventos anteriores al ACK, intercalados durante la
consulta y posteriores a la respuesta, tanto en inicio como en parada.
El usuario ha confirmado posteriormente esta corrección como validada físicamente:
inicio/parada, eventos de vídeo y diagnóstico forman parte del Hito 2 completado.
Las rutas completas usadas por los tests son ejemplos sintéticos.

Limitaciones: no hay temporizador de finalización de foto ni polling periódico;
si no llega `photo_taken` no se inventa una ruta. Un estado no reconocido mantiene
`UNKNOWN` y bloquea iniciar. Un timeout cierra la sesión como en el Hito 1,
incluido un timeout de la consulta de configuración posterior a una acción;
esto no asegura que la acción no se ejecutase, por lo que no se reintenta sola.

La configuración de Compose usa
su [plugin oficial de compilación](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler),
alineado con Kotlin 2.2.10 incluido por el AGP del proyecto.

## Hito 3 · Vista previa RTSP

### Historial de diagnóstico en memoria

La cabecera de la pantalla incluye un historial visible, seleccionable y con
botones **Copiar historial** y **Limpiar**. Conserva las últimas 200 entradas (detalle limitado a 1200 caracteres por
entrada), en orden, con número de
secuencia y fecha/hora con zona. Al llenarse descarta las entradas más antiguas.
Registra TX tras escribir el comando, RX, todos los eventos `msg_id=7`, timeouts,
conexiones y estados del preview. RX/eventos se capturan en el lector existente,
antes de actualizar el estado; no dependen de que la UI observe cada mensaje.

No almacena tokens (tampoco el `param` del login), SSID, contraseña Wi-Fi ni
número de serie. La configuración se resume a los campos de estado, modos,
tarjeta, resolución y versiones. De los eventos se conserva tipo y solo los
parámetros permitidos de diagnóstico, incluida la ruta de `photo_taken`;
los parámetros desconocidos se omiten. No es una captura íntegra del protocolo.
El portapapeles recibe únicamente este historial resumido al pulsar Copiar,
no los campos de diagnóstico antiguo que muestran la configuración completa.

Sobrevive a rotación y reconexión dentro del mismo ViewModel, no a muerte del
proceso. No se guarda en archivos, base de datos ni Logcat. Limpiar borra solo
el historial de la app, no una copia ya realizada al portapapeles. No añade
comandos, polling, reintentos ni una interpretación del rechazo `259/-21`.

Para la próxima prueba: pulsar **Limpiar**, reproducir la secuencia
preview → detener → foto → grabar → detener → preview. Si aparece el rechazo,
consultar configuración y pulsar **Copiar historial** antes de cerrar la app.
Compartir ese texto, indicando si los datos móviles estaban activos.
El usuario ha compartido el historial generado en la prueba física del 08/09/2026.

El usuario ha confirmado los Hitos 1 y 2 completos y validados físicamente,
incluida la corrección de los estados de grabación descrita anteriormente.
La vista previa UDP ha reproducido vídeo físicamente según las pruebas del
usuario, incluidos rotación, grabación simultánea y background. En la prueba
inicial del 07/09/2026, Media3 obtuvo información H.264
432 × 240 y la cámara rechazó el SETUP con `461` al solicitar RTP sobre TCP.
Ese rechazo no demostraba reproducción de frames; las pruebas posteriores
con **RTP/UDP** sí muestran reproducción. Los tests JVM no reproducen
ni simulan un servidor RTSP real.

Se usan exclusivamente `media3-exoplayer`, `media3-exoplayer-rtsp` y `media3-ui`
de AndroidX Media3 **1.11.0**, declarados en el version catalog.

| Canal             | Destino                    | Función                                              |
|-------------------|----------------------------|------------------------------------------------------|
| Control existente | TCP `192.168.42.1:7878`    | Token, diagnóstico, foto/vídeo y comandos de preview |
| Vídeo             | `rtsp://192.168.42.1/live` | Negociación RTSP por TCP, RTP/RTCP por UDP           |

### Inicio y parada

**Recuperación explícita de 259/-21:** tras ese rechazo aparece **Reiniciar
vista previa**. Solo está habilitado con sesión conectada, token, grabación
`IDLE` y ninguna petición pendiente. No se permite en `UNKNOWN`, `STARTING`,
`RECORDING` ni `STOPPING`. Envía 260 y exige tanto `rval=0` como un nuevo
`vf_stop` recibido después de enviar el comando (acepta evento antes o después
del ACK). Durante la confirmación se bloquean otras peticiones de control.
Espera como máximo cinco segundos tras el ACK; si falta el evento, muestra
error sin cerrar el TCP ni enviar 259. El timeout del propio comando conserva
la política de desconexión existente.

Solo tras ambas confirmaciones vuelve a comprobar el estado y envía un único
259; Media3 se crea únicamente con `rval=0`. No hay bucles ni reintentos
automáticos. Detener, background, desconexión o pérdida de Network durante la
recuperación impiden iniciar el player. Se registra la operación en el
historial (`UI`, `RECOVERY`, `TX`, `RX`, `EVENT`, `PREVIEW`).

**Evidencia física del 08/09/2026:** inicio UDP con `PLAYING`, parada 260
aceptada seguida de `vf_stop`, foto completada seguida de `vf_start`, grabación
completada (`video_record_complete`) seguida de otro `vf_start`, y rechazo
259/-21 al intentar iniciar de nuevo. Esto sugiere un conflicto de estado del
visor, pero **no confirma el significado general de -21**. La nueva secuencia
de recuperación está cubierta por tests y validada en la cámara.
No considera el rechazo como éxito ni abre RTSP basándose solo en
`preview_status=on`.

1. Conectar a la YI (en el Hito 4, mediante **Conectar a cámara** desde la app).
2. Pulsar **Iniciar vista previa**. Se exige sesión TCP conectada y se confirma
   una Network Wi-Fi con ruta a la cámara.
3. Se envía `START_PREVIEW=259` con el token actual y se espera `rval=0`.
   Un rechazo se muestra como error de control y no crea el reproductor.
4. Solo entonces `Media3PreviewPlayer` crea ExoPlayer y un `RtspMediaSource`
   explícito con la socket factory de esa Network y canales RTP/RTCP UDP
   vinculados individualmente a la misma Network. Timeout de recepción UDP:
   8 segundos. No se habilita logging de protocolo ni fallback RTP/TCP.
5. **Detener vista previa** libera localmente el reproductor primero y después
   envía `STOP_PREVIEW=260`. Si había un inicio en vuelo, espera su respuesta
   para detenerlo, sin llegar a crear el player si ya se solicitó la parada.

Ambos comandos usan el lector y la cola TCP existentes, con una sola petición
en vuelo. Para detener se espera a que termine cualquier operación de control
actual; la liberación local nunca espera al ACK del stop. Un error de Media3
no cierra `CameraClient`. Se puede reintentar explícitamente cuando termine la
limpieza. Los timeouts de comandos TCP siguen la política de los Hitos 1 y 2.

### Routing y permisos

`CameraNetworkProvider` enumera `ConnectivityManager.allNetworks`, inspecciona
capacidades y rutas de `LinkProperties` y selecciona únicamente Wi-Fi (sin VPN
ni transporte celular) con una ruta unicast específica que contenga
`192.168.42.1`. La coincidencia de prefijo más larga tiene preferencia. Una ruta
predeterminada `0.0.0.0/0` no basta para identificar la YI. No se exige `INTERNET`
ni `VALIDATED`, ni se usa `activeNetwork` como sustituto de esta selección.

TCP recibe opcionalmente la `SocketFactory` de la Network; si no se proporciona,
el cliente mantiene su comportamiento previo. Preview requiere identificar la
Network y pasa **su** `network.socketFactory` a la conexión RTSP de Media3.
No se llama a `bindProcessToNetwork`: el resto del tráfico puede seguir usando
datos móviles. Cada `DatagramSocket` RTP/RTCP se vincula mediante
`network.bindSocket(socket)` antes de reservar su puerto local. Se reservan
puertos consecutivos (RTP par y RTCP impar); un fallo de binding cierra los
sockets y no recurre a la red predeterminada. RTCP queda reservado, como en el
canal UDP estándar de Media3; no se añade procesamiento ni envío de informes RTCP.
Véase la [documentación oficial de RTSP y SocketFactory](https://developer.android.com/media/media3/exoplayer/rtsp).

**Adaptador específico de Media3 1.11.0:** su Factory pública no permite
inyectar sockets UDP. `YiUdpMediaSource`, aislado en el paquete
`androidx.media3.exoplayer.rtsp`, accede al constructor y a `RtpDataChannel`
con visibilidad de paquete. No usa reflexión, sustituye clases ni copia el
player. Usa el extractor RTP de Media3 con un canal de datagramas propio.
Es una dependencia de APIs internas: al actualizar Media3 hay que revisar y
compilar este adaptador contra el
[código de la versión](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer_rtsp/src/main/java/androidx/media3/exoplayer/rtsp/RtspMediaSource.java).
`PreviewUdpSockets` cierra todos los pares antes del release de ExoPlayer para
desbloquear inmediatamente lecturas, incluso si todavía se estaban creando.
No se modifica `CameraClient` ni su canal de control TCP para esta adaptación.

El Hito 4 amplía los permisos de conexión Wi-Fi (detalle más abajo). Desde
Android 13 se solicita Dispositivos cercanos antes de conectar, sin ubicación.
Si se deniega, el flujo se detiene con error recuperable y no inicia TCP ni preview.

Prueba experimental en Android 16 (targetSdk sigue siendo **36**):

```sh
adb shell am compat enable RESTRICT_LOCAL_NETWORK es.jcprieto.yiactioncontroller
adb reboot
```

Tras reiniciar, abrir la app, conceder **Dispositivos cercanos** al solicitarlo (o en Ajustes → Aplicaciones →
YiActionController → Permisos), conectar al Wi-Fi
YI y repetir inicio/parada con datos móviles activos. Para desactivar:

```sh
adb shell am compat disable RESTRICT_LOCAL_NETWORK es.jcprieto.yiactioncontroller
adb reboot
```

Android 17 / targetSdk 37 introduce `ACCESS_LOCAL_NETWORK`; no se declara ni
implementa todavía.
Referencia: [protección de red local de Android](https://developer.android.com/privacy-and-security/local-network-permission).

### Estados y ciclo de vida

`CameraViewModel` mantiene `CameraPreviewController`, que gobierna
`Media3PreviewPlayer`; cliente y Network pertenecen al servicio desde Hito 4B. El reproductor
no se crea desde Compose. `PlayerView` se adjunta con `AndroidView`, en una
superficie 16:9, y elimina su referencia al player al abandonar la composición.

La UI separa sesión TCP, aceptación del control de streaming y reproducción.
`START_ACCEPTED` solo significa ACK de `259`; **En directo** requiere
`STATE_READY` e `isPlaying=true`. `Player.Listener` procesa buffering, cambios
de reproducción y errores. `preview_status`, `streaming_status` y
`dual_stream_status` siguen visibles en el diagnóstico completo de configuración.

La rotación conserva el ViewModel y el reproductor. `Activity.onStop` detiene
preview al abandonar la pantalla, exceptuando cambios de configuración. No se
reinicia al volver a foreground. Detener, desconectar cámara, perder la Network,
un error fatal o `ViewModel.onCleared` liberan el player; `release` es idempotente.
No hay reproducción en segundo plano. El servicio connectedDevice conserva solo
la conexión, no Media3 ni RTP/RTCP del preview detenido.

Preview no bloquea foto/grabación salvo mientras haya un comando TCP pendiente.
El evento `vf_start` de preview no debe marcar como inactiva una grabación en
curso; la solicitud de detener grabación mantiene su semántica del Hito 2.
La simultaneidad durante inicio/parada de grabación está validada físicamente en la cámara de referencia.

### Verificación y límites

- **Validado físicamente:** Hitos 1 y 2 según confirmación del usuario. En
  preview se ha observado `SETUP 461` con RTP/TCP y reproducción posterior con
  UDP, inicio/parada/reinicio y aceptación de 259/260. Persiste el rechazo
  259/-21 en la secuencia foto/grabación descrita arriba.
- **Tests locales:** tokens de `259`/`260`, rechazos, eventos intercalados,
  selección Wi-Fi sin Internet frente a celular, prioridad por subred, callbacks
  de reproducción, liberación idempotente, desconexión, pérdida de red, error
  fatal, reintento y background durante un inicio pendiente. Regresión Hitos 1/2.
  UDP local: binding de ambos sockets antes de reservar puertos, pares par/impar,
  limpieza ante error, release idempotente y durante apertura, datagramas grandes
  y lecturas parciales, timeout y desbloqueo del receptor al cerrar. El binding
  Android real se sustituye por un callback en JVM: requiere prueba física.
- **Validado físicamente por el usuario (Hito 3):** recuperación explícita
  de 259/-21, rotación con imagen, preview durante inicio/parada de grabación,
  background que detiene preview y exige reinicio manual, y liberación con
  `CAMERA_DISCONNECTED` al desconectar la cámara.
- **Pruebas físicas del Hito 4:** resultados del 09/09/2026 más abajo. Internet
  simultáneo funciona; persiste una incidencia TCP al abrir otra app.

Prueba física propuesta: conectar con datos móviles activos; iniciar preview;
confirmar imagen/En directo; rotar; iniciar/detener grabación con preview;
detener/reiniciar preview; salir a Home y volver (sin autoarranque); apagar el
Wi-Fi o la cámara; reconectar y reintentar. Repetir con protección local activa.

Limitaciones históricas de 4A: UDP sin fallback automático a RTP/TCP. La
conservación de TCP al abrir otra app queda resuelta y validada en 4B. Sin
reconexión automática Wi-Fi, pantalla completa,
galería, listado/descarga/reproducción de archivos, cambios de resolución,
configuración avanzada, persistencia, base de datos, DI ni funciones del Hito 5.

Verificación final:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

## Hito 4A — conexión local-only

### Validación física e incidencia abierta — 09/09/2026

Resultados comunicados por el usuario con la cámara de referencia:

| Prueba                            | Resultado                                                                              |
|-----------------------------------|----------------------------------------------------------------------------------------|
| A — Conexión desde app            | Superada                                                                               |
| B — Internet simultáneo           | Internet funciona al abrir otra web/app; al volver, TCP de la cámara está desconectado |
| C — Preview, rotación y grabación | Superada                                                                               |
| D — Background                    | Home y volver supera la prueba; abrir el navegador provoca cierre TCP                  |
| E — Desconexión física            | Superada                                                                               |
| F — Reconexión                    | Superada                                                                               |
| G — RESTRICT_LOCAL_NETWORK        | Superada                                                                               |

El historial confirma `260/rval=0`, preview `IDLE` y `vf_stop` antes del fallo TCP;
después la app emite `CAMERA_CONNECTION` y libera la callback como limpieza. No
aparece `onLost` previo en el fragmento aportado. Seguir asociado al AP no implica
que la sesión TCP o la solicitud de la app sigan activas.

El historial posterior del 09/09/2026 a las 20:04 muestra
`onBlockedStatusChanged(network, true)` antes del fallo de socket. Esto confirma
que Android bloqueó el acceso del UID a la Network, **no identifica la causa**.
No se atribuye a process freezer, Doze, App Standby, ahorro de batería ni a una
política del fabricante. El Hito 4B, descrito abajo, sustituye la limpieza global
por separación Wi-Fi/TCP y añade el servicio conectado.

El diagnóstico conserva tipo de fallo (`EOF`, `SOCKET`, etc.) y errno conocidos,
sin copiar mensajes de excepción ni credenciales. La última revisión previa al
Hito 4B pasó 60 tests y lint con 0 errores y 17 advertencias.

### Implementación

La conexión manual a la YI dejó al teléfono de prueba sin Internet efectivo aun
con datos móviles activos. El flujo principal desde API 29 solicita una conexión
temporal peer-to-peer con `WifiNetworkSpecifier`, SSID exacto y WPA2. No usa Wi-Fi
Direct, escaneo ni sugerencias de redes. `NetworkRequest` solicita `TRANSPORT_WIFI`
y elimina expresamente `NET_CAPABILITY_INTERNET`.

`CameraWifiConnectionManager`, ahora propiedad del servicio y con application context,
mantiene una sola petición. Las callbacks se procesan en el hilo principal y se
identifican por generación; se ignoran duplicados y callbacks de peticiones anteriores.
`onAvailable` solo indica disponibilidad. Se esperan capacidades Wi-Fi (sin celular
ni VPN) y `LinkProperties` con ruta unicast específica a `192.168.42.1`; la ruta
por defecto no basta. Esto confirma encaminamiento, no identidad ni respuesta de
la cámara: después se abre la sesión TCP y se autentica.

La misma Network proporciona `socketFactory` para control TCP y RTSP. El transporte
UDP validado del Hito 3 usa `network.bindSocket` para RTP y RTCP. No se modifica el
routing global ni se usa `bindProcessToNetwork`/`setProcessDefaultNetwork`.
Internet queda en la red predeterminada que elija Android. La coexistencia YI +
4G/5G depende del dispositivo/sistema y **está validada en el teléfono de referencia**.
Con el servicio connectedDevice, la continuidad de la sesión TCP al abrir otra app
también quedó validada físicamente. No se
activa Wi-Fi ni datos móviles desde la app; la concurrencia entre dos Wi-Fi no se
usa como requisito para permitir Wi-Fi más datos móviles.

Los estados observables son `DISCONNECTED`, `REQUESTING`, `CONNECTING` (validación
de ruta), `CONNECTED`, `BLOCKED` (Hito 4B), `UNAVAILABLE`, `LOST` y `ERROR`. Android no ofrece una callback
que identifique con certeza cuándo está visible el consentimiento. Tampoco permite
distinguir en `onUnavailable` cancelación/rechazo, red no encontrada y contraseña
incorrecta/fallo de asociación: el mensaje explica las posibilidades. Permiso
denegado, Wi-Fi desactivada, entrada inválida, falta de ruta, pérdida de Network y
fallo de sesión TCP sí tienen errores propios y permiten reintento manual.

El límite de solicitud es 45 segundos, incluida la espera de ruta tras disponibilidad.
Un bloqueo temporal conserva la petición; si al desbloquear aún falta validar la
ruta inicial, se vuelve a acotar esa espera a 45 segundos sin crear otra petición.
No se inicia TCP ni preview en una red sin validar. Al desconectar, fallar la
solicitud Wi-Fi, perder la Network o destruirse el servicio se libera la callback exacta una sola vez;
la limpieza tolera que Android ya la haya eliminado. La pérdida libera preview
con `NETWORK_LOST` y cierra TCP; un cierre TCP sin notificación de pérdida mantiene
`CAMERA_DISCONNECTED`. Un fallo TCP ya no libera la petición Wi-Fi.

La rotación conserva el ViewModel, petición y sesión; los Composables no registran
callbacks. Home detiene únicamente preview. La petición y TCP se conservan mientras
viva el servicio y Android mantenga la red; preview no vuelve a iniciarse sola.
El Hito 4B añade foreground service y una recuperación TCP por desbloqueo.

### Permisos y credenciales

- API 26–28: fallback manual existente, sin automatización Wi-Fi legacy.
- API 29–32: `ACCESS_FINE_LOCATION` en runtime. Se declara también
  `ACCESS_COARSE_LOCATION` y se solicitan conjuntamente, como exige Android 12
  para poder conceder ubicación precisa. Ambos tienen `maxSdkVersion=32`.
  Android puede requerir que Ubicación esté activada para esta API; revisar Ajustes
  si la solicitud falla. No se lee, deriva ni almacena ubicación.
- API 33+: `NEARBY_WIFI_DEVICES` en runtime con `neverForLocation`; no se solicita ubicación.
- Permisos normales: `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`,
  `ACCESS_WIFI_STATE` y `CHANGE_WIFI_STATE`.

El formulario no usa estado guardado ni persistencia. La contraseña aparece
enmascarada y se borra del formulario al conectar; si hay diálogo de permisos,
el ViewModel la conserva privadamente hasta recibir el resultado, incluso con
rotación. Después se descarta la referencia de la app al crear el specifier.
Android retiene lo necesario durante la solicitud; no se promete borrado seguro
de Strings en la JVM. Una nueva solicitud Wi-Fi requiere introducir las credenciales
de nuevo; reintentar solo TCP reutiliza la Network sin pedir contraseña.
No hay DataStore, SharedPreferences ni Keystore. Los errores de Android se convierten
en mensajes fijos para no revelar el specifier. El diagnóstico Wi-Fi solo incluye
estados y capacidades, sin SSID/BSSID, contraseña o número de serie. También se
ocultan contraseñas que la cámara devuelva en configuración/eventos antes de
exponerlas en `CameraState` y en el diagnóstico avanzado.

### Protección de red local

En Android 16 / targetSdk 36, probar la protección experimental con:

```sh
adb shell am compat enable RESTRICT_LOCAL_NETWORK es.jcprieto.yiactioncontroller
adb reboot
```

Conceder Dispositivos cercanos antes de conectar; comprobar TCP y preview. Para revertir:

```sh
adb shell am compat disable RESTRICT_LOCAL_NETWORK es.jcprieto.yiactioncontroller
adb reboot
```

Android 17 / targetSdk 37 introduce `android.permission.ACCESS_LOCAL_NETWORK`;
su adaptación queda pendiente. No se declara ni implementa en este hito.

Referencias
oficiales: [Wi-Fi Network Request API](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap),
[permisos Wi-Fi](https://developer.android.com/develop/connectivity/wifi/wifi-permissions),
[permisos de ubicación en Android 12](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime),
[protección de red local](https://developer.android.com/privacy-and-security/local-network-permission).

### Cubierto por tests

Verificación local del 09/09/2026: `:app:testDebugUnitTest :app:assembleDebug
:app:lintDebug` termina con **BUILD SUCCESSFUL**. Pasan **59 tests**;
lint informa **0 errores y 17 advertencias**. APK debug generado en
`app/build/outputs/apk/debug/app-debug.apk`. Esta comprobación no valida
las callbacks Wi-Fi físicas ni Internet móvil simultáneo.

La lógica de callbacks se prueba en JVM: petición única, pulsaciones duplicadas,
espera de capacidades/ruta en ambos órdenes, rechazo de celular/no Wi-Fi,
timeout sin ruta, `onUnavailable`, reintento, pérdida de ruta/red, callbacks tardías,
limpieza idempotente y una liberación por petición. Se verifica el transporte
compartido para TCP/preview/UDP, la ocultación de secretos y que un cierre TCP
posterior no sustituya `NETWORK_LOST`. Se conserva la regresión Hitos 1–3.
Las callbacks Android, el diálogo de consentimiento, la retención real en rotación
y el routing físico no se simulan como si fueran pruebas del sistema.

### Plan de pruebas físicas y repetición de B/D

**A — Conexión desde app.** Olvidar/desconectar manualmente la Wi-Fi YI en Android;
activar datos móviles; encender Wi-Fi de la cámara; abrir la app; introducir SSID
y contraseña; pulsar Conectar a cámara; conceder permisos/diálogo si aparece;
verificar Wi-Fi conectada, sesión TCP, batería y configuración.

**B — Internet simultáneo.** Conectar mediante la app, iniciar preview y comprobar
PLAYING. Mantener datos móviles activos, abrir una web/app que requiera Internet
y verificar acceso. Volver a YiActionController; preview permanece detenida por
la política de background y se puede iniciar manualmente.

**C — Preview.** Iniciar preview, rotar e iniciar/detener grabación. Confirmar imagen
estable y ausencia de otra petición Wi-Fi/consentimiento al rotar.

**D — Background.** Desde PLAYING, pulsar Home: preview debe detenerse. Volver:
Wi-Fi/TCP deben seguir disponibles si Android conservó la red, sin autoarranque de preview.

**E — Desconexión física.** Apagar Wi-Fi de la YI o la cámara. Verificar pérdida de
Network / CAMERA_DISCONNECTED según qué notificación llegue primero, player liberado,
UI utilizable y reintento manual tras encenderla.

**F — Reconectar.** Desconectar desde la app; comprobar que Internet sigue funcionando;
volver a introducir credenciales y conectar. Verificar una liberación por solicitud,
sin callbacks duplicadas. Probar doble pulsación, cancelar autorización, permiso
denegado, Wi-Fi apagada, SSID inexistente y contraseña incorrecta: sin crash ni TCP/preview
en una red no aceptada, con posibilidad de reintento.

**G — RESTRICT_LOCAL_NETWORK.** Repetir conexión, control y preview con la protección
experimental habilitada y el permiso Dispositivos cercanos concedido.

## Hito 4B — conexión persistente connectedDevice

### Implementado y validado físicamente

La conexión pertenece a `CameraConnectionService`, servicio **started + bound**
del mismo proceso, no exportado, de tipo `connectedDevice`. Hay un solo
`CameraClient`, un `CameraWifiConnectionManager` y un binding activo por conexión.
El ViewModel observa sus StateFlow mediante un Binder local y delega los comandos
existentes. No se serializa Network ni PreviewTransport y no hay singleton de servicio.

**Conectar a cámara** inicia el servicio desde la Activity visible mediante
`ContextCompat.startForegroundService`. El servicio llama a
`ServiceCompat.startForeground` antes de solicitar Wi-Fi. Se declaran
`FOREGROUND_SERVICE` y `FOREGROUND_SERVICE_CONNECTED_DEVICE`; los permisos
`CHANGE_NETWORK_STATE`/`CHANGE_WIFI_STATE` ya existentes cubren el prerrequisito
de connectedDevice. No se usa el tipo `camera`: no accedemos a la cámara del teléfono.
Se mantienen minSdk 26, targetSdk 36 y fallback manual para API 26–28.

El canal **Conexión con cámara YI** tiene importancia LOW. La notificación ongoing
muestra conexión en curso, conectada o temporalmente bloqueada, abre MainActivity
al tocarla e incluye **Desconectar**. Los PendingIntent son explícitos e inmutables,
sin credenciales. En API 33+ se solicita `POST_NOTIFICATIONS`; denegarlo **no
impide el FGS**, aunque Android no muestre su notificación en el drawer.
La app nunca usa ese permiso como requisito técnico de conexión.

El servicio se inicia sin extras de credenciales. Tras el permiso Wi-Fi y la
promoción foreground, el ViewModel entrega SSID/contraseña por el Binder; las
referencias temporales se limpian después. No se guardan en StateFlow, SavedState,
Intent, notificación, logs ni almacenamiento. Si no llega la entrega por Binder,
el servicio abandona el arranque tras un plazo acotado de 30 segundos.

### BLOCKED no es LOST

`onBlockedStatusChanged(true)` conserva candidate, Network, capacidades/ruta
conocidas y NetworkRequest; publica `BLOCKED` y el diagnóstico **Network BLOCKED
por Android**. No llama a unregister, fail ni lost por ese bloqueo. La callback
no consulta síncronamente capacidades o LinkProperties.

Un fallo TCP, tanto en CONNECTED como en BLOCKED, cierra solo la sesión y limpia
el token, **sin liberar la petición Wi-Fi ni el binding**. El error de sesión
permanece visible. Al recibir desbloqueo de la misma Network validada se vuelve
a CONNECTED. Si TCP está desconectado, `CameraSessionRecovery` hace **un intento**
con la misma SocketFactory: login 257, token nuevo, batería y configuración.
No se crea WifiNetworkSpecifier ni se repite consentimiento. Si falla, no hay
bucle: queda **Reintentar sesión TCP**, que también usa la Network conservada.

`onLost` sí termina esa conexión: libera preview, cierra TCP (incluido un intento
de recuperación), limpia binding, libera la callback y detiene el foreground
service. Perder la ruta o recibir una red inválida se distingue de LOST como
error terminal de validación. Un fallo TCP aislado no basta para inferir pérdida
Wi-Fi; si Android no informa pérdida, se mantiene la solicitud hasta desconexión
explícita o un error terminal de red.

### Ciclo de vida y límites

- Rotar mantiene ViewModel y preview; no crea cliente, servicio ni solicitud nuevos.
- Home/otra app detienen preview y liberan Media3/RTP/RTCP. STOP_PREVIEW 260 usa el
  cliente del servicio cuando TCP está disponible. No hay preview en background.
- Volver no reinicia preview. Desvincular o destruir la UI no cierra la conexión:
  el servicio está iniciado además de vinculado. Un nuevo Binder observa el estado real.
- Desconectar desde UI o notificación ejecuta la misma limpieza idempotente:
  libera el preview de la UI, TCP, NetworkRequest y binding, elimina notificación
  y llama a stopSelf. Si la UI aún está vinculada, Android puede conservar la
  instancia inactiva del servicio hasta el unbind; no quedan conexión ni FGS activos.
- `START_NOT_STICKY`: tras muerte del proceso/force-stop no se reconstruye la sesión,
  no se recuperan credenciales y no se solicita Wi-Fi sin una nueva acción del usuario.
- No hay reconexión Wi-Fi automática, keepalive de protocolo, WakeLock, WifiLock,
  WorkManager, CompanionDeviceManager, persistencia, cambio de routing global ni Hito 5.

El historial limitado existente añade estados SERVICE/WIFI/TCP, vínculo de UI,
suspensión durante BLOCKED, intento tras UNBLOCK, éxito con token nuevo (sin
registrar su valor) o fallo. No se registra contraseña, token, BSSID ni número de serie.

La prueba física confirmó el comportamiento esperado del FGS al cambiar entre
YiActionController, Home y navegador: el servicio y la sesión de cámara se
mantienen, Internet sigue funcionando por la red predeterminada y no aparece un
nuevo diálogo Wi-Fi. Si Android envía un bloqueo temporal, se conserva la Network
y la sesión TCP se recupera al desbloquearse. La callback sigue sin explicar por
qué Android bloqueó el acceso.

Referencias oficiales:
[tipo connectedDevice](https://developer.android.com/develop/background-work/services/fgs/service-types),
[arranque foreground](https://developer.android.com/develop/background-work/services/fgs/launch),
[servicios started + bound](https://developer.android.com/develop/background-work/services/bound-services),
[NetworkCallback](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback).

### Cubierto por tests

Verificación final del **10/09/2026**: `:app:testDebugUnitTest :app:assembleDebug
:app:lintDebug` termina con **BUILD SUCCESSFUL**. **74 tests pasan**, sin fallos,
errores ni tests omitidos. Lint: **0 errores y 17 advertencias** (las existentes;
no se actualizan dependencias como parte de este hito). APK debug:
`app/build/outputs/apk/debug/app-debug.apk`.

Tests JVM de BLOCKED idempotente, conservación de candidate/Network/request,
capacidades previas al desbloqueo, generación antigua/red distinta ignoradas,
unblock sin nueva solicitud y LOST/disconnect desde bloqueo con una sola liberación.
La política de sesión cubre separación TCP/Wi-Fi, un intento por transición,
misma SocketFactory para reconectar y preview, ausencia de bucles, reintento manual,
limpieza idempotente y cancelación tras pérdida. Un servidor TCP local comprueba
login real con token nuevo, consultas de batería/configuración y borrado del token anterior.

Los contratos de código/Manifest comprueban propietario único, unbind sin
desconexión, preview en UI, acción de notificación por la misma limpieza, FGS
connectedDevice privado, START_NOT_STICKY, permisos y ausencia de credenciales en
Intent. No simulan el lifecycle Android, su diálogo Wi-Fi o la notificación real.
Se ejecuta también toda la regresión Hitos 1–4A.

### Validado físicamente

Se conservan las validaciones Hitos 1–3 y los resultados 4A indicados arriba. **Hito 4B validado físicamente por el
usuario:** baseline, background con Home y
navegador durante 1 y 5 minutos, Internet simultáneo, rotación, notificación y
desconexión, apagado de cámara, reconexión, recuperación BLOCKED y
RESTRICT_LOCAL_NETWORK. La sesión TCP permaneció utilizable al volver a la app;
no se mostraron solicitudes Wi-Fi duplicadas.

Resultado: el Hito 4B queda **VALIDADO FÍSICAMENTE** en el teléfono de referencia.

### Plan de pruebas físicas 4B (completado)

**A — Baseline.** Abrir app, conectar YI, confirmar Servicio activo, notificación
y TCP conectado. Iniciar preview y confirmar PLAYING. Probar también denegar
notificaciones: debe permitirse el FGS y la conexión.

**B — Background con navegador.** Desde PLAYING, ir a Home/abrir navegador:
preview se detiene y servicio continúa. Navegar por datos móviles; volver después
de **1 minuto** y repetir esperando **5 minutos**. Copiar historial y comprobar
BLOCKED/UNBLOCKED, continuidad de Network, estado TCP e intentos de recuperación.
Ideal: sin bloqueo, TCP sigue conectado y ningún diálogo Wi-Fi nuevo.
Alternativa aceptable: BLOCKED conserva NetworkRequest, TCP cae, UNBLOCKED
recupera TCP y no aparece otro diálogo. Preview queda detenida en ambos casos.

**C — Rotación.** Con servicio conectado y preview activo, rotar: sin otro servicio,
cliente, request ni consentimiento. No debe obtenerse token nuevo salvo recuperación
real de TCP. Repetir inicio/parada de grabación.

**D — Notificación.** En background tocar notificación y comprobar estado actual.
Volver a background y pulsar **Desconectar**: TCP termina, callback se libera una
vez, servicio deja de estar activo y notificación desaparece. La UI debe mostrar
desconectada al volver; comprobar que Internet continúa.

**E — Apagar cámara.** Con servicio activo, apagar YI; comprobar onLost/error real
de red, limpieza y servicio detenido sin nueva solicitud automática. Si solo
llega fallo TCP sin pérdida de Network, conservar Wi-Fi y comprobar reintento
manual o Desconectar; no confundirlo con onLost.

**F — Recuperación BLOCKED.** Si se reproduce, comprobar que no se libera callback.
Volver a foreground, observar UNBLOCKED, un intento TCP con nuevo login/token y
ausencia de diálogo Wi-Fi. Si falla, sin bucles y con reintento manual disponible.

**G — Internet simultáneo.** Con servicio manteniendo YI, abrir navegador, comprobar
Internet por datos móviles, volver y verificar cámara. Repetir conexión/control/
preview con RESTRICT_LOCAL_NETWORK habilitado siguiendo los comandos de 4A.

Verificación local requerida:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

## Hito 5A — exploración read-only de microSD

### Validado físicamente

Los hitos 1–4B y las pruebas del plan 4B fueron superados con la cámara real,
según confirmación del usuario. El experimento 5.0 validó estos comandos con
token dinámico en la YDXJ01XY / YDXJ_v23L / YDXJv25L_1.5.12:

| Operación               | Comando validado                                       |
|-------------------------|--------------------------------------------------------|
| Capacidad total / libre | `msg_id=5 type="total"` / `type="free"`                |
| Directorio actual       | `msg_id=1283 param="."`                                |
| Raíz SD                 | `msg_id=1283 param="/tmp/fuse_d"`                      |
| Entrar en directorios   | `msg_id=1283 param="DCIM/"` y `"100MEDIA/"`            |
| Subir                   | `msg_id=1283 param="../"`                              |
| Listar                  | `msg_id=1282 param=" -D -S"` (incluye espacio inicial) |

Estructura observada, no impuesta a otras cámaras ni tarjetas:

```text
/tmp/fuse_d
├── MISC
└── DCIM
    └── 100MEDIA
```

Cada elemento del array listing tiene una propiedad:
`filename -> "<bytes> bytes|yyyy-MM-dd HH:mm:ss"`.
Los directorios se reconocen por la barra final, no por tamaño cero.
JPG/JPEG, MP4 y THM se clasifican sin distinguir mayúsculas; se conservan OTHER
y las entradas con metadatos incompletos. MP4/THM se asocian por basename
case-insensitive en el mismo directorio. Un MP4 sin THM sigue siendo válido.

Los valores de capacidad brutos observados (31154688 total y 29852096 libres)
se interpretan como **KiB por la prueba física**, no como bytes del protocolo.
La conversión centralizada multiplica por 1024 y rechaza negativos, overflow o
libre mayor que total. La UI muestra GiB/MiB. Conservamos timestampRaw:
hay fechas de 2008 y 2023 y no representan una cronología fiable.

### Implementado y validado físicamente

La sección **Medios**, separada de Control, consulta total/free y abre DCIM
sin asumir el nombre de su subdirectorio. Ofrece Subir, Raíz SD y Actualizar.
Actualizar consulta capacidad, verifica el directorio actual y vuelve a listar,
sin cambiarlo. La raíz permitida es exclusivamente `/tmp/fuse_d`; las rutas
de UI y los nombres recibidos se validan antes de utilizarlos.

CameraConnectionService conserva el repositorio y su estado en memoria.
Se utiliza su único CameraClient, token, lector JSON y política de una petición
TCP en vuelo. Cada operación tiene argumentos y finalización propios aunque
comparta msg_id. No existe otro socket/cliente/cola TCP para la galería.
Rotar conserva pantalla y directorio. Abrir Medios no inicia ni detiene preview;
se puede listar durante grabación si la cámara acepta los comandos.

Al salir a background se deja terminar la petición actual, pero se corta la
secuencia antes de enviar otra. Un LIST ya enviado conserva su resultado.
No hay polling, navegación automática al volver ni sincronización automática.
Los errores de red, SD, timeout, rval, formato y rutas se muestran sin volcar
el listado completo al diagnóstico. El orden es por nombre descendente,
no cronológico; los directorios aparecen primero.

Solo hay iconos genéricos: la asociación THM es metadato, **no descarga**.
No se implementan descarga, reproducción de archivos, borrado, escritura,
miniaturas remotas, persistencia ni ninguna funcionalidad 5B.

### Cubierto por tests

Fixtures físicas sanitizadas, clasificación y metadatos inválidos, asociación
opcional MP4/THM, límites de navegación y conversión segura de capacidad.
Repositorio: carga inicial, refresh sin cambio de directorio, vuelta a raíz,
duplicados, reobservación sin recarga, finalización en background y rechazo
de resultados de sesiones antiguas. Servidor TCP local: argumentos exactos,
tokens dinámicos nuevos, msg_id repetidos, eventos intercalados, exclusión de
peticiones, cancelación del consumidor, rval=-4, timeout y desconexión.
Se ejecuta además la regresión de los hitos anteriores.

### Plan de prueba física ejecutado — Hito 5A

1. Conectar la cámara desde YiActionController.
2. Abrir Medios.
3. Comprobar capacidad total/libre.
4. Comprobar DCIM (destino inicial; desde Raíz SD puede abrirse de nuevo).
5. Abrir 100MEDIA o el directorio presente en la tarjeta.
6. Verificar JPG/MP4.
7. Comprobar tamaños.
8. Comprobar que THM asociado no aparece como vídeo separado.
9. Volver a DCIM.
10. Volver a Raíz SD.
11. Comprobar que no se puede subir por encima de /tmp/fuse_d.
12. Actualizar listado.
13. Rotar el teléfono.
14. Comprobar conexión y pantalla coherentes.
15. Volver a Control.
16. Comprobar preview, foto y grabación; repetir listado con preview/grabación.

Todas las comprobaciones anteriores fueron ejecutadas satisfactoriamente con
la cámara real. No se borró ni descargó ningún archivo. La validación incluye
la integración de la pantalla, la navegación segura, la rotación y la
compatibilidad con preview, fotografía y grabación.

## Hito 5B — descarga fiable de medios

### Protocolo validado físicamente — experimento 5.1

Pruebas comunicadas por el usuario con YDXJ01XY, hardware YDXJ_v23L,
firmware YDXJv25L_1.5.12, Android 16 y targetSdk 36:

- Control TCP 7878 y datos binarios TCP 8787.
- msg_id=1285, token dinámico, param=filename, offset y fetch_size.
- size representa el tamaño total; rem_size, los bytes de esta petición.
- THM completo: 72582 bytes; JPEG Exif 640×360.
- JPG completo: 3310683 bytes; JPEG Exif 4608×3456.
- MP4 completo: 18178802 bytes; H.264 Main 1920×1080 59.94 fps, AAC LC
  48 kHz estéreo, duración 6.49 s. La transferencia tardó aproximadamente 65 s.
- get_file_complete observado; get_file_fail observado al cerrar el receptor
  prematuramente.
- Dos peticiones THM de 4096 bytes con offsets 0 y 4096 reconstruyeron los
  primeros 8192 bytes del original: comparación cmp=0.
- HTTP :80 devolvió Connection refused. **HTTP no se usa en esta cámara.**

El md5sum del evento tiene semántica no confirmada: no coincidió con el MD5
de los fragmentos aun cuando la reconstrucción fue correcta. No se usa para
validación; el diagnóstico solo informa de su presencia.

### Implementado y validado físicamente — A–H + cancelación desde notificación

El usuario comunica que todas las pruebas físicas A–H han sido superadas.
Cancelar desde la aplicación, Cancelar descarga desde la notificación y
Desconectar desde la notificación funcionan correctamente.

Se limita la actualización del progreso de la notificación a una vez por segundo,
sin republicar porcentajes idénticos. Los cambios de estado/acciones son inmediatos.
La reconstrucción frecuente era un riesgo identificado, no una causa física
confirmada del fallo. Android advierte que puede descartar actualizaciones demasiado
frecuentes
([documentación de notificaciones](https://developer.android.com/develop/ui/compose/notifications/create-notification)).
El servicio registra «DOWNLOAD Cancelar recibido desde notificación; estado=…»
antes de llamar a la misma cancelación utilizada por la UI.
La cancelación desde la notificación se comprobó durante una descarga con la app
en segundo plano: se conserva el parcial reanudable, se cierra 8787 y permanecen
vivos Wi-Fi y TCP de control. El diagnóstico de recepción de la acción queda
disponible para futuras regresiones.

CameraConnectionService posee CameraMediaDownloader. La UI observa su estado;
rotación, Home o abrir otra app no cancelan la transferencia. Se conserva el
único CameraClient y la única sesión de control/token. El socket de datos
se crea con el SocketFactory del mismo CameraNetworkBinding YI. No se modifica
el routing global ni se pide Internet a la cámara.

Antes de transferir se exige RecordingState.IDLE y se detiene el preview
limpiamente desde su propietario UI. No se reinicia al terminar. Una reserva
exclusiva impide que otras acciones cambien el working directory o interfieran
con 1285. Foto, grabación, navegación, refresh y preview manual quedan bloqueados
durante la transferencia: restricción conservadora pendiente de flexibilizar
mediante pruebas físicas. Las acciones de limpieza siguen disponibles.

Secuencia: confirmar CHANGE_DIRECTORY (parent), abrir 8787, enviar 1285 por
7878, validar ACK y leer exactamente rem_size bytes. Se usa un buffer fijo de
64 KiB y contadores Long; lecturas parciales son normales. Se cierra el socket
al alcanzar el objetivo sin esperar EOF. Connect timeout: 5 s; inactividad
de datos: 15 s. El timeout del ACK sigue siendo el de control; no hay un límite
total de 5 s para el vídeo.

Una descarga nueva pide offset=0 y fetch_size=tamaño. Reanudar toma la longitud
del temporal y pide los bytes restantes; el ACK debe conservar el tamaño remoto
esperado. Un rem_size menor produce un parcial pausado y permite otra reanudación
manual, sin bucle automático de comandos. Un tamaño diferente impide append y
requiere Descartar antes de iniciar otra descarga.

Tras DATA_COMPLETE se esperan hasta 3 s de confirmación. Con todos los bytes
del archivo y sin error se publica incluso si falta el evento, indicando
«Descargado; confirmación de cámara ausente». Un get_file_fail produce error y
conserva el parcial válido. Estos eventos no identifican el archivo: la reserva
anterior conserva una barrera hasta recibir su evento terminal o renovar TCP.
La siguiente transferencia espera hasta 5 s; si la barrera continúa, ofrece
«Reiniciar sesión TCP». Esta acción explícita conserva Wi-Fi y el parcial,
obtiene un token nuevo y permite Reanudar. No se atribuye un evento antiguo al
archivo nuevo. Un get_file_fail tardío actualiza el resultado sin borrar
automáticamente los bytes completos ya publicados.

Cancelar cierra 8787, cancela la lectura y conserva los bytes escritos sin
cerrar 7878/Wi-Fi/FGS. Un get_file_fail posterior pertenece a esa transferencia,
no a un error global. BLOCKED pausa y conserva el parcial; UNBLOCKED no reinicia
1285 automáticamente. Reanudar es manual. Descartar elimina únicamente el
temporal privado. Desconectar explícitamente cancela, descarta el parcial y
libera los recursos de conexión. LOST cancela; si el servicio se destruye se
limpian sus temporales.

Los parciales están en el directorio privado filesDir/yi-transfers. No se
ofrece resume tras process death: no hay metadatos de reconstrucción, credenciales
persistidas ni arranque automático de Wi-Fi. En el siguiente inicio del servicio
se limpian exclusivamente sus temporales antiguos.

Solo un JPG/MP4 completo se copia a MediaStore Images/Video, con nombre original
y MIME image/jpeg o video/mp4. En API 29+ se usa IS_PENDING durante publicación
y Pictures/YI Action Camera o Movies/YI Action Camera. La publicación local
completa su commit; Cancelar se deshabilita en ese paso. Si falla, se elimina
solo la URI recién creada y el temporal completo permite «Guardar de nuevo»
sin otra descarga. Abrir usa ACTION_VIEW con permiso de lectura de la URI.
API 26–28 solicita READ_EXTERNAL_STORAGE y WRITE_EXTERNAL_STORAGE, limitados a
maxSdkVersion=28, al descargar para guardar y abrir el archivo; no solicita
permisos legacy en Android moderno.
Referencia: [almacenamiento compartido de Android](https://developer.android.com/training/data-storage/shared/media).

Los THM se conservan exclusivamente en archivos privados, nunca en MediaStore.
Se decodifican como JPEG con dimensiones y tamaño limitados; si falla se mantiene
el icono genérico del vídeo. Caché por ruta remota+tamaño, máximo 32 entradas /
16 MiB; se descartan THM mayores de 8 MiB. Solo se solicitan miniaturas de vídeos
visibles en Medios y con preview detenido, una a la vez. No se solicitan en
background. Una descarga explícita tiene prioridad y cancela la miniatura activa
antes de comenzar. Los fallos de miniatura no marcan el vídeo como fallido.

No se implementan borrado remoto, 1281, upload/1286, rename, move, formato,
selección múltiple, sincronización, HTTP, DownloadManager, WorkManager,
reproductor propio, Hito 5C ni Hito 6.

### Pruebas automatizadas

Validación de ACK con Long, campos ausentes y límites; eventos incompletos y
MD5 no interpretado; lectura incremental de 3310683 bytes con buffer fijo,
sin leer después del objetivo; EOF temprano, timeout y error de almacenamiento.
Servidores locales de control/datos verifican orden de apertura, argumentos
numéricos, token dinámico, una transferencia activa, cierre sin EOF del servidor,
append/reanudación, cancelación, barrera de eventos, cambio de tamaño,
BLOCKED, caché THM y publicación exclusivamente tras completar.
MediaPublisher se sustituye en JVM para verificar fallos y reintento de
publicación sin volver a abrir el socket. MediaStore real, notificación, visor
externo y callbacks Android se comprueban físicamente.

### Matriz física superada — Hito 5B

| Prueba          | Pasos y resultado esperado                                                                                                                           |
|-----------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| A — THM         | Abrir Medios/100MEDIA. Comprobar miniatura real del vídeo, fichero privado y ausencia de THM en la galería Android.                                  |
| B — JPG         | Descargar YDXJ0257.jpg, observar progreso, abrir y comprobar resolución 4608×3456.                                                                   |
| C — MP4         | Descargar YDXJ0251.mp4 hasta 100 %, abrir y comprobar audio/vídeo.                                                                                   |
| D — Cancelación | Cancelar MP4 grande al 20–50 %. Comprobar parcial y continuidad de Wi-Fi/control TCP.                                                                |
| E — Resume      | Reanudar; verificar offset > 0 en diagnóstico, tamaño final idéntico al remoto y reproducción correcta.                                              |
| F — Background  | Durante descarga, Home/navegador con datos móviles. Comprobar Internet y progreso continuo o PAUSED si Android bloquea la red; reanudar manualmente. |
| G — Rotación    | Rotar descargando. Progreso coherente, sin reinicio ni socket duplicado.                                                                             |
| H — Desconectar | Pulsar Desconectar en notificación durante descarga. Comprobar cierre de datos/control, liberación Wi-Fi y servicio parado.                          |

Las pruebas A–H están físicamente validadas según el resultado comunicado por el
usuario. La corrección preventiva de Cancelar desde la notificación requiere
una prueba adicional; no se da por resuelta por los tests JVM.
Verificación de entrega:

    ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
