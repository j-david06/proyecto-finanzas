#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
umask 077

if [[ $# -ne 2 ]]; then
  printf 'Uso: bash scripts/provision-shortcuts.sh OWNER_UUID ACCOUNT_UUID\n' >&2
  exit 1
fi
uuid_pattern='^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
if [[ ! "$1" =~ $uuid_pattern || ! "$2" =~ $uuid_pattern ]]; then
  printf 'OWNER_UUID y ACCOUNT_UUID deben ser UUID canonicos.\n' >&2
  exit 1
fi
if [[ -e .env.shortcuts ]]; then
  printf '.env.shortcuts ya existe; conserva esa credencial o revocala antes de aprovisionar otra.\n' >&2
  exit 1
fi

psql_compose() {
  docker compose exec -T postgres sh -c \
    'exec psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 "$@"' sh "$@"
}

connection_id="$(psql_compose -Atc 'SELECT gen_random_uuid()' | tr -d '\r\n')"
if [[ ! "$connection_id" =~ $uuid_pattern ]]; then
  printf 'No se obtuvo un UUID de conexion valido.\n' >&2
  exit 1
fi
secret="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\r\n')"
api_key="pf_ing_${connection_id}.${secret}"
digest="$(printf '%s' "$api_key" | openssl dgst -sha256 | awk '{print $NF}')"
if [[ ! "$digest" =~ ^[0-9a-f]{64}$ ]]; then
  printf 'No se pudo calcular SHA-256.\n' >&2
  exit 1
fi
# Save the one-time credential before admission; never print it or pass it to PostgreSQL.
# If provisioning fails, this file remains for diagnosis; it does not imply an active credential.
set -o noclobber
printf 'INGESTION_API_KEY=%s\nSOURCE_CONNECTION_ID=%s\nINGESTION_ACCOUNT_ID=%s\n' \
  "$api_key" "$connection_id" "$2" > .env.shortcuts
unset api_key secret

psql_compose -v owner="$1" -v account="$2" -v connection="$connection_id" -v digest="$digest" <<'SQL'
BEGIN;
-- Require an existing active account and fail before committing if it is absent.
SELECT id AS validated_account FROM accounts
WHERE id = :'account'::uuid AND owner_id = :'owner'::uuid AND active FOR SHARE
\gset
INSERT INTO source_connections(id, owner_id, source, name, api_key_sha256, allowed_account_ids)
VALUES (:'connection'::uuid, :'owner'::uuid, 'IOS_SHORTCUTS', 'iOS Shortcuts',
        decode(:'digest', 'hex'), ARRAY[:'validated_account'::uuid]);
COMMIT;
SQL
printf 'Conexion creada. Credencial guardada exclusivamente en .env.shortcuts (ignorado por Git).\n'
