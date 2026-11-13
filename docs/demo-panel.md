# Panel demo local

Desde Git Bash en la raíz del proyecto:

```bash
bash scripts/run-demo.sh
```

Requisitos: JDK 21, Maven, Docker Desktop activo y `.env` con la contraseña de PostgreSQL
del proyecto. El script detecta el JDK/Maven de la instalación Windows conocida; si Maven
está en otro lugar, exportar `MAVEN_BIN` con su ruta. Reutiliza la caché `C:/RTEMP/finanzas-m2`
cuando existe. Ejecuta `package -DskipTests` para servir siempre los recursos actuales;
la verificación completa se ejecuta por separado y en CI.

1. Esperar el arranque de Spring Boot y abrir [el panel](http://127.0.0.1:8090/demo/).
2. Pulsar **Cargar credenciales** y elegir `.env.demo-panel` en la raíz del proyecto.
   Si Windows oculta los archivos de configuración, pegar la ruta completa en el selector.
3. Escribir importe y comercio; elegir gasto o ingreso y una fecha en hora de Bogotá.
4. Pulsar **Enviar evento**. La actividad y la respuesta deben mostrar `HTTP 202` y `Recibido`.
5. Pulsar **Repetir el mismo envío**. El ID se conserva y el contador no aumenta.
6. Pulsar **Consultar estado** en la actividad para volver a leer el estado del backend.
7. Pulsar **Nuevo evento** para crear otro con una clave diferente.

La demo crea/reutiliza una base persistente **`finanzas_demo`**, una cuenta sin saldo inicial y
una conexión propia. No aprovisiona en la base `finanzas`. La credencial aleatoria queda en
`.env.demo-panel`, ignorado por Git y sin imprimirse. Importarla lee el archivo local en memoria;
no lo sube como archivo ni envía su contenido a otro sitio. Las peticiones a la API incluyen
solo la API Key necesaria y el evento o ID solicitado.

## Comportamiento

- Interfaz adaptable a escritorio e iPhone. Solo funciona contra el mismo origen que la sirve;
  no hay URL de backend editable, CORS adicional, librerías externas ni conexión con Nu.
- API Key, configuración y actividad solo en memoria de la pestaña. No se usa localStorage,
  sessionStorage, IndexedDB, cookies de autenticación ni service worker.
- El importe se conserva como cadena decimal; admite coma o punto decimal, sin separadores
  de miles, notación científica, redondeo implícito ni conversión a coma flotante.
- La lista muestra hasta 20 eventos de esta sesión. “En espera” cuenta los visibles;
  los estados se actualizan mediante consulta manual. No es una vista del historial completo de BD.
- Recargar/desconectar borra la sesión visual, no las ingestas aceptadas en PostgreSQL.
- Un envío pendiente de confirmación conserva cuerpo y UUID para repetir exactamente el intento.
  Si se quiere descartar un intento incierto, la interfaz advierte del riesgo de duplicarlo.
- Estos son eventos de ingesta, todavía sin procesamiento del worker: no generan saldo ni filas
  financieras en `transactions`. `Recibido` significa evidencia guardada, no gasto contabilizado.

## Arranque y aislamiento

El script ejecuta `api,demo`, escucha exclusivamente en `127.0.0.1:8090` y permite HTTP solo en
esa demo local. El perfil `demo` habilita los recursos `/demo/**`; el perfil ordinario `api`
los mantiene cerrados. Los endpoints financieros conservan autenticación API Key y permisos.
CSP permite scripts/estilos/conexiones solo del propio servidor; no permite scripts inline,
envíos nativos de formularios, iframes ni bases URL externas.

El aprovisionamiento automático requiere expresamente `finance.demo.seed-enabled=true`, URL
loopback, base `finanzas_demo` y servidor loopback. Sin esas condiciones rechaza el arranque.
Si ya existe `.env.demo-panel`, comprueba su correspondencia con la base antes de reutilizarlo;
no sobrescribe credenciales ni sustituye automáticamente una conexión revocada.
Si se elimina la base o se modifica esa credencial, revisar y resolver el desajuste manualmente.

No compartir `.env`, `.env.demo-panel` ni `.env.shortcuts`. Conservar la clave de cifrado de
`.env` mientras existan ingestas: el script no la regenera si ya está configurada.

Si `8090` está ocupado:

```bash
DEMO_PORT=8091 bash scripts/run-demo.sh
```

Detener la API con `Ctrl+C`; PostgreSQL sigue activo y conserva la demo. El script no configura
acceso desde la red: en un iPhone físico, `localhost` se refiere al teléfono. Para acceso desde
otro dispositivo hará falta un despliegue HTTPS y una configuración de red explícita.

## Verificar

```bash
node --test src/test/js/demo-model.test.mjs
# Tras exportar las variables FINANCE_TEST_DB_* de la guía del backend:
mvn verify
```

Las pruebas de infraestructura usan esquemas aleatorios, no la base de demostración.
