#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
umask 077

if [[ ! -f .env ]]; then
  printf 'Configura .env con DB_PASSWORD antes de arrancar la demo.\n' >&2
  exit 1
fi
while IFS='=' read -r name value || [[ -n "$name" ]]; do
  case "$name" in
    DB_NAME|DB_USER|DB_PORT|DB_PASSWORD) export "$name=${value%$'\r'}" ;;
  esac
done < .env
: "${DB_PASSWORD:?DB_PASSWORD debe estar configurada}"

demo_port="${DEMO_PORT:-8090}"
if [[ ! "$demo_port" =~ ^[0-9]{4,5}$ ]] || (( 10#$demo_port < 1000 || 10#$demo_port > 65535 )); then
  printf 'DEMO_PORT debe ser un puerto entre 1000 y 65535.\n' >&2
  exit 1
fi
if (exec 3<>"/dev/tcp/127.0.0.1/$demo_port") 2>/dev/null; then
  printf 'El puerto %s esta ocupado. Deten la demo anterior antes de reconstruir el JAR.\n' "$demo_port" >&2
  exit 1
fi

docker compose up -d --wait postgres
bash scripts/configure-local-ingestion.sh
while IFS='=' read -r name value || [[ -n "$name" ]]; do
  case "$name" in
    INGESTION_PAYLOAD_KEY_BASE64|INGESTION_PAYLOAD_KEY_ID) export "$name=${value%$'\r'}" ;;
  esac
done < .env

if [[ -z "${JAVA_HOME:-}" && -d '/c/Program Files/Java/jdk-21' ]]; then
  export JAVA_HOME='/c/Program Files/Java/jdk-21'
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
if [[ -n "${MAVEN_BIN:-}" ]]; then
  mvn_bin="$MAVEN_BIN"
elif command -v mvn >/dev/null 2>&1; then
  mvn_bin=mvn
elif [[ -f '/c/Program Files/JetBrains/IntelliJ IDEA 2026.1/plugins/maven/lib/maven3/bin/mvn' ]]; then
  mvn_bin='/c/Program Files/JetBrains/IntelliJ IDEA 2026.1/plugins/maven/lib/maven3/bin/mvn'
else
  printf 'Maven no encontrado. Configura MAVEN_BIN o agregalo a PATH.\n' >&2
  exit 1
fi
maven_args=(-B -ntp -DskipTests package)
if [[ -d /c/RTEMP/finanzas-m2 ]]; then maven_args+=('-Dmaven.repo.local=C:/RTEMP/finanzas-m2'); fi
"$mvn_bin" "${maven_args[@]}"

# Dedicated persistent demo database; never seed the personal-finance database.
docker compose exec -T postgres sh -c \
  'exec psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' <<'SQL'
SELECT 'CREATE DATABASE finanzas_demo'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname='finanzas_demo')
\gexec
SQL

export DB_URL="jdbc:postgresql://127.0.0.1:${DB_PORT:-55432}/finanzas_demo"
export INGESTION_REQUIRE_HTTPS=false
printf '\nPanel local: http://127.0.0.1:%s/demo/\n' "$demo_port"
printf 'Carga .env.demo-panel en el panel cuando termine el arranque. Ctrl+C detiene la API.\n\n'
exec java -jar target/proyecto-finanzas-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=api,demo --server.address=127.0.0.1 --server.port="$demo_port" \
  --finance.demo.seed-enabled=true
