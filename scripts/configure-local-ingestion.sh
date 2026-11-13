#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
umask 077

if [[ ! -f .env ]]; then
  printf 'Primero configura .env y DB_PASSWORD.\n' >&2
  exit 1
fi
current_key="$(sed -n 's/^INGESTION_PAYLOAD_KEY_BASE64=//p' .env | tr -d '\r' | tail -n 1)"
if [[ -n "$current_key" ]]; then
  printf 'Clave de cifrado existente conservada en .env.\n'
  exit 0
fi
payload_key="$(openssl rand -base64 32 | tr -d '\r\n')"
if grep -q '^INGESTION_PAYLOAD_KEY_BASE64=' .env; then
  sed -i "s|^INGESTION_PAYLOAD_KEY_BASE64=.*$|INGESTION_PAYLOAD_KEY_BASE64=$payload_key|" .env
else
  printf '\nINGESTION_PAYLOAD_KEY_BASE64=%s\n' "$payload_key" >> .env
fi
unset payload_key current_key
printf 'Clave de cifrado guardada en .env; no regenerarla mientras existan eventos cifrados.\n'
