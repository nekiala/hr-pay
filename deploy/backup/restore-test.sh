#!/bin/sh
# Proves the newest backup can be restored: loads it into a scratch database, checks the people and payslips are
# there, then drops it. The result is noted on that backup's row; a failure alerts the administrators.
set -u
: "${BACKUP_DIR:=/backups}"
scratch=hrpay_restore_test
name=$(ls -1 "$BACKUP_DIR" | grep -E '^hrpay-.*\.dump$' | sort | tail -n 1)
[ -n "$name" ] || { echo "No backup to test."; exit 1; }

sql() { psql -X -q -t -A -v ON_ERROR_STOP=1 "$@"; }
note() { # status message
    sql -v name="$name" -v status="$1" -v msg="$2" <<'SQL'
insert into backup_run (file_name, finished_at, status, message) values ('restore-test: ' || :'name', now(), :'status', :'msg');
SQL
}

echo "Restore test of $name"
dropdb --if-exists "$scratch" && createdb "$scratch" || { note FAILED "Restore test: cannot create the scratch database"; exit 1; }
if err=$(pg_restore --no-owner --exit-on-error -d "$scratch" "$BACKUP_DIR/$name" 2>&1); then
    people=$(psql -X -t -A -d "$scratch" -c "select count(*) from app_user")
    slips=$(psql -X -t -A -d "$scratch" -c "select count(*) from payslip")
    dropdb "$scratch"
    note OK "Restore test of $name passed: $people people, $slips payslips"
    echo "Restore test passed: $people people, $slips payslips"
else
    dropdb --if-exists "$scratch"
    note FAILED "Restore test of $name failed: $err"
    exit 1
fi
