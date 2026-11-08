-- Quinzaine: an optional advance on the month's pay (acompte de quinzaine), paid around the 15th and taken back in
-- full on the month's payslip.
--   DRC, Code du travail (Loi 015/2002, art. 99, 112 and 114 not changed by Loi 16/010): art. 99 pay at regular
--   intervals of at most one month, within six days after the period; art. 112 c) deductions for advances are allowed;
--   art. 103 a written statement of each payment.
--   Republic of the Congo, Code du travail (Loi 45-75 as amended): art. 88 at most 15 days for workers hired by the day
--   or week, one month for those hired by the fortnight or month, within 8 days after the month; art. 89 a company of
--   more than 50 workers may pay day or hour workers monthly only if it pays each fortnight an acompte of at least a
--   third of the previous month's pay; art. 90 each payment recorded and signed.
-- Off by default. The rate is set per grade category (10% to 50%); an employee may ask not to receive it.
-- To verify with the payroll rules certifier.

set local app.bypass = 'on';

insert into payroll_setting (company_id, key, value)
select c.id, s.key, s.value
from company c cross join (values
    ('QUINZAINE_ENABLED', 'false'),
    ('QUINZAINE_BASE', 'NET'),          -- NET: of a normal month's net pay; BASIC: of the basic salary
    ('QUINZAINE_DAY', '15'),            -- day of the month it is paid (the working day before when it is not one)
    ('QUINZAINE_MAX_DEBT_MONTHS', '3')  -- no quinzaine when loans and advances owed exceed this many months of net pay
) as s(key, value)
on conflict do nothing;

-- The rate of each grade category, from 10% to 50%; none: the category does not get the quinzaine.
alter table grade_category add column quinzaine_percent numeric(5, 2) check (quinzaine_percent between 10 and 50);

insert into pay_element (company_id, code, name, kind, method, subject_to_cnss, taxable_ipr, prorated, default_for_all,
                         system, display_order)
select c.id, 'QUINZAINE', case when c.language = 'fr' then 'Acompte de quinzaine' else 'Mid-month pay (quinzaine) paid in advance' end,
       'DEDUCTION', 'COMPUTED', false, false, false, false, true, 143
from company c
where exists (select 1 from pay_element p where p.company_id = c.id and p.code = 'BASIC')
  and not exists (select 1 from pay_element p where p.company_id = c.id and p.code = 'QUINZAINE');

-- An employee's request not to receive the quinzaine: from the next one not yet approved, until withdrawn.
create table quinzaine_refusal (
    id          bigserial primary key,
    user_id     bigint not null references app_user (id),
    reason      varchar(300),
    recorded_by varchar(320) not null,
    recorded_at timestamptz not null default now()
);
select hrpay_company_table('quinzaine_refusal');
create unique index quinzaine_refusal_user on quinzaine_refusal (company_id, user_id);

-- One quinzaine per month: CALCULATED (HR) -> APPROVED (Finance; on the payslips) -> CLOSED (HR; receipts numbered,
-- payment files) -> PAID (every line in a paid file), or CANCELLED before it is closed.
create table quinzaine_run (
    id            bigserial primary key,
    month         date not null check (extract(day from month) = 1),
    pay_date      date not null,
    base          varchar(5) not null check (base in ('NET', 'BASIC')),
    status        varchar(10) not null default 'CALCULATED' check (status in ('CALCULATED', 'APPROVED', 'CLOSED', 'PAID', 'CANCELLED')),
    skipped       jsonb not null default '[]',
    calculated_by varchar(320) not null,
    calculated_at timestamptz not null default now(),
    approved_by   varchar(320),
    approved_at   timestamptz,
    closed_by     varchar(320),
    closed_at     timestamptz,
    cancelled_by  varchar(320),
    cancelled_at  timestamptz,
    cancel_note   varchar(300)
);
select hrpay_company_table('quinzaine_run');
create unique index quinzaine_run_one_open on quinzaine_run (company_id, month) where status <> 'CANCELLED';

create table quinzaine_line (
    id           bigserial primary key,
    run_id       bigint not null references quinzaine_run (id) on delete cascade,
    user_id      bigint not null references app_user (id),
    number       varchar(30) unique,          -- the receipt, given when the quinzaine is closed
    currency     char(3) not null check (currency in ('USD', 'CDF', 'XAF')),
    category     varchar(32) not null,
    percent      numeric(5, 2) not null,
    base_amount  numeric(14, 2) not null,     -- normal month's net, or basic salary
    normal_net   numeric(14, 2) not null,
    worked_share numeric(6, 4) not null,      -- paid time from the 1st to the pay day over scheduled time
    earned_net   numeric(14, 2) not null,     -- net already earned on the pay day: the most it can be
    amount       numeric(14, 2) not null check (amount > 0),
    unit_id      bigint references org_unit (id),
    explanation  jsonb not null default '[]',
    unique (run_id, user_id)
);
select hrpay_company_table('quinzaine_line');

-- HR leaves one person out of one month's quinzaine, with a reason.
create table quinzaine_exclusion (
    id          bigserial primary key,
    month       date not null check (extract(day from month) = 1),
    user_id     bigint not null references app_user (id),
    reason      varchar(300) not null,
    recorded_by varchar(320) not null,
    recorded_at timestamptz not null default now()
);
select hrpay_company_table('quinzaine_exclusion');
create unique index quinzaine_exclusion_one on quinzaine_exclusion (company_id, month, user_id);

-- Payment files serve the quinzaine too: a file is for a payroll run or for a quinzaine, a line for a payslip or a
-- quinzaine line.
alter table payment_batch alter column run_id drop not null;
alter table payment_batch add column quinzaine_run_id bigint references quinzaine_run (id);
alter table payment_batch add constraint payment_batch_source check ((run_id is null) <> (quinzaine_run_id is null));
alter table payment_line alter column payslip_id drop not null;
alter table payment_line add column quinzaine_line_id bigint unique references quinzaine_line (id);
alter table payment_line add constraint payment_line_source check ((payslip_id is null) <> (quinzaine_line_id is null));
