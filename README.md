# proyecto-finanzas

Java 21, Spring Boot 3.5.16, PostgreSQL 16 y Flyway. Dominio sin Spring, JPA, Jackson ni Lombok.

Flujo de ramas, commits y pull requests: [CONTRIBUTING.md](CONTRIBUTING.md).

## Probar con pantallas

```bash
bash scripts/run-demo.sh
```

Abrir [http://127.0.0.1:8090/demo/](http://127.0.0.1:8090/demo/) y cargar `.env.demo-panel` con
el botón **Cargar credenciales**. Usa la base separada `finanzas_demo` y permite enviar eventos,
consultarlos y repetir un intento sin duplicarlo. [Guía del panel](docs/demo-panel.md).

## Ejecutar

Requisitos: Git Bash, JDK 21, Maven 3.6.3+ y Docker con daemon activo.

```bash
export JAVA_HOME='/c/Program Files/Java/jdk-21'
export PATH="$JAVA_HOME/bin:$PATH"
# Maven incluido en la instalación local de IntelliJ. Si está en PATH: mvn_bin=mvn
mvn_bin='/c/Program Files/JetBrains/IntelliJ IDEA 2026.1/plugins/maven/lib/maven3/bin/mvn'

if [ ! -f .env ]; then
  cp .env.example .env
  db_password="$(openssl rand -hex 32)"
  sed -i "s/^DB_PASSWORD=.*/DB_PASSWORD=$db_password/" .env
  unset db_password
fi

while IFS='=' read -r key value || [ -n "$key" ]; do
  case "$key" in
    DB_NAME|DB_USER|DB_PORT|DB_PASSWORD) export "$key=${value%$'\r'}" ;;
  esac
done < .env

docker compose up -d --wait postgres
bash scripts/configure-local-ingestion.sh
while IFS='=' read -r key value || [ -n "$key" ]; do
  case "$key" in
    INGESTION_PAYLOAD_KEY_BASE64|INGESTION_PAYLOAD_KEY_ID) export "$key=${value%$'\r'}" ;;
  esac
done < .env
# Solo para probar en localhost; en despliegue HTTPS es obligatorio.
export INGESTION_REQUIRE_HTTPS=false
"$mvn_bin" '-Dmaven.repo.local=C:/RTEMP/finanzas-m2' verify
java -jar target/proyecto-finanzas-0.0.1-SNAPSHOT.jar --spring.profiles.active=api
```

Conservar `.env` para reutilizar el volumen; completar `DB_PASSWORD` si el archivo existente
la tiene vacía. Java/Maven no cargan `.env` automáticamente: ejecutar la lectura anterior en
cada nueva terminal. La contraseña del volumen existente no cambia al cambiar una variable.

```bash
# Tras detener la API con Ctrl+C, alternativa en la misma terminal:
java -jar target/proyecto-finanzas-0.0.1-SNAPSHOT.jar --spring.profiles.active=worker
```

- Usar un solo perfil por proceso. Predeterminado: `api`.
- PostgreSQL local: `127.0.0.1:55432`, volumen persistente. `DB_PORT` configurable.
  Puerto interno del contenedor: `5432`. Si la terminal tenía `DB_PORT=5432` exportado,
  releer `.env` o ejecutar `export DB_PORT=55432`; el entorno prevalece sobre `.env`.
- API local: `127.0.0.1:8080`. En despliegue, publicar exclusivamente mediante proxy HTTPS.
- `worker`: sin servidor HTTP, proceso persistente preparado para jobs posteriores.
- Flyway crea el esquema al arrancar; Hibernate solo valida. No hay entidades JPA todavía.
- Compose local usa el usuario inicial de PostgreSQL. En producción separar usuario de migraciones
  y rol de ejecución con permisos mínimos; no exponer PostgreSQL públicamente.

## Alcance implementado

- Arranque Spring Boot, perfiles, configuración y migración con seis tablas.
- Diez tipos de dominio solicitados, enums auxiliares y validaciones financieras.
- Webhook autenticado, inbox cifrado, idempotencia concurrente y consulta de estado por conexión.
- Interfaz `HmacVerificationPort` sin implementación ni almacén de nonces; `API_KEY_ONLY` es el perfil soportado.
- Permisos por cuenta, revocación, expiración y aislamiento por propietario/conexión.
- Límite JSON real de 64 KiB, también con transferencia chunked; errores sin detalles internos.
- [Contrato, aprovisionamiento y prueba desde Git Bash](docs/webhook.md).
- Panel de demostración local con perfil `api,demo`; no habilitado en el perfil `api` ordinario.

La persistencia de ingesta usa JDBC detrás de puertos; JPA queda disponible para otros adaptadores.
El worker procesa `NORMALIZED`, las [plantillas Nu Colombia](docs/nu-parser.md) y
[Gmail por IMAP](docs/email-worker.md), desactivado hasta configurar el buzón.
Registro manual/JWT siguen pendientes. El perfil `worker` registra transacciones; la demo lo ejecuta dentro
del proceso `api,demo`. [Funcionamiento y pruebas](docs/processing-worker.md).

## Invariantes

- `Money` representa importes positivos, no saldos firmados. Máximo `numeric(18,2)`.
  Se normaliza escala sin redondeo; fracciones de centavo rechazadas. COP por defecto.
- Transferencias: cuenta destino distinta, mismo propietario y moneda, sin categoría.
- Categorías: mismo propietario y tipo compatible; transferencias sin categoría.
- Movimientos y recurrencias son snapshots inmutables de recursos de dominio. Constructores
  permiten reconstruir historia con recursos inactivos. Antes de insertar un registro nuevo,
  el caso de uso debe llamar `requireRegistrable()` usando recursos actuales cargados de BD.
- `POSTED` y `VOIDED` son terminales para cambios de estado. Una corrección de movimiento
  contabilizado requerirá un movimiento compensatorio; no se borra ni se anula directamente.
- Recurrencia: previsión, nunca crea gastos automáticamente. Para compra financiada, registrar
  compra una vez; pago de principal como transferencia, intereses/comisiones como gasto.
- Calendario mensual anclado a `billing_day`; febrero se ajusta sin desplazar marzo.
  Si el día de cobro del mes inicial ya pasó, primer vencimiento en el mes siguiente;
  desde ese vencimiento se aplica `interval_months`.
- Cuotas finitas: cantidad positiva, número y fecha correspondientes a la misma ocurrencia.
  `next_due_on` es proyección persistida del calendario; su actualización pertenece al futuro job.
- Instantes almacenados en UTC; vencimientos `LocalDate` interpretados en `America/Bogota`.

## Credenciales e idempotencia

No se insertan credenciales de ejemplo. Aprovisionamiento local mediante
`bash scripts/provision-shortcuts.sh OWNER_UUID ACCOUNT_UUID`, con una cuenta ya existente:

1. Crear cuenta y conexión con el mismo `owner_id`.
2. Generar secreto aleatorio de al menos 256 bits. Formato previsto:
   `pf_ing_<source_connection_uuid>.<base64url-secret>`.
3. Guardar únicamente SHA-256 de la **API Key completa**, bytes UTF-8 sin modificar,
   en `source_connections.api_key_sha256` (`bytea`, 32 bytes).
4. `allowed_account_ids`: array UUID no vacío, sin duplicados, limitado al mismo propietario.
   Triggers validan pertenencia y protegen referencias de borrado/cambio de propietario.
5. El filtro compara hashes en tiempo constante y comprueba conexión activa,
   expiración, `source = IOS_SHORTCUTS` y perfil soportado. `HMAC_REQUIRED` está reservado:
   rechazar mientras no exista verificador, sin degradación a API Key.
6. La admisión autoriza `account_id` y vuelve a comprobar credencial y lista dentro de la transacción,
   sin confiar en propietario/source del JSON. Rotar hash sin cambiar el ID conserva idempotencia.

`ingestion_logs` impone unicidad `(owner_id, source, source_connection_id, idempotency_key)`
y FK compuesta a la conexión. SHA-256 del payload permite detectar conflicto de contenido.
Referencia bancaria estable permite conciliación entre canales; monto/fecha/comercio no son
una clave única. Worker implementado: leases, `SKIP LOCKED`, y commit conjunto de movimiento + estado.
No se implementan nonces en esta fase.

## Referencias

- [Requisitos Spring Boot 3.5](https://docs.spring.io/spring-boot/3.5/system-requirements.html).
- [Módulo PostgreSQL de Flyway](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database).

## Pruebas de infraestructura opcionales

Las pruebas de dominio se ejecutan sin base de datos. Para probar migración, restricciones y
arranque de ambos perfiles contra un PostgreSQL disponible:

```bash
export FINANCE_TEST_DB_URL="jdbc:postgresql://127.0.0.1:${DB_PORT:-55432}/${DB_NAME:-finanzas}"
export FINANCE_TEST_DB_USER="${DB_USER:-finanzas}"
export FINANCE_TEST_DB_PASSWORD="$DB_PASSWORD"
"$mvn_bin" '-Dmaven.repo.local=C:/RTEMP/finanzas-m2' verify
```

El usuario de pruebas necesita crear esquemas. Cada suite usa un esquema aleatorio propio,
lo elimina al finalizar y revierte los fixtures de cada caso. Sin `FINANCE_TEST_DB_URL`,
las pruebas de infraestructura se omiten.
