#!/bin/sh
# Puts a backup back into the HR Pay database, replacing what is there. Stop the app first:
#   docker compose stop app
#   docker compose run --rm backup restore.sh hrpay-2026-11-07_020000.dump
#   docker compose start app
# Without a file name it lists the backups. Add --yes to skip the question.
set -u
: "${BACKUP_DIR:=/backups}"

if [ $# -eq 0 ]; then
    echo "Backups in $BACKUP_DIR (newest last):"
    ls -1 "$BACKUP_DIR" | grep '\.dump$' || echo "  none"
    exit 0
fi
name=$1
file="$BACKUP_DIR/$name"
[ -f "$file" ] || { echo "No backup $file"; exit 1; }
if [ -f "$file.sha256" ]; then
    (cd "$BACKUP_DIR" && sha256sum -c "$name.sha256") || { echo "The file does not match its checksum; not restoring."; exit 1; }
fi
if [ "${2:-}" != "--yes" ]; then
    printf "This replaces everything in database %s with %s. Type RESTORE to go on: " "$PGDATABASE" "$name"
    read -r answer
    [ "$answer" = "RESTORE" ] || { echo "Nothing changed."; exit 1; }
fi
pg_restore --clean --if-exists --no-owner --single-transaction --exit-on-error -d "$PGDATABASE" "$file" || exit 1
# The backup was taken while its own row said RUNNING: close it, and note the restore.
psql -X -q -v ON_ERROR_STOP=1 -v name="$name" <<'SQL'
update backup_run set status = 'OK', finished_at = coalesce(finished_at, started_at), message = 'Restored from this backup'
where file_name = :'name' and status = 'RUNNING';
insert into backup_run (file_name, finished_at, status, message)
values ('restore: ' || :'name', now(), 'OK', 'The database was restored from ' || :'name');
SQL
echo "Restored $name into $PGDATABASE. Start the app again: docker compose start app"
