#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
umask 077
uuid_pattern='^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
if [[ $# -ne 3 || ! "$1" =~ $uuid_pattern || ! "$2" =~ $uuid_pattern || ! "$3" =~ $uuid_pattern || "$2" == "$3" ]]; then
  printf 'Uso: bash scripts/provision-email.sh OWNER_UUID DEPOSIT_UUID CREDIT_CARD_UUID\n' >&2; exit 1
fi
if [[ -e .env.gmail ]]; then printf '.env.gmail ya existe; no se sobrescribira.\n' >&2; exit 1; fi
psql_compose() {
  docker compose exec -T postgres sh -c \
    'exec psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 "$@"' sh "$@"
}
connection_id="$(psql_compose -qAtc 'SELECT gen_random_uuid()' | tr -d '\r\n')"
if [[ ! "$connection_id" =~ $uuid_pattern ]]; then printf 'No se obtuvo una conexion valida.\n' >&2; exit 1; fi
set -o noclobber
printf 'NU_EMAIL_ENABLED=false\nNU_EMAIL_CONNECTION_ID=%s\nNU_EMAIL_ACCOUNT_ID=%s\nNU_EMAIL_DESTINATION_ACCOUNT_ID=%s\nNU_GMAIL_USERNAME=\nNU_GMAIL_APP_PASSWORD=\nNU_GMAIL_ACCESS_TOKEN=\nNU_EMAIL_FROM=\nNU_EMAIL_FOLDER=INBOX\nNU_EMAIL_BACKFILL=false\n' \
  "$connection_id" "$2" "$3" > .env.gmail
psql_compose -v owner="$1" -v account="$2" -v card="$3" -v connection="$connection_id" <<'SQL'
BEGIN;
SELECT a.id AS deposit,b.id AS destination FROM accounts a JOIN accounts b ON b.owner_id=a.owner_id
WHERE a.owner_id=:'owner'::uuid AND a.id=:'account'::uuid AND a.kind='DEPOSIT' AND a.active AND a.currency='COP'
  AND a.bank_reference IS NOT NULL AND b.id=:'card'::uuid AND b.kind='CREDIT_CARD' AND b.active AND b.currency='COP'
  AND b.card_last4 IS NOT NULL FOR SHARE OF a,b
\gset
INSERT INTO source_connections(id,owner_id,source,name,allowed_account_ids)
VALUES(:'connection'::uuid,:'owner'::uuid,'IMAP','Gmail Nu',ARRAY[:'deposit'::uuid,:'destination'::uuid]);
COMMIT;
SQL
printf 'Conexion IMAP creada. Completa .env.gmail localmente; queda desactivada por defecto.\n'
