# Security

## Reporting a problem

Write to the provider's security contact (set it in this file before going live) rather than opening a public
issue. Say what you found, how to reproduce it, and what it exposes. You get an answer within three working days.

## What protects what

| Threat | Protection |
| --- | --- |
| Stolen password | Two-step sign-in, required for administrators, HR and finance; five failed passwords block the account for 15 minutes |
| A company reading another's data | Row-level security in PostgreSQL on every company table, with the app connecting as a role that cannot bypass it |
| A stolen backup or database copy | Bank and mobile money accounts, CNSS and tax numbers and authenticator secrets are encrypted with a key kept outside the database |
| Someone editing the audit log in the database | Append-only trigger, and a hash chain checked every night |
| A browser left signed in | Sessions end after 8 hours idle; a person signs out their other browsers from My account |
| Known vulnerable libraries | Dependabot updates, `npm audit` and a Trivy scan of the image in CI |

## Before going live

- Set `HRPAY_DATA_KEY` and keep a copy of it away from the server.
- Set `COOKIE_SECURE=true` (the production compose file serves HTTPS only).
- Have an external penetration test done on a copy of the installation, and fix what it finds.
- Have a lawyer check the privacy notice and the retention period against the DRC's Code du numérique (2023).
