#!/usr/bin/env bash
set -euo pipefail

MYSQL_IMAGE="${MYSQL_IMAGE:-docker.m.daocloud.io/library/mysql:8.0}"
PG_IMAGE="${PG_IMAGE:-postgres:15}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-lineage}"
PG_PASSWORD="${PG_PASSWORD:-lineage}"
RUN_ID="$$-$(date +%s)"
MYSQL_NAME="lineage-mysql-it-${RUN_ID}"
PG_NAME="lineage-postgres-it-${RUN_ID}"

MYSQL_CREATED=0
PG_CREATED=0
MYSQL_ID=""
PG_ID=""

cleanup() {
  if [ "$MYSQL_CREATED" = "1" ] && [ -n "$MYSQL_ID" ]; then
    docker rm -f "$MYSQL_ID" >/dev/null 2>&1 || true
  fi
  if [ "$PG_CREATED" = "1" ] && [ -n "$PG_ID" ]; then
    docker rm -f "$PG_ID" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

start_mysql() {
  local port_args
  if [ -n "${MYSQL_PORT:-}" ]; then
    port_args="-p 127.0.0.1:${MYSQL_PORT}:3306"
  else
    port_args="-p 127.0.0.1::3306"
  fi
  MYSQL_ID="$(docker create --name "$MYSQL_NAME" \
    -e MYSQL_ROOT_PASSWORD="$MYSQL_PASSWORD" \
    -e MYSQL_DATABASE=lineage \
    $port_args \
    "$MYSQL_IMAGE")"
  MYSQL_CREATED=1
  if ! docker start "$MYSQL_ID" >/dev/null; then
    echo "failed to start MySQL container" >&2
    exit 1
  fi

  if [ -n "${MYSQL_PORT:-}" ]; then
    MYSQL_ACTUAL_PORT="$MYSQL_PORT"
  else
    MYSQL_ACTUAL_PORT="$(docker port "$MYSQL_ID" 3306/tcp | head -1 | sed 's/.*://')"
  fi
}

start_postgres() {
  local port_args
  if [ -n "${PG_PORT:-}" ]; then
    port_args="-p 127.0.0.1:${PG_PORT}:5432"
  else
    port_args="-p 127.0.0.1::5432"
  fi
  PG_ID="$(docker create --name "$PG_NAME" \
    -e POSTGRES_PASSWORD="$PG_PASSWORD" \
    -e POSTGRES_DB=lineage \
    $port_args \
    "$PG_IMAGE")"
  PG_CREATED=1
  if ! docker start "$PG_ID" >/dev/null; then
    echo "failed to start PostgreSQL container" >&2
    exit 1
  fi

  if [ -n "${PG_PORT:-}" ]; then
    PG_ACTUAL_PORT="$PG_PORT"
  else
    PG_ACTUAL_PORT="$(docker port "$PG_ID" 5432/tcp | head -1 | sed 's/.*://')"
  fi
}

start_mysql
start_postgres

echo "waiting for MySQL on port $MYSQL_ACTUAL_PORT ..."
mysql_ready=0
for _ in $(seq 1 90); do
  if docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" "$MYSQL_ID" \
      mysql -h127.0.0.1 -P3306 -uroot -e 'SELECT 1' >/dev/null 2>&1; then
    mysql_ready=1
    break
  fi
  sleep 2
done
if [ "$mysql_ready" != "1" ]; then
  echo "MySQL did not become ready" >&2
  docker logs "$MYSQL_ID" >&2 || true
  exit 1
fi

echo "waiting for PostgreSQL on port $PG_ACTUAL_PORT ..."
pg_ready=0
for _ in $(seq 1 60); do
  if docker exec -e PGPASSWORD="$PG_PASSWORD" "$PG_ID" \
      psql -h 127.0.0.1 -U postgres -d lineage -c 'SELECT 1' >/dev/null 2>&1; then
    pg_ready=1
    break
  fi
  sleep 1
done
if [ "$pg_ready" != "1" ]; then
  echo "PostgreSQL did not become ready" >&2
  docker logs "$PG_ID" >&2 || true
  exit 1
fi

# The PostgreSQL JDBC driver honors the JVM SOCKS proxy settings; disable proxies for
# localhost integration tests.
mvn -Dtest=JdbcMetadataScannerDockerTest \
  -DargLine="-DsocksProxyHost= -Dhttp.proxyHost= -Dhttps.proxyHost=" \
  -Dlineage.mysql.url="jdbc:mysql://127.0.0.1:${MYSQL_ACTUAL_PORT}/lineage?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" \
  -Dlineage.mysql.user=root \
  -Dlineage.mysql.password="$MYSQL_PASSWORD" \
  -Dlineage.postgres.url="jdbc:postgresql://127.0.0.1:${PG_ACTUAL_PORT}/lineage" \
  -Dlineage.postgres.user=postgres \
  -Dlineage.postgres.password="$PG_PASSWORD" \
  test
