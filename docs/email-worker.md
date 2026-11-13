# Gmail: pagos de tarjeta y extractos

El perfil worker incorpora lectura IMAP de Gmail. Solo el recibo de pago entra en la cola
financiera; el extracto actualiza billing_statements sin generar EXPENSE ni TRANSFER.
NU_EMAIL_ENABLED es false por defecto. No se ha conectado un buzón real durante la implementación.

## Probar sin Gmail ni PostgreSQL

Después de compilar el JAR, desde Git Bash:

```bash
java -jar target/proyecto-finanzas-0.0.1-SNAPSHOT.jar email-preview \
  src/test/resources/nu/statement-preview.eml \
  nu@example.test 2026-10-15T18:00:00Z
```

La fixture es sintética. Debe mostrar OFFLINE_PREVIEW_NO_WRITES, minimum_payment 190000.00,
periodo 2026-09-14 a 2026-10-15, corte 2026-10-15 y vencimiento 2026-11-04.
Un archivo .eml puede contener cabeceras falsificadas: la vista previa no demuestra autenticidad
ni escribe en la BD. No enviar archivos con credenciales, datos de identidad o información personal.

## Conectar Gmail

1. Tener configuradas en la BD una cuenta DEPOSIT y otra CREDIT_CARD del mismo propietario,
   activas y en COP. bank_reference del origen debe coincidir con Producto origen del recibo;
   card_last4 del destino con Producto destino. No usar los valores ficticios de la demo en cuentas reales.
2. Crear la conexión IMAP con sus cuentas permitidas:

```bash
bash scripts/provision-email.sh OWNER_UUID DEPOSIT_UUID CREDIT_CARD_UUID
```

El script usa la base del docker-compose y crea .env.gmail sin sobrescribir un archivo existente.
Si el aprovisionamiento falla, ese archivo no implica que haya una conexión activa; revisar el error.

3. Completar .env.gmail localmente, sin comillas ni comandos de shell:

```dotenv
NU_EMAIL_ENABLED=true
NU_GMAIL_USERNAME=tu-correo@gmail.com
NU_GMAIL_APP_PASSWORD=secreto-local
NU_GMAIL_ACCESS_TOKEN=
NU_EMAIL_FROM=remitente-exacto-del-correo-nu
NU_EMAIL_FOLDER=INBOX
NU_EMAIL_BACKFILL=false
```

No copiar esos valores de ejemplo como credenciales. NU_EMAIL_FROM debe ser la dirección real
From del banco, no el nombre mostrado. No se inventa un remitente Nu predeterminado.
La conexión admite una [contraseña de aplicación con verificación en dos pasos](https://support.google.com/mail/answer/185833?hl=es)
o un access token OAuth2 por NU_GMAIL_ACCESS_TOKEN; en ese caso se usa XOAUTH2.
La renovación OAuth2 no está implementada: un token vencido detiene las lecturas hasta sustituirlo.
No usar la contraseña principal de Gmail ni enviar secretos por chat.

4. Compilar con Maven y arrancar un solo proceso worker:

```bash
bash scripts/run-email-worker.sh
```

Carga únicamente variables permitidas de .env y .env.gmail, sin ejecutar su contenido. Usa
la misma DB y clave AES de la API. La API corre por separado; el worker no abre HTTP.
Detener procesos que estén usando el JAR antes de recompilar en Windows.

Se recomienda una etiqueta/carpeta dedicada a correos Nu. La conexión queda vinculada al
hash del usuario Gmail, carpeta y cuentas; cambiarlos requiere una nueva source_connection.
Un destino explícito representa una tarjeta: si el buzón contiene extractos de varias tarjetas
sin identificador suficiente en el cuerpo, separarlos antes de automatizar su asignación.

## Recepción y seguridad

- Solo imap.gmail.com:993, TLS implícito y validación de identidad/cadena del certificado.
  Carpeta READ_ONLY y PEEK: no marca leído, elimina, mueve ni envía correos.
- From exacto más un único Authentication-Results de mx.google.com con DMARC pass y
  header.from alineado con ese dominio. Ausencia, duplicidad, contradicción o formato ambiguo:
  revisión. Un From permitido por sí solo no encola pagos.
- Esta confianza solo es válida sobre Gmail autenticado: depende de que el proveedor retire
  resultados entrantes que suplanten su authserv-id, según [RFC 8601](https://www.rfc-editor.org/rfc/rfc8601).
  No reutilizar el adaptador para servidores arbitrarios o archivos importados como si fueran correo verificado.
- No se descargan/abren partes adjuntas: se reconstruye evidencia de texto con las cabeceras
  necesarias. Se ignoran PDFs cifrados y mensajes adjuntos; nunca se solicita un documento de identidad.
- MIME hasta 8 niveles/40 partes; texto decodificado limitado, cuerpo final hasta 8192 caracteres.
  HTML se procesa sin ejecutar JavaScript ni cargar imágenes/URLs. multipart/alternative prefiere texto plano.
- Credenciales solo por entorno local; sin secretos ni cuerpos en logs. Evidencia válida cifrada
  AES-GCM, SHA-256 y AAD propietario:conexión:imap:UIDVALIDITY:UID. Otros remitentes no guardan su contenido.

Configuración TLS y OAuth2 basada en [Gmail IMAP](https://developers.google.com/workspace/gmail/imap/imap-smtp)
y [Angus Mail](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/imap/package-summary.html).

## Cursor, idempotencia y recuperación

La primera lectura fija UIDVALIDITY y el UID actual, sin importar históricos. Para importar
histórico, establecer NU_EMAIL_BACKFILL=true antes de inicializar una conexión nueva; procesará
esa carpeta desde su inicio. No se retrocede un cursor ya inicializado al cambiar la variable.
No habilitar backfill en INBOX sin revisar el alcance de esa importación.

Cada consulta revisa una ventana de 500 UIDs y lee hasta 10 mensajes. Ciclo de 60 segundos,
timeout de conexión 10 s/lectura 5 s y lease de 180 s. Si vence el lease, el token viejo no confirma.
Cursor, auditoría, ingesta financiera o facturación se confirman atómicamente por mensaje.
Un crash permite retomar sin perder mensajes; una falla transitoria de red no adelanta el cursor.
Un cuerpo que puede leerse pero no coincide con la plantilla queda REVIEW/IGNORED y permite
avanzar al mensaje siguiente. Si el transporte/MIME no permite leerlo, se conserva el cursor
y se requiere resolver ese fallo; no se descarta automáticamente un correo sin poder inspeccionarlo.

Un cambio de UIDVALIDITY deja UIDVALIDITY_CHANGED y no reinicia el cursor. Revisar la carpeta
y aprovisionar conscientemente una nueva conexión; no editar el cursor para ocultar el error.
La identidad IMAP es (source_connection_id,UIDVALIDITY,UID). En pagos, ID transacción también
deduplica entre correo y Shortcuts mediante la unicidad bancaria ya existente.

## Reglas de extracto

Asunto exacto: Llegó el extracto de tu tarjeta de crédito Nu. El parser usa el timestamp de
recepción del servidor Gmail (INTERNALDATE), no Date aportado por el remitente ni el nombre del PDF.
En hora Bogotá toma el corte más reciente no posterior a esa recepción; rechaza inferencias
de más de 62 días. El inicio del periodo puede pertenecer al año anterior; el vencimiento al
siguiente. Periodo y vencimiento deben ser consistentes con un ciclo mensual.

Se guardan minimum_payment, billing_period_start, billing_period_end, cut_off_date y payment_due_date.
La clave es propietario/tarjeta/corte. Una recepción posterior puede actualizar el estado;
otra más antigua no lo reemplaza. Un pago no marca automáticamente un extracto como pagado.
No se generan gastos, cargos recurrentes, transferencias ni notificaciones push por una alerta.

El pago reconoce el asunto real ¡Ya entró tu pago! y el título interno ¡Recibimos tu pago! Ya puedes
estar relax. Conserva validación de cuentas, productos y referencia bancaria del parser existente.

## Consultar resultados

Desde psql, limitar las consultas al UUID de la conexión configurada:

```sql
SELECT source_connection_id,uid_validity,last_uid,error_code,updated_at
FROM email_mailboxes WHERE source_connection_id='UUID-CONEXION';

SELECT e.kind,e.state,e.error_code,e.message_uid,i.state AS ingestion_state,i.transaction_id
FROM email_receipts e LEFT JOIN ingestion_logs i ON i.id=e.ingestion_id
WHERE e.source_connection_id='UUID-CONEXION' ORDER BY e.received_at DESC LIMIT 20;

SELECT b.minimum_payment,b.billing_period_start,b.billing_period_end,b.cut_off_date,b.payment_due_date
FROM billing_statements b JOIN email_receipts e ON e.id=b.email_receipt_id
WHERE e.source_connection_id='UUID-CONEXION' ORDER BY b.cut_off_date DESC;
```

No se exponen esos datos mediante API Keys de webhook: la interfaz de facturación con JWT y
las notificaciones de vencimiento quedan para una implementación posterior.
