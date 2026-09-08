# YI Action Controller · Hito 3: vista previa RTSP

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
Network. No conecta automáticamente al Wi-Fi ni modifica la red predeterminada.

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
usuario; quedan pendientes los escenarios indicados al final. En la prueba
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

1. Conectar manualmente el teléfono al Wi-Fi de la YI y pulsar **Conectar**.
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

El manifiesto declara `INTERNET`, `ACCESS_NETWORK_STATE` y
`NEARBY_WIFI_DEVICES` con `neverForLocation`. En Android 13+ se solicita el
permiso de dispositivos cercanos al conectar/iniciar preview si falta. No se
solicita ubicación. Si se deniega, se intenta la operación: en SDK 36 sin la
protección experimental puede funcionar; con ella activa habrá que conceder
el permiso desde ajustes para recuperar el acceso local.

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

`CameraViewModel` mantiene `CameraClient`, el proveedor de Network y
`CameraPreviewController`, que gobierna `Media3PreviewPlayer`. El reproductor
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
No hay servicio ni reproducción en segundo plano.

Preview no bloquea foto/grabación salvo mientras haya un comando TCP pendiente.
El evento `vf_start` de preview no debe marcar como inactiva una grabación en
curso; la solicitud de detener grabación mantiene su semántica del Hito 2.
La simultaneidad real depende del firmware y queda pendiente de validación.

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
- **Implementado, pendiente de validar físicamente:** recuperación explícita
  de 259/-21, datos móviles simultáneos, rotación con imagen,
  segundo plano, grabación con preview y `RESTRICT_LOCAL_NETWORK`.

Prueba física propuesta: conectar con datos móviles activos; iniciar preview;
confirmar imagen/En directo; rotar; iniciar/detener grabación con preview;
detener/reiniciar preview; salir a Home y volver (sin autoarranque); apagar el
Wi-Fi o la cámara; reconectar y reintentar. Repetir con protección local activa.

Limitaciones: UDP sin fallback automático a RTP/TCP; routing
simultáneo con datos móviles aún pendiente de validar en la cámara. Sin
reconexión automática, pantalla completa,
galería, listado/descarga/reproducción de archivos, cambios de resolución,
configuración avanzada, persistencia, base de datos, DI ni funciones del Hito 4.

Verificación final:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```
