#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
if [[ ! -f .env || ! -f .env.gmail ]]; then printf 'Configura .env y .env.gmail primero.\n' >&2; exit 1; fi
while IFS='=' read -r name value || [[ -n "$name" ]]; do
  case "$name" in DB_URL|DB_NAME|DB_USER|DB_PORT|DB_PASSWORD|INGESTION_PAYLOAD_KEY_BASE64|INGESTION_PAYLOAD_KEY_ID)
    export "$name=${value%$'\r'}";; esac
done < .env
while IFS='=' read -r name value || [[ -n "$name" ]]; do
  case "$name" in NU_EMAIL_ENABLED|NU_EMAIL_CONNECTION_ID|NU_EMAIL_ACCOUNT_ID|NU_EMAIL_DESTINATION_ACCOUNT_ID|NU_GMAIL_USERNAME|NU_GMAIL_APP_PASSWORD|NU_GMAIL_ACCESS_TOKEN|NU_EMAIL_FROM|NU_EMAIL_FOLDER|NU_EMAIL_BACKFILL|NU_EMAIL_POLL_DELAY_MS)
    export "$name=${value%$'\r'}";; esac
done < .env.gmail
if [[ "${NU_EMAIL_ENABLED:-false}" != true ]]; then printf 'El worker de correo esta desactivado; cambia NU_EMAIL_ENABLED en .env.gmail para habilitarlo.\n' >&2; exit 1; fi
: "${DB_PASSWORD:?Configura DB_PASSWORD}" "${INGESTION_PAYLOAD_KEY_BASE64:?Configura la clave AES usada por la API}"
: "${NU_GMAIL_USERNAME:?Configura el usuario Gmail}" "${NU_EMAIL_FROM:?Configura el remitente Nu exacto}"
if [[ -z "${NU_GMAIL_APP_PASSWORD:-}" && -z "${NU_GMAIL_ACCESS_TOKEN:-}" ]]; then printf 'Configura un secreto Gmail localmente, sin enviarlo al chat.\n' >&2; exit 1; fi
if [[ -z "${JAVA_HOME:-}" && -d '/c/Program Files/Java/jdk-21' ]]; then export JAVA_HOME='/c/Program Files/Java/jdk-21'; fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
exec java -jar target/proyecto-finanzas-0.0.1-SNAPSHOT.jar --spring.profiles.active=worker
