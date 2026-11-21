# Bluetooth y audio en Android

Decisión técnica para POLTERHEIST Mobile 0.2.0. Revisión de documentación oficial: 21 de noviembre de 2026. `minSdk=26`, `compileSdk=35`, `targetSdk=35`. El código compila; las pruebas quedan pendientes por indicación del usuario.

## Qué admite el ecosistema actual

| Tecnología | Para qué sirve | Tratamiento en la app |
| --- | --- | --- |
| Bluetooth Classic / A2DP | Audio multimedia de altavoces y auriculares | Descubrimiento, emparejamiento y reproducción sobre la salida A2DP conectada por Android |
| AVRCP / sesión multimedia | Controles del accesorio y del sistema | `MediaSession` con reproducir, pausar, detener y desplazamiento; los botones disponibles dependen del accesorio |
| BLE / GATT | Anuncios y servicios de accesorios, sensores y otros IoT | Descubrimiento e identificación; no se envía un MP3 a una característica GATT esperando que suene |
| LE Audio | Audio sobre LE, con perfiles y hardware específicos | Se aceptan `TYPE_BLE_HEADSET` y `TYPE_BLE_SPEAKER` si Android los expone conectados |
| Audífonos | Salidas accesibles gestionadas por Android | Se acepta `TYPE_HEARING_AID` desde API 28 |
| Auracast | Audio LE por difusión | No se implementa un transmisor ni un asistente Auracast propio |

Android documenta soporte integrado de LE Audio desde Android 13, con LC3 y nuevos casos de uso. Un accesorio puede anunciar BLE para control/configuración y seguir usando Classic para reproducir. La versión del chip Bluetooth, por sí sola, no confirma compatibilidad con LE Audio. La app utiliza las salidas que expone el sistema, sin afirmar soporte por el nombre o la marca. [Android: LE Audio](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview).

Auracast añade difusión a receptores compatibles y tiene requisitos propios; no es equivalente a conectar varios altavoces A2DP. Esta entrega controla una salida elegida. La duplicación de audio que pudiera ofrecer el fabricante queda bajo control del sistema. [Bluetooth SIG: LE Audio](https://www.bluetooth.com/learn-about-bluetooth/feature-enhancements/le-audio/), [requisitos Auracast](https://www.bluetooth.com/auracast/faq/).

## Descubrir, emparejar y conectar

`BluetoothDiscovery` muestra los dispositivos emparejados y hace búsquedas explícitas: 13 s de inquiry Classic, después 8 s de anuncios BLE. Se limita a 128 direcciones por búsqueda, se detiene al salir de la pantalla y no guarda inventarios en disco. Las entradas se agrupan por dirección dentro de esa búsqueda; una dirección BLE privada puede cambiar. Clase y UUID de Audio Sink/ASCS/PACS son indicios, no una comprobación de reproducción.

En Android 12+ se solicitan `BLUETOOTH_SCAN` y `BLUETOOTH_CONNECT`. En Android 8–11, ubicación precisa para descubrir, más los permisos Bluetooth antiguos declarados hasta API 30. La búsqueda no deriva ubicación física y usa `neverForLocation`; Android puede filtrar algunos beacons. No se pide anunciar el móvil ni permiso de micrófono. La ubicación hasta API 32 del manifiesto se conserva para Wi-Fi Direct/hotspot, no se solicita desde el flujo Bluetooth en Android 12+. [Permisos oficiales](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).

La app inicia `BluetoothDevice.createBond()` y observa cambios de vínculo. Android presenta cualquier PIN o confirmación. Emparejar intercambia credenciales; que un vínculo exista no demuestra que el perfil multimedia esté conectado. [BluetoothDevice](https://developer.android.com/reference/android/bluetooth/BluetoothDevice).

En el SDK 35 utilizado, `BluetoothA2dp` no ofrece un método público general para forzar la conexión del perfil. Se abre `ACTION_BLUETOOTH_SETTINGS` para completar la conexión y activar audio multimedia cuando sea necesario. No se usa reflexión, root ni automatización del PIN. La lista de reproducción se alimenta de `AudioManager.getDevices(GET_DEVICES_OUTPUTS)`, no de la lista de emparejados. [API pública A2DP](https://developer.android.com/reference/android/bluetooth/BluetoothA2dp), [AudioManager](https://developer.android.com/reference/android/media/AudioManager).

Los receivers Bluetooth usan `RECEIVER_EXPORTED` porque los broadcasts protegidos proceden de un proceso privilegiado con UID distinto al de la app y al de system. El filtro contiene solo acciones del sistema; no expone comandos propios. [Recepción de broadcasts de Bluetooth](https://developer.android.com/develop/background-work/background-tasks/broadcasts).

## Reproductor y ciclo de vida

Para esta función de archivo local único se mantiene la base Java nativa y se usa `MediaPlayer` junto con `MediaSession` y un servicio `mediaPlayback`. Google recomienda Media3 para nuevas aplicaciones de reproducción; aquí se prioriza la comprobación directa de la ruta de `MediaPlayer` y el alcance limitado del reproductor. Si se añaden playlists, streaming o una biblioteca, conviene migrar a Media3 manteniendo la validación de salida. [Recomendación de Media3](https://developer.android.com/media/platform/mediaplayer), [arquitectura de reproducción moderna](https://developer.android.com/media/implement/playback-app).

El selector SAF concede acceso al documento elegido. Se copia en un worker a caché privada, hasta 512 MiB, y `MediaExtractor` exige una pista de audio. El nombre no se usa como ruta. La copia nueva reemplaza la anterior solo tras completarse; cancelar o fallar elimina el temporal. Los restos se limpian al crear el controlador en otro proceso. La copia permite seguir reproduciendo sin depender de que el proveedor mantenga abierto el documento. Los formatos dependen del extractor/decodificador del móvil: no hay transcodificación ni descifrado DRM.

En API 28+ se establece `setPreferredDevice`, se arranca silenciado y se consulta `getRoutedDevice`: aceptar una preferencia no prueba el destino real. Solo se habilita sonido cuando coincide la salida Bluetooth seleccionada. Se restaura la posición anterior al arranque silenciado. Una espera de ruta mayor de 3 s pausa; la preparación de archivo tiene límite de 15 s. Los callbacks de ruta y una comprobación cada 500 ms detectan cambios durante la reproducción. Estas señales no verifican acústicamente que el accesorio suene y no eliminan toda latencia del sistema al desconectar. [Contrato de enrutamiento](https://developer.android.com/reference/android/media/AudioRouting), [API MediaPlayer](https://developer.android.com/reference/android/media/MediaPlayer).

API 26/27 no ofrece esas funciones en `MediaPlayer`: se exige A2DP activo y se delega la ruta a Android. La interfaz explica la limitación. También se pausa ante `ACTION_AUDIO_BECOMING_NOISY`, retirada de la salida, pérdida de permisos o pérdida de foco. No se reanuda por sí sola después de una llamada o reconexión. La búsqueda Bluetooth se evita durante reproducción para reducir interferencias.

El servicio entra en primer plano antes de solicitar foco, como requiere Android 15 para apps target 35 que reproducen desde segundo plano. Se usa `USAGE_MEDIA`, pausa ante pérdida de foco incluso temporal y libera foco/recursos al parar. Los callbacks antiguos de foco quedan invalidados. [Foco de audio](https://developer.android.com/media/optimize/audio-focus), [tipo de servicio mediaPlayback](https://developer.android.com/develop/background-work/services/fgs/service-types).

La notificación usa una sesión multimedia y controles de reproducción; esta clase de notificación está exenta del cambio de permiso de Android 13. La reproducción puede continuar con la pantalla bloqueada. En pausa se retira el estado de primer plano y se libera la sesión tras cinco minutos. `START_NOT_STICKY` impide reinicios automáticos del servicio tras terminar el proceso. Las restricciones de batería del fabricante siguen aplicándose. [Permiso de notificaciones y sesiones multimedia](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

## Pendiente de comprobar cuando se reanuden las pruebas

- Permisos concedidos, denegados y revocados; Bluetooth/ubicación apagados; móvil sin radio Bluetooth.
- Emparejamiento nuevo, cancelado, accesorio ya emparejado y accesorio conectado a otro móvil.
- A2DP en el Redmi; LE Audio en otro móvil y accesorio que lo soporten; ruta de Android 8 en un dispositivo adecuado.
- Audio MP3/M4A/WAV/Ogg/FLAC, archivo no compatible, vacío, muy corto, superior al límite, importación cancelada y proveedor de nube sin conexión.
- Pausa/reanudación, seek, repetición, controles del accesorio, notificación y pantalla bloqueada.
- Desconexión durante preparación y reproducción, cambio de salida, llamada, otra app de audio, rotación, salida de la Activity y fin del proceso.
- Regresión de TV/Cast/DLNA y descubrimiento Wi-Fi Direct/hotspot incorporado desde GitHub.

No se ha ejecutado ninguno de estos casos en esta entrega. El commit usa `[skip ci]`; para generar la APK sin ejecutar tests ni lint: `scripts/package.ps1 -SkipTests`.
