# Procesamiento de ingestas

El worker convierte eventos `NORMALIZED` de `IOS_SHORTCUTS` en transacciones `POSTED`.
No contacta con Nu ni ejecuta cargos. `POSTED` indica registro interno del dato enviado;
la API Key no demuestra liquidación bancaria.

## Probar desde Git Bash

```bash
bash scripts/run-demo.sh
```

Abrir http://127.0.0.1:8090/demo/, cargar `.env.demo-panel`, enviar un ingreso/gasto y pulsar
**Consultar estado** después de unos segundos. Debe pasar de `RECEIVED` a `PROCESSED` y mostrar
el ID de transacción. Repetir el envío conserva ambos IDs. La demo ejecuta el worker dentro
del proceso `api,demo`, exclusivamente sobre la base `finanzas_demo`.

En ejecución normal, API y worker son procesos separados, con la misma BD, clave AES e ID de clave:

```bash
# Exportar DB_URL/DB_USER/DB_PASSWORD e INGESTION_PAYLOAD_KEY_BASE64/INGESTION_PAYLOAD_KEY_ID
# con los mismos valores usados por la API; no regenerar la clave de datos existentes.
java -jar target/proyecto-finanzas-0.0.1-SNAPSHOT.jar --spring.profiles.active=worker
```

El perfil `worker` no abre HTTP. `api` no agenda procesamiento. Detener ambos procesos antes
de reconstruir el JAR en Windows. No combinar `api,worker`; usar procesos separados o la demo.

## Garantías y límites

- Reclamo corto con `FOR UPDATE SKIP LOCKED`; incrementa intentos y asigna lease/token.
- Descifrado AES-GCM con AAD de propietario/conexión/evento y verificación SHA-256 del original.
- Parser compartido valida JSON estricto, montos `BigDecimal`, moneda y fecha con offset.
- Transacción financiera y confirmación `PROCESSED` se confirman en una sola transacción SQL.
  Revalidación bajo bloqueos de conexión activa/no vencida, allowlist y cuenta activa/moneda.
- El ID financiero usa el ID de ingesta. Un lease viejo no puede confirmar ni cambiar estados.
- Leases vencidos se recuperan; errores transitorios aplican backoff exponencial limitado a 1 hora.
  Agotamiento, incluidos crashes repetidos, termina en `DEAD`.
- Payload inválido/alterado o permisos retirados terminan en `REVIEW`, sin registrar movimientos.
  Los errores persistidos son códigos estáticos, sin texto de mensajes o excepciones.
- `RAW_MESSAGE` admite [plantillas Nu Colombia](nu-parser.md) verificadas para APP_DETAIL y
  pagos de tarjeta por EMAIL. Los demás formatos terminan en `REVIEW / PARSER_UNAVAILABLE`.
- Mismo propietario/cuenta/proveedor/ID bancario reutiliza el movimiento existente solo si coincide
  tipo, estado, importe, moneda, instante y comercio. Diferencias: `BANK_EVENT_CONFLICT` para revisión.
  Sin referencia bancaria estable, eventos con distintas claves se consideran distintos; nunca se
  deduplican por monto/fecha/comercio. No hay deduplicación heurística entre canales.
- Instantes normalizados a microsegundos antes de persistir, según precisión de PostgreSQL.
- Una clave no disponible aplica `RETRY`, luego `DEAD` si no se restaura a tiempo. Solo se carga
  una clave; rotación con llavero y recuperación manual de `REVIEW/DEAD` aún pendientes.
- No calcula saldos ni categoriza. V1 permanece intacta; V2 añade metadatos Nu y mapeos de cuentas.

## Configuración

| Variable | Predeterminado | Uso |
|---|---:|---|
| `PROCESSING_POLL_DELAY_MS` | 2000 | Pausa entre lotes |
| `PROCESSING_BATCH_SIZE` | 20 | Máximo de eventos por lote, 1–100 |
| `PROCESSING_MAX_ATTEMPTS` | 5 | Intentos por evento, 1–20 |
| `PROCESSING_LEASE_SECONDS` | 90 | Lease, 30–3600 segundos |
| `PROCESSING_RETRY_SECONDS` | 10 | Base de backoff, 1–3600 segundos |
| `PROCESSING_SCHEDULING_ENABLED` | true | Desactiva solo la agenda, útil en pruebas |

Transacciones de procesamiento: aislamiento `READ_COMMITTED`, timeout 10 segundos. Los fallos
de conexión al reclamar se reintentan en el siguiente ciclo sin consumir intento de un evento.

`GET /api/v1/ingestions/{id}` y las repeticiones del webhook añaden `transaction_id`, `error_code`
y `attempts`. Conservan el aislamiento por propietario/conexión. `transaction_id` puede apuntar
a un movimiento ya existente cuando hay deduplicación por referencia bancaria.

## Validación

`mvn verify` con `FINANCE_TEST_DB_*` configuradas ejecuta pruebas PostgreSQL en esquemas aleatorios:
concurrencia, recuperación de leases, rechazo de tokens viejos, deduplicación bancaria,
rollback de inserción si falla la confirmación, backoff, agotamiento y evidencia alterada.

```bash
node --test src/test/js/demo-model.test.mjs
```
