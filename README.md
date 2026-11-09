# Tiempo en Pantalla 2.0

El repositorio incluye el APK firmado, el código Android y el servidor para cuentas, amistades y estadísticas compartidas.

## Desplegar en Render

1. Desde tu cuenta de Render, crea un Blueprint con el repositorio `josephao64/apss` y la rama `main`.
2. Revisa el costo del plan Starter y del disco persistente antes de confirmar. El archivo `render.yaml` configura ambos recursos.
3. Cuando el servicio esté disponible, abre **Shell** en el panel del servicio y ejecuta `python server.py admin tu_usuario`. Escribe tu contraseña en el terminal cuando se solicite.
4. Abre `/health` en la URL HTTPS del servicio. Si devuelve `{"ok":true}`, usa la URL base en **Cuenta y amigos**.

## Activar el servidor con Docker Compose

Necesitas un equipo siempre encendido o un VPS con Docker Compose y un dominio o subdominio que apunte a ese equipo. Caddy obtiene el certificado HTTPS cuando el dominio apunta al servidor y los puertos TCP 80 y 443 son accesibles.

1. Copia la carpeta `servidor` al equipo y entra en ella.
2. Crea un archivo `.env` con una línea como `SCREEN_DOMAIN=pantalla.tudominio.com`, usando tu dominio real.
3. Crea tu usuario principal antes de arrancar el servicio público. Escribe la contraseña cuando te la pida el comando.

   ```sh
   docker compose run --rm --no-deps api python server.py admin tu_usuario
   docker compose up -d
   ```

4. Abre `https://pantalla.tudominio.com/health` usando tu dominio. Debe devolver `{"ok":true}`. Usa la dirección HTTPS sin `/health` en cada teléfono.

El volumen `usage_data` conserva cuentas, amistades y estadísticas compartidas. No lo elimines si necesitas conservarlas.

## Instalar y compartir

1. Instala `Tiempo_en_Pantalla_v2.apk` en Android 10 o superior. Tiene la misma firma que la versión 1.0 y puede instalarse encima.
2. Abre **Cuenta y amigos**. Ingresa la dirección HTTPS la primera vez. Usa tu usuario y contraseña de administrador. Tus amigos pueden crear cuentas con solo usuario y contraseña en esa misma dirección.
3. Toca **Generar mi código** y envíalo a la persona que quieras agregar. La otra persona toca **Agregar amigo con código**. El código vence en 24 horas y se usa una sola vez.
4. Cada persona que quiera compartir debe habilitar **Acceso de uso**, permitir las notificaciones y tocar **Compartir en vivo**. La notificación permite pausar la transmisión. La vista de amigos se refresca automáticamente. La red, el sistema y el estado del teléfono pueden demorar las actualizaciones.
5. Toca un amigo para ver el tiempo total y por aplicación. Usa **Elegir día del historial** para consultar los días disponibles. Puedes eliminar una amistad u ocultar tus estadísticas compartidas. Pausar conserva la última copia del historial en el servidor.

La cuenta principal puede desactivar y reactivar usuarios. Para ver las estadísticas de una persona, también necesita establecer una amistad con ella. Las contraseñas tienen un mínimo de 8 caracteres.

## Código Android

`android/` contiene el manifiesto, los recursos y las clases Java. Para compilarlo se usó el SDK 35 y Build Tools 35. Para firmar futuras actualizaciones sobre la aplicación ya instalada se necesita la clave de firma original.
