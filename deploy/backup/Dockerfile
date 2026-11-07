# The backup job: pg_dump of the HR Pay database every night, kept on the server and, when RCLONE_REMOTE is set,
# copied off the server (S3, Backblaze B2, Google Drive, SFTP... anything rclone supports).
FROM postgres:16
RUN apt-get update \
    && apt-get install -y --no-install-recommends rclone ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY backup.sh restore.sh restore-test.sh schedule.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/backup.sh /usr/local/bin/restore.sh /usr/local/bin/restore-test.sh /usr/local/bin/schedule.sh
ENV BACKUP_DIR=/backups RCLONE_CONFIG=/config/rclone/rclone.conf
ENTRYPOINT ["schedule.sh"]
