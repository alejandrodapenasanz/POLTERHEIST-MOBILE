# POLTERHEIST Mobile

Primera app Android de POLTERHEIST para mostrar una imagen o un vídeo del móvil en una TV. Aplicación nativa Java, Android 8.0 o superior, interfaz en español. No necesita el PC durante el uso.

## Instalar y usar

La compilación de desarrollo está en `artifacts/POLTERHEIST-Mobile-0.1.0-debug.apk` cuando se ha ejecutado el empaquetado local. La salida original de Gradle es `app/build/outputs/apk/debug/app-debug.apk`.

1. Copia la APK al móvil, ábrela y permite instalar desde esa aplicación cuando Android lo solicite. Es una APK de desarrollo firmada con la clave debug, no una publicación de Play Store.
2. Lo más sencillo es conectar el móvil y la TV a la misma red local (la TV puede usar Ethernet). Si no es posible, mira [Detectar TVs fuera de tu red](#detectar-tvs-fuera-de-tu-red).
3. Abre POLTERHEIST y elige una imagen o un vídeo con el selector de Android.
4. Pulsa **Buscar TVs**, selecciona un receptor y pulsa **Enviar a la TV**. En Cast, espera a que la conexión termine. Acepta en la TV cualquier solicitud que muestre su sistema.
5. Usa **Pausar**, **Reanudar** y **Detener**, o detén el envío desde la notificación.

La búsqueda no inicia reproducción. Seleccionar Cast abre su aplicación receptora; el archivo se carga al pulsar Enviar. Se controla **una TV por sesión**. Para cambiar de TV o archivo, detén primero la emisión.

## Vías de conexión reales

| Vía | Requisito del televisor | Funciones de esta versión |
| --- | --- | --- |
| Google Cast | Receptor de vídeo Google Cast activado; móvil con Google Play Services | Descubrimiento oficial, conexión, imagen/vídeo, pausa, reanudación, repetición y cierre |
| DLNA/UPnP | Anunciar `MediaRenderer` con `AVTransport`, `SetAVTransportURI`, `Play` y `Stop` | SSDP en Wi-Fi/Ethernet, búsqueda dirigida por IPv4, imagen/vídeo y controles anunciados por la TV |
| Navegador | Navegador de TV con HTTP local, JavaScript y soporte del contenido | Enlace local que se escribe en la TV; imagen/vídeo, reproducción, pausa, bucle y parada desde el móvil |
| Otra subred | IP enrutable desde la red del móvil y DLNA en la TV | SSDP unicast a una IP, red /22–/32 o rango + URLs DLNA habituales (Samsung, Sony, genéricas) |
| Wi-Fi Direct | TV con Wi-Fi Direct / Screen Mirroring activado | Descubrimiento y conexión directa sin router; después DLNA o navegador sobre el enlace directo |
| Bluetooth | TV visible por Bluetooth (clásico o BLE) | Solo detección y emparejado; puente automático a Wi-Fi Direct con la misma TV |
| mDNS | TV que anuncie Google Cast, AirPlay, Android TV o Fire TV | Detección y sondeo DLNA directo a su IP |
| Red del móvil | TV con Wi-Fi que pueda unirse a otra red | El móvil crea una red local sin Internet (LocalOnlyHotspot); la TV se une y se usa DLNA o navegador |
| Duplicación del sistema | Función compatible en el móvil y en la TV | Acceso a ajustes de Android y vista local a pantalla completa; la conexión y proyección las realiza el sistema |

**No existe compatibilidad garantizada con todos los modelos del mercado.** La marca por sí sola no demuestra compatibilidad: importa el receptor que tenga activo cada modelo. Samsung, LG y otras marcas pueden ofrecer DLNA o navegador, según modelo y configuración. Algunas TVs Android no tienen Cast habilitado. Se pueden listar dos entradas de una misma TV si anuncia Cast y DLNA; son dos métodos distintos.

Esta versión no implementa envío AirPlay, control específico de Roku/Fire TV/webOS/Tizen, instalación de apps en la TV, Bluetooth para vídeo ni un emisor Miracast propio (AirPlay/Fire TV solo se usan para detectar la TV). Bluetooth no es un sustituto general de Cast/DLNA. Un televisor sin una vía compatible requiere añadir un receptor o usar una conexión externa, por ejemplo HDMI. Abrir los ajustes de duplicación no garantiza que el móvil disponga de Miracast.

## Detectar TVs fuera de tu red

- **Buscar TVs** busca a la vez por SSDP en todas las interfaces locales, mDNS, Wi-Fi Direct y Bluetooth (estos dos con permiso de dispositivos cercanos).
- **Buscar por IP o en otra red** acepta `192.168.2.40`, `192.168.2.0/24` o `192.168.2.10-60`.
- **Wi-Fi Direct**: toca la TV, acepta en la TV y espera. Si la TV ofrece DLNA por el enlace directo aparecerá como DLNA; si no, usa el navegador de la TV (el enlace usa la IP Wi-Fi Direct).
- **Bluetooth**: solo detecta. Al tocarla se busca la misma TV (por nombre) por Wi-Fi Direct y se conecta. También permite emparejar.
- **Crear red del móvil**: se muestra nombre y clave; conecta la TV a esa red y pulsa Buscar TVs o usa el navegador.

## Navegador de la TV

Elige **Usar el navegador de la TV**, selecciona un archivo y pulsa **Enviar a la TV**. Escribe la dirección mostrada, incluido `http://`, puerto y código, en el navegador del televisor. El enlace también funciona en un navegador de otro dispositivo de la misma LAN para comprobar el envío.

El vídeo arranca silenciado por las restricciones de autoplay del navegador. Usa el mando para pulsar **Activar sonido / reproducir** y **Pantalla completa**. La app indica si el navegador está consultando la sesión; esto no verifica visualmente que la TV reproduzca correctamente. La compatibilidad depende del motor y los códecs del navegador.

## Archivos y red

- El selector solo concede acceso al archivo elegido; no se solicita acceso general a tu galería. Los permisos de dispositivos cercanos (Wi-Fi Direct/Bluetooth; ubicación en Android 8–12) se piden solo para buscar fuera de tu red y son opcionales. Se prepara una copia temporal privada en el móvil.
- Imágenes: hasta 50 MiB y 100 megapíxeles de entrada, normalizadas a JPEG, orientación EXIF, transparencia sobre negro y lado máximo 1920 px. Los formatos de entrada dependen del decodificador de Android.
- Para Cast, una imagen se convierte localmente en un clip H.264 de 1280×720, dos segundos, repetido. Requiere un codificador H.264 con entrada YUV flexible; si no está disponible se indica el error y se puede elegir otra vía. El comportamiento de la transición del bucle depende del receptor.
- Vídeos: hasta 1 GiB, con pista de vídeo reconocida por Android. **No hay transcodificación automática de vídeos**. MP4/H.264/AAC es la opción de mayor compatibilidad habitual. HEVC, MKV, HDR, resoluciones altas y otros formatos dependen del televisor.
- DLNA verifica las acciones del descriptor SCPD. No negocia todos los perfiles DLNA/códecs antes del envío; el rechazo del receptor se muestra como error. El bucle usa `SetPlayMode(REPEAT_ONE)` cuando la TV lo anuncia y acepta. Pausa y estado se habilitan según acciones anunciadas.
- El móvil sirve el archivo por HTTP en su IPv4 de Wi-Fi o Ethernet, puerto efímero y URL aleatoria. Admite GET, HEAD, rangos de bytes y CORS para Cast. No expone carpetas ni acepta subidas.
- El servicio en primer plano y su notificación mantienen el envío activo al salir de la pantalla o bloquear el móvil, sujeto a las restricciones de batería del fabricante. Detener cierra el servidor. Un receptor desconectado puede conservar datos que ya hubiera cargado.
- **El móvil debe permanecer conectado durante la reproducción.** No es el modo de payload autónomo del proyecto de escritorio: no se instala ni guarda un reproductor permanente en la TV.
- No se requiere Internet para DLNA o navegador. Google Cast/Play Services y la carga inicial de su receptor pueden necesitarlo.
- El descubrimiento usa IPv4 privada. La búsqueda automática cubre Wi-Fi, Ethernet, Wi-Fi Direct y la red del móvil; otras subredes solo se exploran cuando el usuario escribe una IP, red o rango privado (máximo 1024 direcciones). Para una IP concreta se prueban además URLs DLNA habituales.
- Para otra subred, el router debe enrutar tráfico entre ambas redes y la TV debe poder abrir la IP del móvil (no funciona a través de NAT ni con aislamiento de clientes).

Si no aparece una TV, enciéndela, activa compartir contenido/Cast y revisa la lista de dispositivos permitidos en sus ajustes. Evita Wi-Fi de invitados, aislamiento de clientes y VPN que bloqueen la LAN. Dos bandas Wi-Fi diferentes funcionan si el router las integra en la misma LAN. El enlace de navegador sirve como alternativa cuando el router bloquea el descubrimiento multicast pero permite HTTP entre dispositivos.

## Compilar

Herramientas: JDK 17, Android SDK Platform 35, Build Tools 35.0.0. Gradle 8.11.1 y Android Gradle Plugin 8.9.2 están fijados. El wrapper verifica el SHA-256 oficial de la distribución. `minSdk=26`, `targetSdk=35`; no se solicita `ACCESS_LOCAL_NETWORK` con este target. Al actualizar a target 37 hay que implementar el permiso runtime antes de acceder a la red.

En Android Studio abre esta carpeta, instala el SDK indicado y ejecuta `app`. En terminal, configura `JAVA_HOME` y `ANDROID_HOME` o `local.properties`:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
# En esta máquina, detecta también el JDK local de POLTERHEIST:
.\scripts\build.ps1
# Compila, comprueba y copia la APK con su SHA-256 a artifacts:
.\scripts\package.ps1
# Con un móvil autorizado conectado por USB:
.\.tools\android-sdk\platform-tools\adb.exe install -r .\app\build\outputs\apk\debug\app-debug.apk
```

`scripts/prepare-local.py` es una utilidad para este workspace: copia el SDK ya existente en el proyecto hermano `POLTERHEIST` a `.tools/android-sdk`, escribe `local.properties` y obtiene/verifica el wrapper oficial. No modifica las fuentes del proyecto original ni la configuración global de Java/Android. En otra máquina usa el SDK de Android Studio.

En Linux/macOS se puede usar `sh gradlew assembleDebug testDebugUnitTest lintDebug` con JDK/SDK configurados. `scripts/build.ps1` restaura las variables de entorno temporales al terminar.

## Validación

`testDebugUnitTest` cubre rangos HTTP, descarga/HEAD, revocación de enlaces, cierre, controles del navegador, escape de nombres, parser XML, bloqueo de entidades/referencias a otro host y secuencia real HTTP/SOAP contra un simulador DLNA local. Robolectric comprueba el arranque sin Google Play Services y la conservación del destino al recrear la Activity. `lintDebug` revisa compatibilidad Android y manifiesto.

Estos tests **no sustituyen pruebas con móviles y televisores reales**. No se han validado físicamente Cast, el codificador MediaCodec, descubrimiento Wi-Fi, reproducción DLNA, TV browsers ni ejecución prolongada con el móvil bloqueado en esta entrega.

`node scripts/test-receiver.cjs` ejecuta el JavaScript real del receptor en un pequeño entorno simulado y comprueba carga, pausa/reanudación, repetición, imagen, parada y pérdida/recuperación de conexión. No es una prueba del motor de un navegador de TV.

### Pruebas en un Android conectado por USB

Activa **Depuración USB** y acepta en el móvil la autorización del PC. `adb devices -l` debe mostrar el dispositivo con estado `device`, no `unauthorized`. En Xiaomi/MIUI, las opciones de desarrollador se activan pulsando siete veces Versión de MIUI en Sobre el teléfono; la depuración está en Ajustes adicionales → Opciones de desarrollador. [Instrucciones de Xiaomi](https://www.mi.com/global/support/article/KA-06515/).

```powershell
.\scripts\build.ps1 -Tasks connectedDebugAndroidTest
```

`DeviceValidationTest` usa archivos de color generados dentro de la caché privada de la app; no lee fotos personales. Comprueba que el MediaCodec del dispositivo produzca y decodifique 60 fotogramas H.264 con colores correctos, que el servidor Android responda a rangos y revoque el contenido al detenerse, y que una sesión real de navegador sobreviva a la recreación de la Activity y cierre su servicio. La última prueba requiere Wi-Fi/Ethernet local y se omite explícitamente si no hay red. No envía contenido a un televisor. Los resultados se guardan en `app/build/reports/androidTests/connected/`.

Para validar en casa: prueba primero JPEG y un MP4 H.264/AAC pequeño, verifica la imagen en la TV, pausa/reanuda, bloquea el móvil un minuto y detén desde la notificación. Repite con cada vía disponible. Comprueba además rechazo de permisos/TV, cambio de Wi-Fi, rotación del móvil y un formato no compatible; los errores deben mostrarse sin afirmar que la reproducción fue exitosa.

## Organización y referencias

`AppController` mantiene estado y coordina las sesiones; `ShareService` gestiona ejecución en primer plano; `CastController` integra el SDK oficial; `SsdpDiscovery`, `DlnaClient` y `UpnpXml` implementan descubrimiento/control; `MediaHttpServer` sirve exclusivamente el contenido seleccionado; `MediaImporter` y `ImageClipEncoder` lo preparan. `MainActivity` contiene la interfaz nativa y `assets/receiver.html` el receptor de navegador.

Se ha revisado `C:\dev\POLTERHEIST\tv_control`, especialmente sus adaptadores Cast/DLNA y el servidor multimedia. Esta app adapta el enfoque a APIs nativas Android; no ejecuta Python ni herramientas de auditoría de redes.

Fuentes técnicas: [integración oficial de Cast](https://developers.google.com/cast/docs/android_sender/integrate), [formatos Cast](https://developers.google.com/cast/docs/media), [colas y repetición](https://developers.google.com/cast/docs/android_sender/queueing), [servicios Android en primer plano](https://developer.android.com/develop/background-work/services/fgs/service-types), [permiso de red local](https://developer.android.com/privacy-and-security/local-network-permission), [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec).
