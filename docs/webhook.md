# Webhook de ingesta

`POST /api/v1/transactions/ingest`: API Key sobre HTTPS, perfil `API_KEY_ONLY`.
`GET /api/v1/ingestions/{id}`: misma credencial, solo eventos de su conexión y propietario.
Las otras rutas permanecen cerradas. API Keys no permiten administrar cuentas ni conexiones.

## Configuración

- `INGESTION_PAYLOAD_KEY_BASE64`: clave AES de 32 bytes en Base64; obligatoria para arrancar `api`.
- `INGESTION_PAYLOAD_KEY_ID`: identificador persistido junto al contenido; predeterminado `v1`.
- `INGESTION_REQUIRE_HTTPS`: predeterminado `true`. `false` se permite explícitamente para pruebas locales.
- `SERVER_ADDRESS`: predeterminado `127.0.0.1`. Nunca publicar HTTP con API Keys.
- Configurar límites de peticiones por credencial/IP en el reverse proxy antes de exponer el servicio.

La aplicación usa `request.isSecure()` y no confía en `X-Forwarded-Proto` enviado por el cliente.
Para producción, configurar TLS del servidor o un proxy con HTTPS obligatorio y soporte de
forwarded headers restringido a proxies de confianza. No habilitar confianza global en cabeceras
de proxy sobre una API expuesta directamente.

```bash
bash scripts/configure-local-ingestion.sh
while IFS='=' read -r key value || [ -n "$key" ]; do
  case "$key" in
    DB_NAME|DB_USER|DB_PORT|DB_PASSWORD|INGESTION_PAYLOAD_KEY_BASE64|INGESTION_PAYLOAD_KEY_ID)
      export "$key=${value%$'\r'}" ;;
  esac
done < .env
export INGESTION_REQUIRE_HTTPS=false  # Exclusivamente localhost
java -jar target/proyecto-finanzas-0.0.1-SNAPSHOT.jar --spring.profiles.active=api
```

Conservar la clave de cifrado junto a los backups. No regenerarla ni cambiar su identificador
mientras existan eventos pendientes cifrados con ella; el worker deberá resolver versiones de clave.
No hay clave predeterminada, log de secretos ni generación automática al arrancar.

## Aprovisionar una conexión

En otra terminal Git Bash, usar un `owner_id` y `account_id` ya existentes:

```bash
bash scripts/provision-shortcuts.sh OWNER_UUID ACCOUNT_UUID
```

El script exige una cuenta activa del propietario, genera 256 bits aleatorios y guarda
exclusivamente el hash de la API Key completa en PostgreSQL. La credencial queda en
`.env.shortcuts`, excluido de Git, sin imprimirse. Rechaza sobrescribir ese archivo.
Si falla PostgreSQL, el archivo puede existir sin una conexión activa: revisar el error antes de usarlo.
No enviar ese archivo ni `.env` al repositorio o a logs.

Si aún no existen cuentas, estos comandos crean una **cuenta de demostración vacía** y muestran
los UUID necesarios; no introducen movimientos ni conectan con Nu:

```bash
docker compose exec -T postgres sh -c \
  'exec psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' <<'SQL'
SELECT gen_random_uuid() AS demo_owner, gen_random_uuid() AS demo_account \gset
INSERT INTO accounts(id, owner_id, name, kind)
VALUES (:'demo_account'::uuid, :'demo_owner'::uuid, 'Nu - demostracion', 'DEPOSIT');
\echo OWNER_UUID=:demo_owner ACCOUNT_UUID=:demo_account
SQL
```

## Enviar y reintentar

Tras arrancar la API, en la terminal de pruebas:

```bash
while IFS='=' read -r key value || [ -n "$key" ]; do
  case "$key" in
    INGESTION_API_KEY|INGESTION_ACCOUNT_ID|SOURCE_CONNECTION_ID) export "$key=${value%$'\r'}" ;;
  esac
done < .env.shortcuts

EVENT_ID="$(docker compose exec -T postgres sh -c \
  'psql -X -At -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT gen_random_uuid()"' | tr -d '\r\n')"

cat > target/shortcut-event.json <<JSON
{
  "schema_version": 1,
  "trigger": "APPLE_PAY",
  "format": "NORMALIZED",
  "account_id": "$INGESTION_ACCOUNT_ID",
  "movement": {
    "kind": "EXPENSE",
    "amount": "45900.00",
    "currency": "COP",
    "occurred_at": "2026-11-13T13:45:12-05:00",
    "merchant": "COMERCIO DEMO",
    "bank_provider": "NU_CO",
    "bank_event_id": null
  }
}
JSON

curl -i 'http://127.0.0.1:8080/api/v1/transactions/ingest' \
  -H 'Content-Type: application/json' \
  -H "X-API-Key: $INGESTION_API_KEY" \
  -H "Idempotency-Key: $EVENT_ID" \
  --data-binary @target/shortcut-event.json
```

Repetir el mismo `curl` conserva evento y bytes: `202`, mismo `ingestion_id`, una sola fila.
Cambiar el contenido manteniendo `EVENT_ID` devuelve `409`; no sobrescribe la primera ingesta.
Un evento nuevo necesita otro UUID. Shortcuts debe guardar UUID y cuerpo antes del envío y
reutilizarlos si hay error de red. La entrega offline/reintentos del iPhone deben configurarse
en el cliente; este backend no garantiza que iOS ejecute la automatización.

Ejemplo de respuesta después del commit:

```json
{"ingestion_id":"2b67da32-a2c3-4900-841d-d31269af9fa4","status":"RECEIVED"}
```

`Location` contiene la ruta para consultar el estado, usando la misma API Key:

```bash
curl -i 'http://127.0.0.1:8080/api/v1/ingestions/UUID_DE_RESPUESTA' \
  -H "X-API-Key: $INGESTION_API_KEY"
```

Con un worker activo, los eventos NORMALIZED generan transacciones POSTED. Consultar hasta PROCESSED y comprobar transaction_id. Ver [worker](processing-worker.md).

## Contrato y errores

- `schema_version`: entero JSON `1`; sin coerción de decimales o cadenas.
- `trigger`: `APPLE_PAY`, `SMS`, `EMAIL`.
- `NORMALIZED`: movimiento obligatorio, sin `message`; ingresos/gastos. Transferencias se rechazan en esta fase.
- `amount`: cadena decimal positiva, máximo 16 enteros y 2 decimales; sin separadores ni notación científica.
- `currency`: código ISO admitido por Java, predeterminado COP; debe coincidir con la cuenta.
- `occurred_at`: fecha ISO con offset. Referencia bancaria solo si existe; no inventarla.
- `RAW_MESSAGE`: `message.text` y `message.received_at` con offset; solo SMS/correo. Sin movimiento normalizado.
- Sin propiedades desconocidas, claves JSON repetidas, contenido comprimido ni cuerpos mayores de 64 KiB.
- `Idempotency-Key`: UUID canónico en minúsculas, una cabecera. Hash calculado sobre bytes originales;
  reordenar campos/espacios cuenta como otro contenido para la misma clave.
- Duplicado pendiente: `202`. Duplicado terminal (`PROCESSED`, `REVIEW`, `DEAD`): `200`.
- `400`: solicitud/cabeceras inválidas o HTTPS requerido; `401`: credencial no admitida.
- `403`: cuenta no autorizada/inactiva; `404`: ingesta no visible para esta conexión.
- `409`: conflicto de idempotencia; `413`: tamaño; `415`: tipo/codificación; `422`: movimiento inválido.
- `503`: almacenamiento no disponible. Reintentar con la misma clave y cuerpo, backoff y jitter.

`HMAC_REQUIRED` se rechaza sin degradación; no hay almacén de nonces. La clave autentica el
dispositivo, no demuestra liquidación bancaria. Deduplicación bancaria estable disponible; parsers Nu pendientes.

## Cifrado y persistencia

`payload_encrypted`: versión binaria `0x01`, nonce aleatorio de 12 bytes y ciphertext AES-256-GCM
con tag de 128 bits. AAD UTF-8: `owner_id:source_connection_id:idempotency_key`.
`payload_sha256` usa SHA-256 de los bytes originales; `payload_key_id` referencia la clave.

La admisión revalida credencial, cuenta y lista bajo bloqueos compartidos hasta el commit.
La unicidad SQL y `ON CONFLICT` resuelven concurrencia; no hay cola en memoria ni confirmación
antes de persistir. No se cambia `V1__init_schema.sql` ni se altera el esquema instalado.
