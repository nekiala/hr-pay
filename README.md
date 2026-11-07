# HR Pay

Attendance, leave and payroll for organisations in DR Congo (pilot), built to be configured rather than coded:
schedules, holidays, overtime rates, taxes and permissions are data.

The design is in the project design document. Delivery is in five phases:

| Phase | Scope | Status |
| --- | --- | --- |
| 0. Foundation | Login, admin-registered users, roles and permissions, sites (allowed IPs and GPS area), audit log | Done |
| 1. Attendance and leave | Schedules, holidays, check-in with OTP, missions, overtime approval, leave, reports | This branch |
| 2. Payroll core | Grades, pay elements, CNSS, IPR, loans, payslips | Next |
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
`127.0.0.1` so check-ins work locally in phase 1. Check-in codes are not emailed without a mail server: an admin
reads them on the Messages page. Sample employees report to the sample manager and have 2026 annual leave.

## Attendance rules (phase 1)

- Paid time per day = minutes worked inside the schedule, plus lateness or early departure that is within the grace
  period or not deducted by the schedule, capped at the scheduled minutes. Unpaid lunch never counts.
- Earned to date = gross monthly salary / scheduled minutes of the month x paid minutes. Holidays on workdays and
  paid leave count as paid time.
- Work outside the schedule, on rest days or on holidays is overtime, rounded down to the policy step, paid only when
  approved, priced with the policy's weekly tiers (DRC sample: first 6 weekday hours x1.30, beyond x1.60, rest days
  and holidays x2.00).
- A day left open is closed at the schedule end (or the approved mission's end) and flagged for HR.
- Clock entries are never edited: HR voids a wrong one with a reason and adds the right one.
- The DRC values (overtime tiers, holidays, leave kinds, leave days) are samples to confirm with a payroll specialist.

## Payroll rules (phase 2)

- Master data: grade categories (leave days, anniversary raise % and when it starts), grades with a base salary,
  occupations. A contract sets the grade; the basic is the negotiated amount or the grade's base.
- Pay elements are data: each says if it is an earning or a deduction, how it is computed (fixed, % of basic, per day
  worked, per child in charge, entered each month) and whether it is subject to CNSS and taxable for IPR. HR can
  override, add or exclude an element for one employee, and enter one-off bonuses or deductions for a month.
- A month's payslip: basic x paid hours / scheduled hours; approved overtime; allowances and bonuses; CNSS on the
  elements subject to it; IPR on taxable elements less employee CNSS, worked out in CDF on the annual barème, then
  reduced per dependant and capped; other deductions; loans and advances within a maximum share of net pay;
  employer charges (CNSS, INPP by company size, ONEM, IERE for expatriates).
- Statutory rates and the IPR barème carry an effective date, so old payslips can be reproduced.
- USD/CDF uses the BCC rate, fetched at start-up and twice a day from `EXCHANGE_RATES_URL`; HR can enter a rate.
  Each run records the rate it used.
- Workflow: HR calculates (again as often as needed), Finance approves (not the person who calculated), HR closes.
  Closing numbers the payslips, records loan repayments and shows employees their payslips. Pay data of an approved or
  closed month is locked.
- Anniversary raises are applied each morning for permanent staff; HR gets one email on the 1st of each month.
  Anniversaries before `RAISES_FROM` (payroll setting) are not applied.
- **Every DRC rate and the barème are samples from memory.** A DRC payroll accountant must confirm them and check a
  set of test payslips before the first real payroll.

With `SAMPLE_DATA=true`, last month's attendance is filled in for the demo staff so a payroll can be tried at once.

Tests run against the `hrpay_test` database, which they wipe and re-create:

```bash
cd backend && ./gradlew test
```

## Evaluations and 13th month (phase 3)

- **Templates** set how often people are evaluated (monthly, quarterly, every six months) and the weight of each criterion (weights add up to 100). One active template per grade category; a template with no category applies to everyone else.
- **Criteria** are scored 0 to 100 by the direct manager, or by the app when the criterion is "from attendance": 100, minus 5 per late day and 10 per absent day in the period (up to today for a period still running).
- **Flow:** the manager (or HR) starts the evaluation for a period, scores it and submits it. The employee acknowledges it, or disagrees with a comment, which sends it back. The manager can revise and resubmit; HR approves, and must write a decision when the employee contested. Nobody evaluates or approves their own evaluation.
- **13th month** (settings under Payroll, `THIRTEENTH_*`): base (average or last basic of the year) x the year's score, prorated by months under contract if set. The year's score is the average of the approved evaluations ending in that year, weighted by the months each covers; with none, HR's fallback % is used. It is added as the `THIRTEENTH_MONTH` line (subject to CNSS and IPR by default, editable under Pay elements) in the payroll of the pay month.

## Payments and declarations (phase 4)

- **Payments** (Finance, `payroll.pay`): once HR closes a month, the net pays are grouped by how each person is paid (one bank, one mobile money operator, or cash) and currency. Each group becomes a payment file (CSV): bank transfers with the account and the debit account, mobile money with the number as 243XXXXXXXXX, cash as a list to sign. The payment details are taken from the employee file when the file is made and copied into it. Finance then records the date and the bank or operator reference. A file not yet paid can be cancelled, which lets its people go into a new file. People with incomplete details are listed and left out until their file is fixed.
- **Declarations** (`declaration.manage`, Finance and HR): CNSS (employee and employer shares, CNSS numbers), IPR withheld (NIF, taxable base), INPP, ONEM and IERE, computed from the closed payslips and stated in CDF at the payroll's rate. Each one can be printed or downloaded as CSV, then recorded as filed with its receipt reference. The employer's own numbers (CNSS, NIF, Id. Nat., RCCM, INPP, ONEM, bank) are kept under Declarations.
- **Year summary**: totals for everyone for a year (HR), and an annual pay statement per employee, month by month with the date each pay was made. Employees open their own statement from My payslips.
- The CSV layouts are generic. If a bank, operator or the CNSS/DGI portal wants a fixed format, it can be added as another export.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | local `hrpay` | Database |
| `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD` | empty | Creates the first administrator when there are no users. They must change the password at first sign-in. |
| `SAMPLE_DATA` | `false` | Loads the demo site and users. Never in production. |
| `EXCHANGE_RATES_URL` | BCC rate API | Source of the daily USD/CDF rate |
| `EXCHANGE_RATES_FETCH` | `true` | Set to `false` to enter rates by hand only |
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
