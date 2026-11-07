#!/bin/sh
# Runs the backup every day at BACKUP_AT (HH:MM, in TZ), and a restore test on the 1st of each month after it.
set -u
: "${BACKUP_AT:=02:00}"
echo "Backups every day at $BACKUP_AT ($TZ), kept in $BACKUP_DIR${RCLONE_REMOTE:+ and copied to $RCLONE_REMOTE}."
last=""
while true; do
    now=$(date +%H:%M)
    today=$(date +%F)
    if [ "$now" = "$BACKUP_AT" ] && [ "$last" != "$today" ]; then
        last=$today
        backup.sh || echo "Backup failed (recorded for the administrators)."
        if [ "$(date +%d)" = "01" ]; then
            restore-test.sh || echo "Restore test failed (recorded for the administrators)."
        fi
    fi
    sleep 20
done
