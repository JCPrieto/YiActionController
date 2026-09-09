# YI Action Controller · Hito 4: conexión Wi-Fi local-only

Android nativo, Kotlin y Jetpack Compose. Control y vista previa de la
Xiaomi YI original YDXJ01XY. Referencia física aportada: hardware `YDXJ_v23L`,
firmware `YDXJv25L_1.5.12`, teléfono Android 16. Estas versiones no se usan
como valores de la UI. Se conserva la arquitectura y el diagnóstico del Hito 1.

## Uso

1. Instala `app/build/outputs/apk/debug/app-debug.apk`.
2. Enciende la cámara y su Wi-Fi. Desde Android 10 introduce el SSID exacto y
   la contraseña en la app. En Android 8/9 conecta manualmente desde Ajustes.
3. Pulsa **Conectar a cámara** y concede los permisos y la autorización del sistema.
   Tras confirmar una ruta local, la app abre `192.168.42.1:7878`, obtiene un token con
   `msg_id=257` y consulta batería (`13`) y configuración (`3`).
4. **Consultar batería y configuración** repite ambas consultas. El socket sigue
   leyendo eventos incluso sin pulsar este botón.
5. **Desconectar** libera preview, socket y solicitud Wi-Fi y borra el estado de la sesión. La siguiente
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

Limitaciones: UDP sin fallback automático a RTP/TCP; conservación de TCP al
abrir otra app pendiente de resolver. Sin
reconexión automática, pantalla completa,
galería, listado/descarga/reproducción de archivos, cambios de resolución,
configuración avanzada, persistencia, base de datos, DI ni funciones del Hito 5.

Verificación final:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

## Hito 4 — conexión local-only

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

Hipótesis pendiente de confirmar: Android puede cerrar los sockets TCP cuando
congela los procesos de una app en caché, aunque la asociación Wi-Fi permanezca
([Cached apps freezer, AOSP](https://source.android.com/docs/core/perf/cached-apps-freezer)).
La diferencia entre Home y abrir otra app es compatible con esa política, pero
este historial no demuestra que sea la causa en el teléfono probado.

Se corrige una pérdida de evidencia: la limpieza Wi-Fi ya no vuelve a llamar
`client.disconnect()` cuando TCP está cerrado, por lo que conserva su error en
el diagnóstico de sesión. El historial añade tipo de fallo (`EOF`, `SOCKET`, etc.)
y errno conocidos, sin copiar mensajes de excepción ni credenciales. También
registra vuelta a foreground y `onBlockedStatusChanged`. Repetir B/D y copiar el
historial para distinguir cierre remoto, error de socket y restricciones de red.
No se añade reconexión automática, keepalive de protocolo ni foreground service.
Verificación de esta revisión: 60 tests pasando, APK debug generado y lint con
0 errores y 17 advertencias. La causa física del cierre TCP sigue pendiente de confirmar.

### Implementación

La conexión manual a la YI dejó al teléfono de prueba sin Internet efectivo aun
con datos móviles activos. El flujo principal desde API 29 solicita una conexión
temporal peer-to-peer con `WifiNetworkSpecifier`, SSID exacto y WPA2. No usa Wi-Fi
Direct, escaneo ni sugerencias de redes. `NetworkRequest` solicita `TRANSPORT_WIFI`
y elimina expresamente `NET_CAPABILITY_INTERNET`.

`CameraWifiConnectionManager`, propiedad del ViewModel y con application context,
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
4G/5G depende del dispositivo/sistema y **está validada en el teléfono de referencia**;
la continuidad de la sesión TCP al abrir otra app sigue pendiente. No se
activa Wi-Fi ni datos móviles desde la app; la concurrencia entre dos Wi-Fi no se
usa como requisito para permitir Wi-Fi más datos móviles.

Los estados observables son `DISCONNECTED`, `REQUESTING`, `CONNECTING` (validación
de ruta), `CONNECTED`, `UNAVAILABLE`, `LOST` y `ERROR`. Android no ofrece una callback
que identifique con certeza cuándo está visible el consentimiento. Tampoco permite
distinguir en `onUnavailable` cancelación/rechazo, red no encontrada y contraseña
incorrecta/fallo de asociación: el mensaje explica las posibilidades. Permiso
denegado, Wi-Fi desactivada, entrada inválida, falta de ruta, pérdida de Network y
fallo de sesión TCP sí tienen errores propios y permiten reintento manual.

El límite de solicitud es 45 segundos, incluida la espera de ruta tras disponibilidad.
No se inicia TCP ni preview en una red sin validar. Al desconectar, fallar, perder
la Network o destruirse el ViewModel se libera la callback exacta una sola vez;
la limpieza tolera que Android ya la haya eliminado. La pérdida libera preview
con `NETWORK_LOST` y cierra TCP; un cierre TCP sin notificación de pérdida mantiene
`CAMERA_DISCONNECTED`. No hay reconexión automática.

La rotación conserva el ViewModel, petición y sesión; los Composables no registran
callbacks. Home detiene únicamente preview. La petición y TCP se conservan mientras
viva el ViewModel y Android mantenga la red; preview no vuelve a iniciarse sola.
No se añade foreground service.

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
de Strings en la JVM. Cada reintento requiere introducir las credenciales de nuevo.
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
