#!/usr/bin/env bash
# PostgreSQL 백업 (DEPLOY.md D-7)
# VM crontab 등록 예: 0 4 * * * /opt/catch-my-ride-server/deploy/backup-db.sh >> /opt/catch-my-ride-server/backups/backup.log 2>&1
set -euo pipefail
cd "$(dirname "$0")/.."

mkdir -p backups
docker compose exec -T postgres pg_dump -U catchmyride catchmyride \
  | gzip > "backups/catchmyride-$(date +%Y%m%d-%H%M%S).sql.gz"

# 30일 지난 백업은 정리
find backups -name 'catchmyride-*.sql.gz' -mtime +30 -delete
echo "backup done: $(ls -t backups/catchmyride-*.sql.gz | head -1)"
