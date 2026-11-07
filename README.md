# HR Pay

Attendance, leave and payroll for organisations in DR Congo (pilot), built to be configured rather than coded:
schedules, holidays, overtime rates, taxes and permissions are data.

The design is in the project design document. Delivery is in five phases:

| Phase | Scope | Status |
| --- | --- | --- |
| 0. Foundation | Login, admin-registered users, roles and permissions, sites (allowed IPs and GPS area), audit log | This branch |
| 1. Attendance and leave | Schedules, holidays, check-in with OTP, missions, overtime approval, leave | Next |
| 2. Payroll core | Grades, pay elements, CNSS, IPR, loans, payslips | |
| 3. Evaluations and 13th month | Weighted evaluations, contests, 13th month | |
| 4. Payments and declarations | Bank and mobile-money files, CNSS and tax exports | |

## Stack

- `backend/`: Spring Boot 4.1, Java 26, PostgreSQL 16, Flyway, Spring Security (session cookie + CSRF, optional Google sign-in)
- `frontend/`: React 19 + TypeScript, Vite

## Run locally

```bash
docker compose up -d                       # PostgreSQL on :5432 (databases hrpay and hrpay_test)

cd backend
SAMPLE_DATA=true ./gradlew bootRun         # API on :8081, loads demo users (needs JDK 26)

cd ../frontend
npm install && npm run dev                 # app on http://localhost:5173
                                           # other API port: SERVER_PORT=9090 ./gradlew bootRun, API_URL=http://localhost:9090 npm run dev
```

Sample users (password `Demo@2026!`): `admin@demo.hrpay.cd`, `hr@demo.hrpay.cd`, `finance@demo.hrpay.cd`,
`manager@demo.hrpay.cd`, `employee1@demo.hrpay.cd` to `employee3@demo.hrpay.cd`. The sample site allows
`127.0.0.1` so check-ins work locally in phase 1.

Tests run against the `hrpay_test` database, which they wipe and re-create:

```bash
cd backend && ./gradlew test
```

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | local `hrpay` | Database |
| `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD` | empty | Creates the first administrator when there are no users. They must change the password at first sign-in. |
| `SAMPLE_DATA` | `false` | Loads the demo site and users. Never in production. |
| `COOKIE_SECURE` | `false` | Set to `true` behind HTTPS |
| `FORWARD_HEADERS` | `none` | Set to `native` behind a reverse proxy so the client IP comes from `X-Forwarded-For` |
| `CORS_ORIGINS` | `http://localhost:5173` | Only needed if the app is served from another origin |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID` / `..._CLIENT_SECRET` | empty | Turns on "Sign in with Google". Only pre-registered, active, verified emails are accepted. |

For production, build the app and copy it into the API's static folder so both are served from one origin:

```bash
(cd frontend && npm ci && npm run build) && cp -r frontend/dist/. backend/src/main/resources/static/
(cd backend && ./gradlew bootJar)
```

## Access control

Code checks permissions (for example `user.create`), never role names, so roles are edited in the app.
Built-in roles: Administrator, Human resources, Finance, Manager, Employee. Two rules sit on top of plain
permissions:

- Without `role.assign`, a user can only create and manage accounts whose only role is Employee. HR therefore
  cannot create or change an administrator.
- Permission and role changes, and disabled accounts, take effect on the user's next request.

The audit log records every sign-in and every change to users, roles and sites. A database trigger makes it
append-only.
