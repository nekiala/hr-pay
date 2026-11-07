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
reads them on the Messages page. Sample employees report to the heads of the sample structure and have 2026 annual leave.

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

## Organizational structure (phase 5)

- Levels follow the usual DRC pattern and can be renamed or extended under Org chart › Levels, import and moves: Direction générale, Direction, Département, Service, Section, Équipe, plus the Cellule, a staff unit that can sit anywhere. A unit sits under a higher level; levels may be skipped.
- Units have a code, an optional cost centre and dates. A unit closes only when nobody works in it and it has no open sub-units; history is kept.
- Each unit has at most one head and one deputy at a time, either of them possibly ad interim. A unit can be vacant, and one person can head any number of units.
- Every employee belongs to exactly one unit at a time. New users need a unit; moves are dated and kept (hire, transfer, promotion, reorganisation, correction). A correction fixes the current line instead of adding one.
- Who someone reports to: the head of their unit, or, if it is vacant or they are the head, the nearest head above.
- The org chart (units or people) is visible to everyone and prints to PDF; headcounts and names of members need `user.read`. The structure can be exported and imported as CSV (`code,name,level,parent,cost_centre,head_registration_no`, all or nothing).
- `org.manage` (HR) edits the structure.

## The structure across the app (phase 6)

- **Who approves**: a head approves missions, overtime and leave, and scores evaluations, for everyone in the units they head and every unit below. The deputy does the same only while the unit has no head, or while the head is on approved leave or an approved mission that day. A manager set by hand on a user is an exception: that person also approves, and becomes who the employee "reports to". Migration V18 removed hand-set managers that only repeated the structure.
- **Users**: the list shows each person's unit and can be filtered by a unit, including the units below it. The employee file shows the unit.
- **Payslips** keep the unit, Direction and cost centre (the unit's own or the nearest one above) of the employee at the end of the month. The payroll page has subtotals and a filter by Direction.
- **Reports**: the attendance and finance spreadsheets have Direction and unit columns and can be limited to a unit. Reports › Headcount and payroll cost by Direction gives people, payslips, gross, employer charges and total cost per Direction or per unit, in USD and in CDF at the payroll rate, also as CSV.

## Approvals and leave rules (phase 7)

- **Attendance corrections** (`/corrections`): an employee asks for a forgotten check-in or check-out to be added, or a wrong one removed, up to 62 days back. A head of their branch proposes it (or rejects it with a reason), and HR applies it through the usual correction, with "Request #n: reason" as its note. A head can propose one directly for someone in their branch; HR can also apply a request no head has looked at yet. Days older than two months go to HR directly.
- **Unit filters**: the approval lists for overtime, leave, missions and corrections can be narrowed to a unit and the units below it. Payment files and declarations show subtotals by Direction; the files sent to the bank, provider, CNSS or DGI stay company-wide.
- **Org chart by site**: when people work at more than one site, the chart can show one site, with the headcounts of that site.
- **Evaluation templates per unit**: a template can apply to a unit and every unit below it. The most specific wins: the person's unit (or the nearest unit above with a template) for their grade category, then for anyone in it, then the grade category's template, then the one for everyone.
- **Leave**: each kind keeps a share of the day's pay (sick leave is a sample 66.67%, to confirm with a payroll specialist). A one-day request can cover the morning or the afternoon only (0.5 day). Annual leave gains seniority days (sample: 1 more day per 5 full years of service on 1 January, set in Leave settings). Kinds that need a document (sick, maternity, family events) take a PDF, JPEG or PNG of up to 5 MB; the manager approves, then HR checks the document before the leave counts. HR approving directly needs the document first.

## Reports, documents and privacy (phase 8)

- **Presence** (`/presence`, HR and heads): who is in on a day (expected, in, late, not in yet, absent, on leave, on mission), and the exceptions of a period of up to 92 days: absences, late arrivals, early departures, days without a check-out, days closed automatically and days corrected by HR. Filter by unit; export the exceptions as Excel or CSV.
- **Excel exports**: the attendance, finance and cost reports download as Excel workbooks (numbers are number cells, header frozen) as well as CSV. Payment files and declarations stay CSV until the bank, operators, CNSS and DGI formats are known.
- **PDF payslips**: `/api/payslips/{id}.pdf` (Download PDF on the payslip), with the employer's details, earnings, deductions, net pay, employer charges and the year-to-date totals (closed months of the year plus this one). The payslip screen also shows the year to date.
- **Holiday moved inside a closed month**: when HR moves a holiday's observed date into or out of a month whose payroll is closed, that month's basic pay is worked out again with the new calendar; the difference goes into the first payroll not yet approved as "Holiday change, earlier month" (more, or less). Closed payslips never change. Payroll › Holiday changes lists them; calculate the target month again to include them. Moving the holiday back cancels the difference.
- **Privacy**: at their first sign-in each person reads a notice on what is recorded, why, who sees it and for how long (also under Privacy at the bottom of every page). GPS positions of check-ins are erased after a period set on the Sites page (sample 90 days, 0 keeps them); the check-in itself stays.

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
