# Cliente Android (nativo, Kotlin)

Cliente móvil del parqués distribuido (NOTA 1 del enunciado). Habla con el servidor por
WebSocket usando el mismo protocolo JSON que el cliente de escritorio y el web.
Sin XML de layouts: la interfaz se construye por código. Única dependencia: OkHttp.

## Compilar y ejecutar
1. Abrir la carpeta `android/` con **Android Studio** (Koala o superior, JDK 17) y esperar el *Gradle sync*.
2. Arrancar el servidor en el PC: `python -m server.main --port 5050 --web-port 8080`
3. Ejecutar la app (▶):
   - **Emulador:** servidor `10.0.2.2:8080` (valor por defecto).
   - **Celular físico:** servidor `IP_DEL_PC:8080` (misma red Wi-Fi; abrir el puerto 8080 en el firewall).
   - **Nube:** `wss://tu-app.herokuapp.com` (usa `wss://` si el servicio tiene HTTPS).
4. Registrarse → *Unirse* con un color → *Listo*. Con 2+ jugadores (o `+ Bot`) inicia la partida.

APK de prueba: *Build ▸ Build Bundle(s) / APK(s) ▸ Build APK(s)*.

## Archivos (`app/src/main/java/com/parques/client/`)
| Archivo | Función |
|---|---|
| `GameClient.kt` | WebSocket (OkHttp), responde `time_req` para Berkeley |
| `Board.kt` | Geometría del tablero (igual que `common/board.py`) |
| `BoardView.kt` | `View` personalizada que dibuja el tablero; tocar una ficha selecciona su jugada |
| `MainActivity.kt` | Login, sala, partida, recomendación, estadísticas y chat |

> Nota: este proyecto se escribió sin poder compilarlo en el entorno de desarrollo del asistente;
> si Android Studio marca algún error de versión (AGP/Kotlin/Gradle), basta con aceptar la
> actualización sugerida.
