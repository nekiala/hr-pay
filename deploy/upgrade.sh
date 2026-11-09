#!/usr/bin/env bash
# Upgrades this server to the newest code of the branch it follows (main by default):
# a fresh backup, git pull, rebuild, restart, then waits until the app answers. Run from anywhere:
#   /opt/hr-pay/deploy/upgrade.sh
set -euo pipefail
cd "$(dirname "$0")"

before=$(git rev-parse --short HEAD)
if docker compose ps --status running --services 2>/dev/null | grep -qx backup; then
  echo "== Backup before upgrading"
  docker compose exec -T backup backup.sh
else
  echo "== Backup service not running: no backup taken"
fi

echo "== Pulling the code"
git pull --ff-only
after=$(git rev-parse --short HEAD)
echo "   $before -> $after"

echo "== Building and restarting"
docker compose up -d --build --remove-orphans

echo "== Waiting for the app"
for _ in $(seq 1 60); do
  state=$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q app)" 2>/dev/null || echo starting)
  if [ "$state" = healthy ]; then
    echo "   app healthy on $after"
    docker image prune -f >/dev/null
    exit 0
  fi
  sleep 5
done
echo "The app is not healthy after 5 minutes: docker compose logs --tail 200 app" >&2
echo "To go back: restore the backup taken above, git checkout $before, docker compose up -d --build" >&2
exit 1
