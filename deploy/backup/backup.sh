#!/bin/sh
# One backup of the HR Pay database, recorded in backup_run (the app alerts the administrators when it fails or
# when none succeeded for 26 hours). Uses the PG* variables to reach the database.
#
#   KEEP_DAILY    newest backups kept (default 14)
#   KEEP_MONTHLY  months for which the first backup of the month is also kept (default 12)
#   RCLONE_REMOTE optional rclone destination, for example offsite:hrpay-backups
set -u
: "${BACKUP_DIR:=/backups}" "${KEEP_DAILY:=14}" "${KEEP_MONTHLY:=12}" "${RCLONE_REMOTE:=}"

mkdir -p "$BACKUP_DIR"
name="hrpay-$(date +%Y-%m-%d_%H%M%S).dump"
file="$BACKUP_DIR/$name"

sql() { psql -X -q -t -A -v ON_ERROR_STOP=1 "$@"; }

id=$(sql -v name="$name" <<'SQL'
insert into backup_run (file_name) values (:'name') returning id;
SQL
) || { echo "Cannot reach the database to record the backup."; exit 1; }

finish() { # status message offsite size
    sql -v id="$id" -v status="$1" -v msg="$2" -v offsite="$3" -v size="$4" <<'SQL'
update backup_run set finished_at = now(), status = :'status', message = nullif(:'msg', ''),
    offsite = :'offsite'::boolean, size_bytes = nullif(:'size', '')::bigint where id = :id;
SQL
}

echo "Backing up to $file"
if ! err=$(pg_dump --format=custom --compress=6 --file="$file.part" 2>&1); then
    rm -f "$file.part"
    finish FAILED "pg_dump failed: $err" false ""
    exit 1
fi
# A backup that cannot be read back is no backup.
if ! err=$(pg_restore --list "$file.part" 2>&1 >/dev/null); then
    rm -f "$file.part"
    finish FAILED "The backup file is unreadable: $err" false ""
    exit 1
fi
mv "$file.part" "$file"
(cd "$BACKUP_DIR" && sha256sum "$name" > "$name.sha256")
size=$(stat -c %s "$file")

offsite=false
if [ -n "$RCLONE_REMOTE" ]; then
    if err=$(rclone copy "$file" "$RCLONE_REMOTE" 2>&1 && rclone copy "$file.sha256" "$RCLONE_REMOTE" 2>&1); then
        offsite=true
    else
        finish FAILED "Saved on the server, but the off-site copy to $RCLONE_REMOTE failed: $err" false "$size"
        exit 1
    fi
fi

# Keep the newest KEEP_DAILY backups, and the first backup of each of the last KEEP_MONTHLY months.
ls -1 "$BACKUP_DIR" | grep -E '^hrpay-[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{4,6}\.dump$' | sort > /tmp/all
tail -n "$KEEP_DAILY" /tmp/all > /tmp/keep
awk -F'[-_]' '{ m = $2 "-" $3; if (!(m in seen)) { seen[m] = 1; print } }' /tmp/all | tail -n "$KEEP_MONTHLY" >> /tmp/keep
sort -u /tmp/keep -o /tmp/keep
for old in $(comm -23 /tmp/all /tmp/keep); do
    rm -f "$BACKUP_DIR/$old" "$BACKUP_DIR/$old.sha256"
done

finish OK "" "$offsite" "$size"
echo "Backup done: $name ($size bytes)${RCLONE_REMOTE:+, copied to $RCLONE_REMOTE}"
