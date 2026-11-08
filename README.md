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
  occupations. A contract sets the grade; the basic is always the grade's base, and agreed pay above it is a salary complement.
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

## Company and payslip layouts

- **Company** (`/company`, `company.manage`): the employer's name, address and legal numbers (RCCM, Id. Nat., NIF, CNSS, INPP, ONEM), the logo (PNG or JPEG, at most 512 KB), the legal form and capital, phone, email, website and a payslip footer.
- **Three layouts** for payslips, chosen on the same page: **Classic** (one table with base and rate, year to date, employer charges), **Compact** (earnings and deductions side by side, half a page) and **Ledger** (a ruled table with element codes and separate employee and employer columns). "See a sample" renders a made-up payslip with the company's real header, so no one's pay is shown. Staff can also open any payslip in another layout with `?template=COMPACT` on the PDF link.
- **Employee details on every payslip**: matricule, grade, occupation, unit, hire date and seniority (years and months at the end of the month), marital status as a code (S single, M married, D divorced, W widowed), the children counted for family allowances, contract, CNSS number and NIF. Marital status and hire date are kept in the payslip's snapshot from now on; older payslips take them from the current file.

## Production (phase 9)

- **Install, upgrade, backups**: see [deploy/README.md](deploy/README.md). One Docker image holds the API and the web app (`Dockerfile` at the root); `deploy/docker-compose.yml` adds PostgreSQL, HTTPS with Caddy and the backup job.
- **Several servers**: sessions (Spring Session JDBC), the sign-in lockout and the scheduled jobs are kept in the database; each scheduled job runs on one server at a time (`job_lock`). A restart signs no one out.
- **Monitoring**: the System page (`system.monitor`) shows the version, database size, backups, scheduled jobs and alerts. Unhandled errors, failed jobs and failed or missing backups email the people holding `system.monitor`, at most once an hour per problem. Health probes at `/actuator/health/liveness` and `/readiness`; `LOG_FORMAT=ecs` writes JSON logs.
- **Load test**: `./gradlew loadTest` adds 5,000 people to the test database, calculates their payroll and checks in 1,000 of them (sign-in, code by email, confirm), 25 at a time. Smaller: `-Pload.employees=1000 -Pload.checkIns=200`.

## Companies and licences

One installation holds many companies. Every row of company data carries its company, and PostgreSQL itself (row-level
security) only shows the app the rows of the signed-in user's company, so a query that forgets a filter still cannot
reach another company. The app's connections run as the `hrpay_app` role, created by the migrations; migrations use a
connection of their own as the database owner. If the database user cannot create roles, sign the app in as a
non-superuser owner of the tables: the policies apply to it as well.

The provider's administrators create a company on **Companies**: name, country, plan by size (up to 50, 51 to 300, 301
to 1,000, over 1,000 employees), modules (Attendance, Payroll, Evaluations) and the first administrator, who gets the
Administrator and HR roles and a temporary password. The company starts with the country's sample grades, rates,
holidays, pay elements, schedule, structure levels and roles (`backend/src/main/resources/packs/CD.json`). A module
outside the plan is hidden and its changes are refused; its data can still be read. Going over the plan's size only
shows a warning; payroll is never blocked.

On premise, the provider signs a licence file on their own computer and the customer installs it on **Licence**:

```bash
cd backend
./gradlew -q licence --args="keys ~/.hrpay-licence"     # once: the provider's key pair; keep private.key secret
# put the printed public key in src/main/resources/licence/public-key.txt, then build the release
./gradlew -q licence --args="issue --key $HOME/.hrpay-licence/private.key --id L-2026-001 --licensee 'Acme SARL' \
    --expires 2027-12-31 --employees 300 --companies 1 --modules ATTENDANCE,PAYROLL,EVALUATIONS --out acme.licence"
```

The licence is checked offline. In the last 30 days the administrators are warned; after the end date a grace period
(30 days by default) keeps everything working; after it, changes in the modules stop until the renewal is installed,
while sign-in, reading and exports keep working.

## Billing and support (phase 11)

On a SaaS installation (`LICENSING_MODE=saas`) each company has a subscription. It starts with a free trial (30 days by
default); on its last day the first invoice is issued, in advance, for one month or one year. Each company's
administrator picks monthly or yearly, and US dollars or Congolese francs, on **Billing**. An invoice in francs converts
the plan's price at the BCC rate of the invoice's day. Invoices are numbered `HP-2026-00001`, carry the tax (TVA 16% by
default; check it with your accountant), and are emailed to the company's administrators, with a PDF carrying the
provider's name, logo and legal details from its **Company** page.

The provider sets the prices per plan, the trial, the payment terms, the grace days, the tax, and the bank and mobile
money details on **Invoices**. Payments are recorded by hand against an invoice: bank transfer, M-Pesa, Orange Money,
Airtel Money, cash or other, with the transaction reference, which can only be recorded once. A payment gateway can
later call the same recording step.

An invoice still unpaid after its due date (15 days) and the grace days (15) makes the company **read-only**: everyone
still signs in, reads and exports, including payslips, but no change is accepted. Changes come back when the payment is
recorded. The provider can also suspend a company by hand, and lift either suspension, on **Companies**. A job runs every
morning at 7:00 Kinshasa time (`app.billing.cron`) to issue the invoices that are due and suspend the unpaid ones.

**Support access**: a company's administrator opens it on **Billing** for 1 to 14 days, and can close it at any time.
While it is open, the provider's administrators can open the company from its page on **Companies** and see it as its
users do, read-only. Every request they make is listed on the company's Billing page, and the start and the end are in
both companies' audit logs. On premise there are no invoices; support access still works between companies of the
installation.

## Security and personal data (phase 12)

**Two-step sign-in.** After the password (or Google, or Microsoft), members of the roles marked *two-step required*
(Administrator, Human resources and Finance by default; set on **Roles**) enter a six-digit code from an authenticator
app (Google Authenticator, Microsoft Authenticator, Aegis…), or a code sent by email when an email server is set. The
first sign-in sets it up with a QR code and gives ten one-time recovery codes. Anyone else can turn it on in **My
account**. An administrator resets the second step of someone who lost their phone (Users, then the person); the
person sets it up again at their next sign-in. Five wrong codes end the attempt.

**Signed-in browsers.** **My account** lists the browsers a person is signed in on and signs out one or all the others.
A new password, a password reset, a disabled account or a second-step reset signs the person out everywhere.

**Forgotten password.** *Forgot your password?* on the sign-in page emails a link (to `PUBLIC_URL/reset-password`) that
works once, for 30 minutes; at most three links per person an hour, and the page answers the same whether or not the
address has an account. Only a hash of the link is kept. The new password signs the person out everywhere, and the
second step is still asked at the next sign-in. An administrator can also set a new password for anyone (Users, then
the person).

**When no administrator can sign in.** The provider opens the company on **Companies** and, under *Administrators*,
emails one of them a link or sets a temporary password to give by phone, and can remove their second step (lost
phone). A reason is required; it is written in the company's audit log and its administrators are told. On premise,
run on the server:

```sh
docker compose exec app java -jar /app/app.jar reset-password admin@example.cd [--second-step]
```

It prints a temporary password to change at the next sign-in, lifts a lockout, signs the person out everywhere and
writes the reset in the company's audit log. It uses the app's `DB_URL`, `DB_USER` and `DB_PASSWORD` and does not
start the app.

**Sign in with Microsoft 365.** Register an app in the Microsoft Entra admin centre (accounts in any organizational
directory, redirect URI `https://<your domain>/login/oauth2/code/microsoft`), then set `MICROSOFT_CLIENT_ID` and
`MICROSOFT_CLIENT_SECRET`. Each company's administrator pastes its own tenant ID on **Company**; a Microsoft sign-in
from any other tenant is refused. As with Google, only accounts already registered in HR Pay can sign in. SAML is not
available yet.

**Encryption.** With `HRPAY_DATA_KEY` set (`openssl rand -base64 32`, or a file named by `HRPAY_DATA_KEY_FILE`), bank
and mobile money accounts, CNSS and tax numbers (in the employee file and on payslips) and authenticator secrets are
stored encrypted (AES-256-GCM). Values written before the key was set are encrypted at the next start. Keep a copy of
the key away from the server and its backups: a backup cannot be read without it. Salaries are not encrypted in the
database; company separation, permissions and the audit log protect them. The System page says when no key is set.

**Audit log.** Append-only (a trigger refuses changes), and chained: each entry's hash covers its content and the hash
of the one before, so an entry changed or deleted directly in the database shows. The chain is checked every night
(2:30 Kinshasa) and on the System page; a break raises an alert. Write down the last hash shown there from time to
time (or keep it with your backups): it proves later that nothing before it was rewritten.

**Personal data.** Each person downloads everything held about them (**My account**, a JSON file) and asks HR to
correct something; HR answers on **Privacy**, where they can also download a person's data. A former employee (account
disabled, last contract ended) can be erased once the retention period after they left has passed (10 years by
default, set on **Privacy**): name, contact details, employee file, dependants, GPS positions and the identifiers
copied on payslips are removed; payroll amounts, the registration number and the audit log stay. Erasure is done by
HR, one person at a time, and cannot be undone. The retention period and the wording of the privacy notice must be
checked by a lawyer against the DRC's Code du numérique (2023).

**Dependencies.** Dependabot opens weekly update pull requests (`.github/dependabot.yml`); CI fails on high or
critical advisories in the browser app (`npm audit`) and in the container image (Trivy). See `SECURITY.md`.

## French and country packs (phase 13)

- The interface, payslips, declarations, invoices, emails and WhatsApp codes are in French, with English kept. Each
  company has a language (French by default) and each person can choose their own on My account; the sign-in page has
  a switch. Numbers and dates follow the language (fr-FR or en-GB). Interface texts are in `frontend/src/fr.ts`,
  server texts in `src/main/resources/i18n/fr.json`, keyed by their English text.
- Each company has a country, chosen when the platform creates it: the DRC (CDF, CNSS, IPR, INPP, ONEM, IERE) or the
  Republic of the Congo (XAF, CNSS, ITS, TUS). The country pack sets grades, holidays, overtime tiers, pay elements,
  statutory rates and declarations.
- **Republic of the Congo, to verify with Albert Mbodo before any real payroll** (all values are samples from memory):
  CNSS pension 4% employee and 8% employer up to 1,200,000 XAF a month; family benefits 10.03% and work accidents
  2.25% (employer) up to 600,000 XAF; TUS 7.5% of gross; ITS with 20% professional deduction and the family quotient
  (1 part, +1 for a spouse, +0.5 per child, at most 6.5), bands per part 1% / 10% / 25% / 40% at 464,000 / 1,000,000 /
  3,000,000 XAF a year; SMIG 90,000 XAF; overtime 110% / 125% / 150% / 200%; the 7 public holidays; the declaration
  formats and the payslip mentions.

## Payroll completeness (phase 14)

- **Departure and final settlement**: open a person on the Users page and choose Departure. HR Pay works out the notice pay (when not worked), the unused annual leave paid out, the severance and the share of the 13th month from the rates on the Rates page (`SETTLEMENT_DAYS_PER_MONTH`, `SEVERANCE_DAYS_PER_YEAR`, `JOB_SEARCH_DAYS_PER_WEEK`) and the notice of the person's grade category; HR can change each amount. Confirming (by someone other than the leaver) writes the amounts as pay entries of the last month, takes the leave out of the balance and ends the contract. The last payslip pays only the days up to the last day and takes what is left of loans, within the allowed share of net pay. The certificate of employment is a PDF numbered `CT-YYYY-NNNN`. The departure can be taken back while that month is still open.
- **Annual leave pay** (V37, both country packs): Leave settings say whether the company pays annual leave (`LEAVE_ANNUAL_PAID`; both labour codes require it, the setting warns), how much of a year's leave can be carried (`LEAVE_CARRY_PERCENT`) and for how many more years (`LEAVE_CARRY_YEARS`): DRC 50 % for 1 year (art. 140, "only half of the leave over two years"), Republic of the Congo 100 % for 3 years (art. 120, the right lapses after 3 years). Closing a leave year (Leave settings) shows, then writes, the days that lapse (`EXPIRED` ledger rows). Employees see on the Leave page the days earned so far this year (one twelfth a month), carried from earlier years, taken, left, and what lapses when. **Leave pay** (Payroll > Leave pay, V38): approved annual leave waiting for its allowance (allocation de congé) is listed with the date to pay by, the last working day before the leave (DRC art. 145). HR works it out on request, on its own payslip (bulletin d'allocation de congé): the remuneration at departure (art. 142) for the leave days, i.e. the prorated elements in the CNSS base of a normal month (basic, `COMPLEMENT`) ÷ `SETTLEMENT_DAYS_PER_MONTH` × the leave days, plus the average of bonuses, commissions and overtime of the closed payslips of the 12 months before. Housing, transport and family allowances are not remuneration (art. 7) and are left out: housing and family allowances keep running on the monthly payslip for the leave days (art. 138, 142), transport is paid per day worked only. CNSS and income tax are withheld on the leave payslip and the net is paid. Finance approves it (four eyes; it is then numbered `AC-YYYY-NNNN` and the employee sees it under My payslips, with its PDF), then records it as paid. The payslips of the months the leave covers do not pay the remuneration of those days again: they add the allocation's gross (`LEAVE_ALLOWANCE`) so that CNSS and income tax are worked out on the whole month, and take back the net already paid (`LEAVE_ADVANCE`); declarations, payments and accounting follow. Leave cancelled before payment cancels the allowance; after payment the net stays taken back and the days worked are paid. The Leave page shows HR, payroll and managers (their branch) who takes annual leave and when, with where its pay stands; Dashboards show leave pay by month. When annual leave is not paid, leave days pay nothing and the departure pays out no leave. To check with the payroll expert.
- **Negotiated salary complement** (V36, both country packs): pay element `COMPLEMENT`, "Complément de salaire (négocié)", the part of a person's agreed pay above their grade's basic. A fixed amount set per person on the employee file (Pay elements), from a month; it is salary, so CNSS and income tax apply and it is prorated like the basic.
- **IRPP, DR Congo** (V35): from 1 January 2026 the IRPP replaces the IPR on salaries (Loi n° 23/053 du 30 novembre 2023). New bands from that date, annual: 3 % up to 1 944 000 FC, 15 % up to 21 600 000 FC, 30 % up to 43 200 000 FC, 40 % above; the existing cap (30 % of the taxable base) and 2 % reduction per dependant (nine at most) stay. The postnom and the commune or territory are in the employee file (the DGI's monthly declaration asks for them). To check with the payroll expert.
- **Agreed pay is entered as a net**: on a new contract or salary, HR enters the net agreed with the person. The basic stays the grade's; HR Pay works out the `COMPLEMENT` so that a normal month (scheduled days, housing, transport, CNSS and IPR with the person's dependants) pays that net, and shows basic, complement, gross, CNSS, IPR and net before saving. An agreed salary below the grade's basic is refused (choose a lower grade); an agreed gross above it keeps the basic and makes the rest the complement. Certificates, letters and final settlements state basic + complement.
- **Job classification, DR Congo** (V34 and the CD pack): four grade categories, Personnel d'exécution (categories 1 to 5), Agents de maîtrise, Cadres de collaboration and Cadres de direction, and 18 grades from the legal classification (Ordonnance 67-442bis du 1er octobre 1967, annexe II, and the interprofessional wage grid): manœuvre ordinaire and lourd, manœuvre spécialisé, semi-qualifié échelons 1 to 3, qualifié 1 and 2, hautement qualifié, maîtrise 1 to 4, cadre de collaboration 1 to 4, cadre de direction (negotiated). Base salaries are the January 2026 daily minimum of each step (SMIG 21 500 FC, Décret 25/22 du 30 mai 2025 as quoted by a secondary source) × 26 days at 2 800 FC to the dollar. Companies whose categories still had the sample names are moved over; contracts stay on their grade row. To check with the payroll expert against the Journal officiel.
- **Notice periods** (Payroll settings, per grade category): a base in days or months, days added per full year of service, working days (every day but Sundays and public holidays) or calendar days, and the share a person who resigns owes. DR Congo defaults: categories 1 to 5, 14 working days + 7 per year (Labour Code art. 64); agents de maîtrise, 1 month + 9 working days per year; cadres, 3 months + 16 working days per year (arrêté 12/CAB.MIN/TPS/117/2005); the employee owes half (art. 64); one paid job-search day a week (art. 65). Republic of the Congo: the Code leaves the length to the collective agreement (art. 39), so the samples (1, 2, 3 months) must be replaced; two job-search days a week (art. 40). On a departure HR gives who gave notice and when: HR Pay shows when the notice ends, pays the employer's notice not worked, or deducts from the settlement (`NOTICE_OWED`) the notice an employee who resigns did not give or work, and warns when notice was given during approved leave. To check with the payroll expert.
- **Corrections of a closed month**: a closed payslip is never changed. Choose Correct on it: the payslip is worked out again with today's data (plus any lines you add), and the difference is either a complementary payslip of that month (numbered `<number>-C<n>` once Finance approves it, by someone other than its author), carried into the next open payroll as back pay (`BACK_PAY` / `BACK_PAY_LESS`), or a reversal that takes back the whole payslip, loan repayments included. A negative net becomes a salary advance. Payments, declarations, year summaries and reports count approved complementary payslips; declarations already filed warn that an amended one is needed.
- **Back pay**: the Corrections page lists closed payslips of the last twelve months whose basic salary on file is now different (a raise dated in the past) and pays them all in the next payroll in one go.
- **Accounting entries** (`accounting.export`): the month's payroll as a SYSCOHADA journal (Excel or CSV), optionally split by cost centre. Default accounts: 661x pay, 662x for expatriates, 664x employer social charges, 6413 taxes on pay (INPP, ONEM), 4220 net pay owed, 421x loans and advances, 431x CNSS, 447x taxes; each pay element can have its own accounts.
- **Parallel run**: Payroll > Compare takes the old payroll's figures for a month as CSV (registration number, gross, CNSS, tax, net) and shows, person by person, what is the same and what differs. Run both payrolls for two months before relying on HR Pay.
- **Sign-off**: the Rates page downloads a workbook of every rate, tax band, pay element, setting and formula for the company's country, for the payroll expert to check and sign; the sign-off is recorded, and the page shows how many rates changed since.

To check with the payroll expert (Albert Mbodo) before going live: the settlement rules and day counts for both countries, the severance rates (law or collective agreement), the default SYSCOHADA accounts, and every rate in the sign-off workbook. Still waiting for outside input: the real bank, mobile money, CNSS and DGI file formats, and the official IPR barème.

## Import and integrations (phase 15)

- **Import from Excel** (`data.import`, Settings > Import data): employees, contracts, salaries, leave balances and year-to-date totals. Download the template (Excel or CSV), fill one row per person, then check it: each row shows what would happen (new, update, add) or what is wrong. The import saves only a file with no error, all at once; a file with one bad row saves nothing. Headers can be in French or English, dates as `dd/mm/yyyy`, `yyyy-mm-dd` or Excel dates, amounts as `1 250,50` or `1250.50`. Employees are matched by registration number; an empty cell keeps what is saved. Year-to-date totals are the months before HR Pay (for the yearly tax and CNSS summaries) and are refused for months that already have closed payslips.
- **Public API** (`api.manage`, Settings > API and webhooks): REST under `/api/v1`, described in `/api/v1/openapi.json` (employees, units, attendance days and events, sending punches, leave, payroll runs and payslips, adding pay entries, accounting entries). Access keys look like `hrp_<prefix>_<secret>` and go in `Authorization: Bearer <key>` (or `X-API-Key`). The secret is shown once and only its SHA-256 is kept. A key holds only the permissions chosen for it, never more than its maker has; it can expire and be revoked. 600 requests a minute per key.
- **Webhooks**: HR Pay POSTs JSON to an `https` address (no private address) when a chosen event happens: `employee.created`, `employee.updated`, `contract.started`, `salary.changed`, `departure.confirmed`, `attendance.checked_in`, `attendance.checked_out`, `leave.requested`, `leave.approved`, `leave.rejected`, `payroll.calculated`, `payroll.approved`, `payroll.closed`, `payment.paid`. The body names the event and the record, never amounts or personal numbers; the receiver reads details through the API. Headers: `X-HRPay-Event`, `X-HRPay-Delivery`, `X-HRPay-Timestamp`, `X-HRPay-Signature: sha256=<hex HMAC-SHA256 of "<timestamp>.<body>" with the signing secret>`. Failed calls are tried again after 1 min, 5 min, 30 min, 2 h, 6 h, 12 h and 24 h. `APP_WEBHOOKS_ALLOW_PRIVATE=true` allows private addresses (tests, on-premise).
- **Time clocks** (ZKTeco and other clocks with the ADMS "push" protocol; Settings > Time clocks): register the clock by its serial number and site, then on the clock set Comm. > Cloud server to the HR Pay address, port 443 with HTTPS, or port 80 for clocks without HTTPS (Caddy serves `/iclock` on both). Limit such clocks to the office's addresses with *allowed addresses*. Each punch becomes a check-in or check-out (alternating, or from the in/out key if chosen) of the person whose registration number, or clock number, it carries; the same punch sent twice counts once, and months whose payroll is approved are left alone. Punches under an unknown number wait in the list until someone links the number to a person.
- **File layouts** (Settings > File layouts): the accounting entries and the bank, mobile money and cash files laid out for the customer's software: columns in order (a field or fixed text), separator, decimal comma, date format, header line, UTF-8 or Windows-1252, `.csv` or `.txt`. The Accounting page and each payment file then offer a download per layout. The starting points are generic: check every layout with the customer's accounting software or bank, with one test file, before using it.

## Hiring, departures and the phone app (phase 16)

- **Checklists** (`lifecycle.manage`: admins and HR; People > Hiring and departures): each company keeps its hiring and departure steps. A hiring checklist is started from the person's page; a departure checklist starts by itself when a departure is confirmed and is cancelled if the departure is taken back. Steps tied to something HR Pay knows tick themselves: a document of a given type filed, equipment lent (hiring) or all back (departure), a contract signed, the final settlement confirmed, the account closed.
- **Document vault**: PDF, JPEG or PNG up to 10 MB per file, checked by content, kept with its SHA-256. Types say whether the document expires (HR and the person are reminded once, the chosen number of days before), whether the employee sees it, and whether they can send one from the app. Removing a document hides it and keeps it in the audit trail.
- **Account closing**: an account is turned off by itself once the payroll of the month of the last day is closed, or 45 days after the last day, whichever comes first.
- **Contracts and letters** from templates with `{{fields}}` (name, contract, grade, salary, site, company, today...). HR prepares a draft, reads the PDF, then sends it: it is numbered (`LT-YYYY-NNNN`) and cannot change. A letter that needs no signature is filed at once. Otherwise the employee reads it in *My documents*, asks for a one-time code (email and WhatsApp, as for check-ins), types their name and the code. HR Pay then adds a signature page with the name typed, the date and time, the channels the code went to, the IP address and device, and the SHA-256 of the document read, and files the signed PDF. The employee can refuse with a reason. The starting templates are drafts for a lawyer to check, and the legal weight of this kind of electronic signature should be confirmed with a lawyer in each country.
- **Certificates**: the employee asks for a work certificate or a salary certificate in *My documents*; HR issues (numbered `AT-` / `AS-`, filed, the person told) or refuses with a reason. HR can also issue one directly. A work certificate is refused once the person has left: the certificate of employment comes from the departure.
- **Notifications**: the bell in the top bar (checked every minute) shows leave to approve and its answer, corrections to approve, payslips ready, documents to sign or expiring, certificates, and a reminder to check out 30 minutes after the end of the planned day.
- **Phone app** (installable web app): *Install* from the browser menu, or from My account. The app keeps its own files to open without a connection but never keeps answers from `/api`. Phone notifications need a key pair the operator makes once (`npx web-push generate-vapid-keys`) and sets as `APP_PUSH_PUBLIC_KEY`, `APP_PUSH_PRIVATE_KEY` and `APP_PUSH_SUBJECT` (a `mailto:` address); never commit the private key. The push carries no content: the phone fetches the notification from HR Pay with the person's session. On iPhone, notifications work only from the installed app (iOS 16.4 or later).
- **Check-ins without a connection**: each site chooses *not allowed*, *allowed, approved by the manager* (the default) or *recorded when the location is in the site's area*. The phone keeps the time and location and sends them when the connection comes back, at most 3 days later and with the phone's clock no more than 5 minutes ahead; the same check-in sent twice counts once. Anything not recorded becomes a correction request for the manager.
- Reloading any page of the built app now works (it used to give a 404 for pages added after phase 9).

## Go-to-market and support (phase 17)

- **Set-up guide** (`/setup`, company administrators): ten steps from an empty company to the first payroll. Most are read from the data (company details, sites, structure, holidays, employees, first payroll); the reviews (schedules, grades, legal rates, pay elements) are marked done by hand. The first steps show on the home page until hidden.
- **Help** (`/help`): short articles in French and English, searchable, and a "?" in the top bar that opens the article for the page you are on. The short videos are still to record.
- **Support requests**: *Report a problem* in the user menu sends the subject, the page, the app version and the browser. The person follows the answer under Help; company administrators see all the company's requests. The provider answers from *Support desk* (`/platform/support`); the person gets a notification and an email.
- **Terms**: terms of service, data processing agreement, privacy policy and service levels, in French and English, public at `/legal/terms`, `/legal/dpa`, `/legal/privacy-policy`, `/legal/sla`. A company administrator accepts the terms and the DPA at sign-in, and again after each new version the provider publishes (*Terms and policies*). The starting texts in `backend/src/main/resources/legal/` are **drafts for a lawyer**, with `[brackets]` to fill in.
- **Version and status**: the version (`0.17.0`) is in `/api/version`, *What's new* lists the changes of each version, and `/status` is a public page with the checks and the incidents the provider posts (*Service status*).
- **Dashboards** (`/dashboards`, `dashboard.read`: admins, HR, finance): headcount at month end, hires and departures, turnover, unpaid absence and payroll cost by direction (gross plus employer charges, in US dollars at each payroll's rate), 6 to 24 months, with a table view.
- **Trials**: the public form at `/trial` records a request (one per email while it waits, five an hour from one IP address). Nothing is created until the provider approves it in *Trial requests* with a plan and modules; the company then starts its trial and its administrator gets a link, valid 7 days, to choose a password.
- **Product website** in `website/` (static, French and English) and a **demo server** mode (`DEMO_MODE`, see `deploy/README.md`).

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | local `hrpay` | Database |
| `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD` | empty | Creates the first administrator when there are no users. They must change the password at first sign-in. |
| `SAMPLE_DATA` | `false` | Loads the demo site and users. Never in production. |
| `LICENSING_MODE` | `saas` | `saas`: the provider sets each company's plan and modules on the Companies page. `on-premise`: a signed licence file sets the modules and limits; without one the installation is a 30-day evaluation copy. |
| `PROVIDER_COMPANY` | `1` | The provider's own company: its administrators manage the other companies, install the licence and get the system alerts. |
| `EXCHANGE_RATES_URL` | BCC rate API | Source of the daily USD/CDF rate |
| `EXCHANGE_RATES_FETCH` | `true` | Set to `false` to enter rates by hand only |
| `COOKIE_SECURE` | `false` | Set to `true` behind HTTPS |
| `FORWARD_HEADERS` | `none` | Set to `native` behind a reverse proxy so the client IP comes from `X-Forwarded-For` |
| `CORS_ORIGINS` | `http://localhost:5173` | Only needed if the app is served from another origin |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | empty | Turns on "Sign in with Google". Only pre-registered, active, verified emails are accepted. (`SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID` still works.) |
| `MICROSOFT_CLIENT_ID` / `MICROSOFT_CLIENT_SECRET` | empty | Turns on "Sign in with Microsoft" (work accounts, from each company's own tenant). |
| `HRPAY_DATA_KEY` or `HRPAY_DATA_KEY_FILE` | empty | Key encrypting bank accounts, ID numbers and authenticator secrets. Keep a copy elsewhere. |
| `APP_PUSH_PUBLIC_KEY`, `APP_PUSH_PRIVATE_KEY`, `APP_PUSH_SUBJECT` | empty | Web Push (VAPID) keys for phone notifications; see phase 16. Without them notifications show in the app only. |
| `DEMO_MODE` | `false` | Public demo server only: erases the database at every start and restarts each night. Needs `SAMPLE_DATA=true`. Never in production. |
| `TWO_STEP_ENFORCED` | `true` | Set to `false` only to let roles marked "two-step required" sign in without it (for example on a test copy). |

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
