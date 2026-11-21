# TODO — POLTERHEIST Mobile

Documento de traspaso entre agentes/desarrolladores. **Léelo entero antes de tocar código.**
Actualízalo al terminar cada tarea: mueve lo hecho a "Hecho" y deja claro qué queda y qué hay que probar.

---

## Contexto rápido

- App Android nativa en Java (minSdk 26, targetSdk 35), UI construida por código en `MainActivity` (sin XML de layouts).
- Vías de envío: Google Cast (`CastController`), DLNA/UPnP (`SsdpDiscovery`, `DlnaClient`, `UpnpXml`), navegador de la TV (`MediaHttpServer` + `assets/receiver.html`).
- `AppController` = estado global y coordinación. `ShareService` = servicio en primer plano durante el envío.
- El móvil sirve el archivo por HTTP en una IP local concreta (`LocalNetwork.address`): la TV tiene que poder llegar a esa IP.

---

## Petición del usuario (tarea 1)

> "Hasta ahora solo detecta las teles de mi red. Si está en otra red, o se detecta una TV mediante otra manera
> (Bluetooth o CUALQUIER método disponible desde un móvil), quiero que se detecte y se pueda conectar."

Restricción del usuario: **no ejecutar pruebas; solo programar.** El usuario hará las pruebas funcionales.
Nada de lo de abajo se ha compilado ni probado (no hay Android SDK en la máquina donde se programó).

---

## Hecho (tarea 1 — detección y conexión fuera de la red local)

### Ficheros nuevos
| Fichero | Qué hace |
| --- | --- |
| `IpRange.java` | Parsea `IP`, `IP/prefijo` (/22–/32) o `IP-últimoOcteto`. Solo IPs privadas RFC1918, máx. 1024 hosts. |
| `NearbyDevice.java` | Modelo de TV "detectada pero aún no reproducible" (Bluetooth, Wi-Fi Direct, mDNS sin DLNA). `sameTv()` compara nombres entre radios. |
| `NearbyPermissions.java` | Permisos runtime por versión: `NEARBY_WIFI_DEVICES` (33+) o `ACCESS_FINE_LOCATION` (≤32); `BLUETOOTH_SCAN/CONNECT` (31+). |
| `WifiDirectController.java` | Wi-Fi Direct (P2P): descubrir peers, filtrar TVs (tipo WPS 7/8, Miracast sink, nombre), conectar (`groupOwnerIntent=0`), desconectar, detectar grupo formado. |
| `BluetoothTvDiscovery.java` | Bluetooth clásico (inquiry) + BLE + dispositivos ya emparejados. Filtra TVs por clase BT (AUDIO_VIDEO display/monitor/set-top) o nombre. Emparejar (`createBond`). |
| `MdnsDiscovery.java` | DNS-SD con `NsdManager`: `_googlecast`, `_androidtvremote2`, `_airplay`, `_amzn-wplay`. Resolución serializada. |
| `HotspotController.java` | `LocalOnlyHotspot`: el móvil crea una red Wi-Fi sin Internet para que la TV se una. Muestra SSID/clave. |

### Ficheros modificados
- `LocalNetwork.java`: ahora soporta varias rutas. `findAll()` añade interfaces Wi-Fi Direct (`p2p*`) y hotspot (`ap*`, `swlan*`, `softap*`, `wlan1/2`) vía `NetworkInterface` (con `network == null`). `forPeer()` elige la interfaz que contiene la IP de la TV o, si no, la red principal (ruta por router). `open()` y `bind()` funcionan sin `Network`. `find()` mantiene su comportamiento (Wi-Fi/Ethernet) para Cast y los tests existentes. En Robolectric no enumera interfaces reales.
- `SsdpDiscovery.java`: nuevo overload con lista de destinos (unicast a IP/rango, **incluidas otras subredes**); ya no rechaza IPs fuera de la subred. Para una IP sola prueba además URLs de descriptor conocidas (`KNOWN_DESCRIPTIONS`: Samsung `:9197/dmr`, Sony `:52323/dmr.xml`, genéricas `:49152-49154/description.xml`, `:1400`). Marca cada `DlnaDevice` con `via` ("Wi-Fi", "Wi-Fi Direct", "Red del móvil", "otra red").
- `DlnaDevice.java`: campo `via` + `withVia()`; constructor antiguo conservado (tests y `UpnpXml` sin cambios).
- `AppController.java`:
  - `scan(null)`: SSDP en paralelo en todas las rutas + mDNS + Wi-Fi Direct + Bluetooth (estos dos solo si hay permiso). Funciona aunque no haya Wi-Fi.
  - `scan("ip|cidr|rango")`: búsqueda dirigida en cualquier subred privada.
  - mDNS → sondeo DLNA directo a esa IP (`probeHost`); si aparece DLNA se elimina la entrada mDNS.
  - Wi-Fi Direct: al formarse el grupo, espera IP del interfaz p2p y busca DLNA (unicast al GO + multicast). Si no hay DLNA, sugiere navegador.
  - Bluetooth → `bridgeBluetooth()`: busca por Wi-Fi Direct un peer con el mismo nombre y conecta (timeout 20 s con sugerencias).
  - `networkFor(target)`: DLNA usa la interfaz que llega a la TV; navegador prioriza Wi-Fi Direct > red del móvil > Wi-Fi; Cast usa Wi-Fi/Ethernet.
  - Hotspot: `startHotspot()/stopHotspot()`; si se cae la red usada en un envío, se llama a `networkLost()`.
- `ShareService.java`: comparación de `Network` null-safe (rutas Wi-Fi Direct/hotspot no tienen `Network`).
- `MainActivity.java`: lista unificada (Cast + DLNA + cercanas), diálogos por tipo (Wi-Fi Direct / Bluetooth / mDNS), botón "Buscar por IP o en otra red" (acepta rangos), estado y botón de desconectar Wi-Fi Direct, botón "Crear red del móvil para la TV" con SSID/clave, petición de permisos de dispositivos cercanos (una vez al pulsar Buscar TVs; siempre al usar una función que los necesita), textos de ayuda.
- `AndroidManifest.xml`: `CHANGE_WIFI_STATE`, `NEARBY_WIFI_DEVICES` (neverForLocation), ubicación (maxSdk 32), Bluetooth legacy (maxSdk 30), `BLUETOOTH_SCAN` (neverForLocation), `BLUETOOTH_CONNECT`, features opcionales wifi.direct/bluetooth/bluetooth_le.
- `README.md`: tabla de vías ampliada, sección "Detectar TVs fuera de tu red", notas de privacidad/red actualizadas.

---

## Pendiente — debe probarse (por el usuario, en dispositivos reales)

### 0. Compilación (primero)
- [ ] `.\gradlew.bat assembleDebug testDebugUnitTest lintDebug` (o `scripts\build.ps1`). Se programó sin compilar: corregir errores de compilación/lint si aparecen.
  - Puntos con más riesgo: APIs `WifiP2pWfdInfo` (API 30), `SoftApConfiguration`, `ContextCompat.registerReceiver`, lint `MissingPermission` (suprimido a nivel de clase en `WifiDirectController`, `BluetoothTvDiscovery`, `HotspotController`).
- [ ] `ActivitySmokeTest` (Robolectric) sigue pasando con los nuevos controladores creados en el constructor de `AppController`.

### 1. Permisos
- [ ] Android 13+: al pulsar "Buscar TVs" la primera vez se piden "Dispositivos cercanos" (Wi-Fi y Bluetooth). Denegar → la búsqueda LAN sigue funcionando y se muestra la pista de permisos.
- [ ] Android 12: pide Bluetooth + ubicación. Android 8–11: pide ubicación (y debe estar la ubicación del sistema activada para Wi-Fi Direct/BLE/hotspot).
- [ ] Revocar permisos desde Ajustes y comprobar que no hay crashes (SecurityException).

### 2. Misma red (regresión)
- [ ] Cast y DLNA en la misma Wi-Fi siguen funcionando igual que antes (envío, pausa, stop, bucle, notificación).
- [ ] Modo navegador sigue mostrando la IP Wi-Fi cuando no hay Wi-Fi Direct ni red del móvil activos.
- [ ] No aparecen duplicados raros (misma TV como Cast + mDNS: la mDNS debería ocultarse si el nombre coincide).

### 3. Otra subred
- [ ] TV en otra subred enrutada (p. ej. móvil 192.168.1.x, TV 192.168.2.x con routing entre ellas): "Buscar por IP" con la IP exacta → aparece como DLNA "· otra red" → enviar y reproducir.
- [ ] Mismo caso con `192.168.2.0/24` y con rango `192.168.2.10-60`.
- [ ] Validar `KNOWN_DESCRIPTIONS` con TVs reales (Samsung `:9197/dmr`, Sony `:52323/dmr.xml`). Añadir rutas de LG/Philips/Panasonic/Hisense si se conocen y funcionan.
- [ ] Entrada inválida (IP pública, /16, texto) → mensaje de error, sin crash.

### 4. mDNS
- [ ] TVs con AirPlay / Android TV / Fire TV / Cast aparecen como "mDNS" y, si tienen DLNA, pasan a DLNA tras el sondeo.
- [ ] "Comprobar DLNA" y "Usar navegador" desde el diálogo mDNS.
- [ ] Comprobar en Android < 14 que la resolución serializada no se queda bloqueada.

### 5. Wi-Fi Direct (sin router común)
- [ ] Con el móvil fuera de la red de la TV (o sin Wi-Fi), activar Wi-Fi Direct en la TV → la TV aparece como "Wi-Fi Direct".
- [ ] Conectar → la TV pide aceptar → aparece "Wi-Fi Direct conectado con …".
- [ ] Si la TV ofrece DLNA por P2P (habitual en Samsung): aparece como DLNA "· Wi-Fi Direct" y reproduce.
- [ ] Si no: modo navegador debe mostrar una URL `192.168.49.x` y la TV debe abrirla.
- [ ] Desconectar Wi-Fi Direct durante un envío → se detiene limpiamente y se informa.
- [ ] Verificar que el socket HTTP/SSDP por la interfaz `p2p*` realmente enruta (Android coloca p2p en la red "local" de netd; confirmar en varios fabricantes, sobre todo Xiaomi/MIUI y Samsung).
- [ ] Revisar filtro `looksLikeTv()`: ¿se esconden TVs reales o aparecen móviles/PCs?

### 6. Bluetooth
- [ ] TVs encendidas con Bluetooth aparecen (clásico y BLE). Revisar falsos positivos/negativos del filtro por nombre/clase.
- [ ] "Conectar por Wi-Fi Direct" encuentra la misma TV por nombre y conecta; si no, mensaje con alternativas tras 20 s.
- [ ] "Emparejar Bluetooth" lanza el emparejado.
- [ ] Bluetooth apagado → mensaje "Activa el Bluetooth…".

### 7. Red del móvil (LocalOnlyHotspot)
- [ ] Crear red → se muestran SSID y clave. Conectar la TV → "Buscar TVs" la encuentra por DLNA, o el navegador abre la URL del hotspot.
- [ ] Comprobar en qué fabricantes se corta la Wi-Fi normal (documentar).
- [ ] Verificar que el nombre de interfaz del hotspot se detecta (`ap0`, `swlan0`, `wlan1`…). Si el móvil usa otro nombre, añadirlo en `LocalNetwork.kindOf()`.
- [ ] Apagar la red durante un envío → se detiene y se informa.

### 8. Robustez
- [ ] Rotación del móvil y recreación de la Activity durante búsquedas/conexión P2P.
- [ ] Varias búsquedas seguidas (cancelación por `scanGeneration`).
- [ ] Consumo de batería: Bluetooth/mDNS se detienen solos (12 s / 8 s). Wi-Fi Direct discovery lo detiene el sistema (~2 min).

---

## Pendiente — mejoras posibles (no hechas)

- [ ] Tests unitarios para `IpRange.parse`, `NearbyDevice.sameTv/normalize`, `LocalNetwork.kindOf`, `BluetoothTvDiscovery.nameLooksLikeTv` (el usuario pidió no hacer pruebas en esta entrega).
- [ ] Cast en otra subred / sin red común: no es posible con el SDK oficial (descubrimiento por mDNS local). Valorar documentarlo más o explorar Cast por IP manual (no soportado oficialmente).
- [ ] Mostrar un código QR con la URL del navegador / credenciales de la red del móvil.
- [ ] Permitir elegir manualmente la interfaz para el modo navegador cuando hay varias.
- [ ] Wi-Fi Aware (NAN): prácticamente ninguna TV lo soporta; descartado por ahora.
- [ ] Al subir a targetSdk 37, implementar el permiso runtime `ACCESS_LOCAL_NETWORK` (ver README).
- [ ] Actualizar `DeviceValidationTest` (androidTest) para cubrir `LocalNetwork.findAll()`.
