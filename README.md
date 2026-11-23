# Parqués Online — cliente Android (nativo, Kotlin)

Cliente móvil del parqués distribuido (NOTA 1 del enunciado). Habla con el servidor de la nube por **WebSocket seguro (`wss://`)**
con el mismo protocolo JSON que usan la web y el escritorio, así que se puede jugar en la misma partida desde cualquier cliente.
La interfaz se construye por código (sin XML de layouts). Única dependencia externa: **OkHttp**.

- Servidor: `https://parques-a6km.onrender.com` (configurado en `Config.kt`).
- Compilación: `minSdk 24` (Android 7.0), `targetSdk 34`, Kotlin, Gradle.

## Qué incluye la app
- **Pantalla de inicio** con ingreso y creación de cuenta, validación de campos, mostrar/ocultar clave y «Recordarme» (guarda un token de sesión, no la clave).
- **Tres pestañas:** *Sala* (puestos, bots, salas privadas, «Jugar rápido»), *Partida* (tablero, dados, jugadas, recomendación) y *Chat* (con el historial de la partida).
- **Tablero** a todo el ancho con animación de fichas y dados; tocar una ficha selecciona su jugada. Lanzamiento inicial visible, con pausa.
- **Reloj de turno** sincronizado con el servidor (Berkeley), con aviso cuando queda poco.
- **Sonidos y vibración**, modo oscuro, textos que se ajustan al tamaño de fuente del teléfono y el teclado no tapa el chat.
- **Reconexión automática** si se cae el internet: dentro de 60 s se recupera el asiento y el estado.
- **Estadísticas:** ranking, probabilidad de ganar e historial de partidas.
- **Actualización automática:** al abrir, consulta el último *Release* del repositorio y, si hay una versión más nueva, ofrece descargarla.

## Compilar
**Opción A — GitHub Actions (la que usamos).** Cada `push` a `main` ejecuta `.github/workflows/android.yml`, que compila el APK y lo publica en
*Releases* (`Versión N`, archivo `parques.apk`). El número de versión es el de la ejecución del workflow, así que siempre crece.

**Opción B — Android Studio.**
1. Abrir esta carpeta con **Android Studio** (Koala o superior, JDK 17) y esperar el *Gradle sync*.
2. Ejecutar (▶) en un emulador o en un celular con depuración USB.
3. APK de prueba: *Build ▸ Build Bundle(s) / APK(s) ▸ Build APK(s)*.

Para probar contra un servidor local cambia `SERVER_URL` en `Config.kt` (por ejemplo `ws://10.0.2.2:8080` en el emulador o
`ws://IP_DEL_PC:8080` en un celular de la misma red) y arranca el servidor con `python -m server.main --port 5050 --web-port 8080`.

## Instalar y actualizar
1. En el celular, abrir la página de *Releases* del repositorio y descargar `parques.apk` del último release; aceptar «instalar apps desconocidas».
2. Las siguientes versiones llegan por el aviso de actualización de la app. Todas se firman con la **misma clave** (`app/parques.keystore`),
   por eso se instalan encima de la anterior sin perder datos.
3. Si una versión instalada antes estaba firmada con otra clave, hay que **desinstalarla una sola vez** antes de instalar la nueva.

## Archivos (`app/src/main/java/com/parques/client/`)
| Archivo | Función |
|---|---|
| `Config.kt` | Dirección del servidor (`SERVER_URL`) |
| `GameClient.kt` | WebSocket (OkHttp), reconexión y respuesta a `time_req` (Berkeley) |
| `Board.kt`, `BoardGeometry.kt` | Geometría del tablero (copia de `common/board.py`, verificada por una prueba del servidor) |
| `BoardView.kt` | `View` que dibuja el tablero y las fichas, con animaciones y toque sobre fichas |
| `DiceView.kt` | Dados animados |
| `Theme.kt` | Colores claro/oscuro y `PillButton` |
| `Feedback.kt` | Sonidos y vibración (captura, pérdida, par, turno…) |
| `MainActivity.kt` | Login, pestañas, sala, partida, chat, estadísticas y actualización automática |

## Recursos
- `res/mipmap-*/ic_launcher.png`: icono de la app (fichas de colores con un dado).
- `res/drawable-nodpi/logo.png`: el mismo logo, en alta resolución, para la pantalla de inicio.

## Notas
- La app no contiene reglas del juego: dibuja el `state` que envía el servidor y manda intenciones (`roll`, `move`, `chat`…).
- El servidor gratuito de Render se duerme si nadie lo usa: el primer ingreso puede tardar hasta un minuto y la app muestra «Conectando…».
