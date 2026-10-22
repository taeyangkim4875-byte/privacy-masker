#!/usr/bin/env bash
# PII masker - local run (Linux / macOS)
#   ./run.sh             start MariaDB (Docker) + build UI + start app
#   ./run.sh --no-db     use your own MariaDB (e.g. dev server), skip Docker
#   ./run.sh --skip-ui   reuse the last UI build
set -euo pipefail
cd "$(dirname "$0")"

NO_DB=0; SKIP_UI=0
for a in "$@"; do
  case "$a" in
    --no-db) NO_DB=1 ;;
    --skip-ui) SKIP_UI=1 ;;
    *) echo "unknown option: $a"; exit 1 ;;
  esac
done

fail() { echo "[x] $*" >&2; exit 1; }

command -v java >/dev/null || fail "Java not found. Install JDK 21 or 25."
JV=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')
[ "${JV:-0}" -ge 21 ] || fail "JDK 21+ required (found $JV)"
echo "[ok] Java $JV"

if [ "$SKIP_UI" -eq 0 ]; then
  command -v node >/dev/null || fail "Node.js not found. Install Node 22 LTS, or use --skip-ui after one build."
  echo "[ok] Node $(node -v)"
fi

if [ "$NO_DB" -eq 0 ]; then
  docker version >/dev/null 2>&1 || fail "Docker is not running (or use --no-db)."
  docker compose up -d mariadb
  printf "waiting for MariaDB"
  for _ in $(seq 1 60); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' piimask-mariadb 2>/dev/null)" = "healthy" ] && break
    sleep 2; printf "."
  done
  echo
  [ "$(docker inspect -f '{{.State.Health.Status}}' piimask-mariadb)" = "healthy" ] || fail "MariaDB not healthy: docker logs piimask-mariadb"
  echo "[ok] MariaDB ready on 127.0.0.1:13306 (db=stt user=masker pw=masker-local)"
fi

echo
echo "Starting app... open http://127.0.0.1:${PIIMASK_PORT:-8080} when you see 'Started PiiMaskApplication'"
echo
ARGS=(:masking-app:bootRun)
[ "$SKIP_UI" -eq 1 ] && ARGS+=(-PskipUi)
exec ./gradlew "${ARGS[@]}"
