# Installing HR Pay on a server

This folder runs HR Pay in production with Docker: the database (PostgreSQL 16), the app (API and web app in one
image), an HTTPS proxy (Caddy, with automatic Let's Encrypt certificates) and the backup job.

## What the server needs

| | Up to 300 employees | Up to 5,000 employees |
| --- | --- | --- |
| CPU | 2 cores | 4 cores |
| Memory | 4 GB | 8 GB |
| Disk | 40 GB SSD | 100 GB SSD |
| System | Ubuntu 24.04 LTS (or any Linux with Docker Engine 24+ and Docker Compose v2) | same |

- A domain name (for example `paie.company.cd`) whose DNS points to the server, and ports 80 and 443 open to the
  internet for the certificate. A server only reachable inside the company network uses `TLS_MODE=internal`.
- An SMTP account to send emails (check-in codes, raises, alerts).
- Somewhere off the server for backup copies: an S3 bucket, Backblaze B2, Google Drive, another server over SFTP...

## First installation

```bash
git clone https://github.com/nekiala/hr-pay.git && cd hr-pay/deploy
cp .env.example .env
nano .env                       # DOMAIN, TLS_MODE, DB_PASSWORD, BOOTSTRAP_ADMIN_*, SMTP_*, RCLONE_REMOTE
docker compose up -d --build    # builds the images, starts everything
docker compose ps               # every service "running", the app "healthy" after about a minute
```

Open `https://<DOMAIN>` and sign in with `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD`; you are asked to
choose a new password. Then remove both lines from `.env` and run `docker compose up -d` again.

As administrator, open **System**: it shows the version, the database size, the backups and the alerts. Give the
"system status" permission to whoever must receive alert emails (the Administrator role has it).

## Backups

The `backup` service backs up the database every day at `BACKUP_AT` (02:00 Kinshasa time by default):

- The file (`hrpay-YYYY-MM-DD_HHMMSS.dump`, compressed, with a `.sha256` checksum) goes to `BACKUP_DIR`
  (`deploy/backups`). It is read back after writing, so an unreadable backup counts as failed.
- The 14 newest are kept, plus the first of each of the last 12 months (`KEEP_DAILY`, `KEEP_MONTHLY`).
- With `RCLONE_REMOTE` set, each file is also copied off the server. A backup that stays on the server only is not
  enough: a disk failure, theft or ransomware takes both. Set this up before the first payroll.
- On the 1st of each month a **restore test** loads the newest backup into a scratch database, counts the people
  and payslips, and drops it.
- Every run is recorded. The app emails the administrators when a backup or a restore test fails, or when no
  backup has succeeded for 26 hours.

### Off-site copy

Create `deploy/rclone/rclone.conf` with one remote. For example, for an S3 bucket:

```ini
[offsite]
type = s3
provider = AWS
access_key_id = ...
secret_access_key = ...
region = eu-west-1
```

Then set `RCLONE_REMOTE=offsite:company-hrpay-backups` in `.env` and run `docker compose up -d backup`. Any
[rclone backend](https://rclone.org/overview/) works (`rclone config` on any computer writes the file for you).
Old copies are not deleted off-site: set a lifecycle rule on the bucket (for example delete after 400 days).

### Backup now, and restore

```bash
docker compose exec backup backup.sh                 # a backup right now (do one before every upgrade)
docker compose exec backup restore.sh                # lists the backups
docker compose stop app
docker compose exec backup restore.sh hrpay-2026-11-07_020000.dump   # asks you to type RESTORE
docker compose start app
docker compose exec backup restore-test.sh           # the monthly restore test, right now
```

To restore on a new server: install as above, copy the backup file into `deploy/backups/` (from the off-site copy),
start only the database and backup services (`docker compose up -d db backup`), restore, then
`docker compose up -d`.

## Upgrading

```bash
cd hr-pay/deploy
docker compose exec backup backup.sh     # always a fresh backup first
git pull
docker compose up -d --build             # the app updates the database by itself when it starts
docker compose logs -f app               # wait for "Started HrPayApplication"
```

The database changes only move forward. To go back to the previous version, restore the backup taken before the
upgrade, then `git checkout <previous tag>` and `docker compose up -d --build`.

## Monitoring

- **System page** (administrators): version, uptime, database size, people signed in, backups, scheduled jobs and
  alerts with their details.
- **Alert emails**: an unexpected error, a failed scheduled job, a failed or missing backup. The same problem is
  emailed at most once an hour and counted on the System page.
- **Health**: Docker restarts the app if `/actuator/health/liveness` stops answering. An outside uptime monitor
  (UptimeRobot, Better Stack...) can watch `https://<DOMAIN>/login`; the health URLs are not published on the internet.
- **Logs**: `docker compose logs app`. The app writes one JSON line per entry (ECS format), ready for a log collector
  such as Grafana Loki or Elastic. Docker keeps 10 files of 50 MB per service.

## When no administrator can sign in

Anyone can use *Forgot your password?* on the sign-in page when email works. If it does not, or the only
administrator lost their phone, run from this folder:

```sh
docker compose exec app java -jar /app/app.jar reset-password admin@example.cd --second-step
```

It prints a temporary password to give to the person (they choose their own at sign-in); `--second-step` also removes
their second step. The reset is written in the company's audit log. Leave out `--second-step` when only the password
is lost.

## Several app servers

Sessions, the sign-in lockout and the scheduled jobs are kept in the database, so two or more app servers can share
one database behind a load balancer, and each scheduled job runs on one of them only. A restart or an upgrade signs
no one out.

### Background jobs

Long tasks (calculating or closing the payroll of thousands of people, imports, every payslip in one zip) wait in a
queue kept in PostgreSQL (table `background_job`) and run on the workers of any app server, two threads each by default
(`JOBS_WORKERS`). Nothing else to install. For a large installation, run a container that only runs jobs:
`docker compose --profile worker up -d` (`WORKER_THREADS`, default 4), and optionally `APP_ROLE=web` on the app so
it leaves the jobs to the worker. A server that stops in the middle of a job hands it back: another server runs it
again after 2 minutes (at most 3 tries). Files made by jobs are kept 7 days. The System page shows the queue;
administrators are alerted when a job fails or waits more than 10 minutes.

## Settings

All settings are in `.env`; `.env.example` explains each one. Never commit `.env`: it holds the passwords.

## Demo server

A public demo runs on its own server, never on the production one, with:

```
SAMPLE_DATA=true
DEMO_MODE=true
TWO_STEP_ENFORCED=false
```

`DEMO_MODE` refuses to start without `SAMPLE_DATA`. At every start it **erases the whole database** and loads the sample company again, and it stops the app at 03:00 (Kinshasa time) so the restart policy brings it back fresh each night. The sign-in page then shows the demo accounts, and every page shows a "demo" banner. Do not point it at a database that holds anything worth keeping.
