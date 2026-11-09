-- Salary advance on demand: an employee takes part of the net pay already earned this month, before pay day, and the
-- month's payslip takes it back in full (before loans), like the quinzaine.
--   DRC, Code du travail (Loi 015/2002, not changed on these points by Loi 16/010): art. 112 c) allows deductions "à titre
--   d'avances"; no article allows interest or fees on them, so the advance is free for the employee; art. 114 caps what
--   may be taken from pay for debts at 1/5 of the part up to five times the monthly SMIG of the category and 1/3 above,
--   after tax and social deductions; art. 100 the final pay within two working days after the last day; art. 103 a
--   written statement of each payment.
--   Republic of the Congo: same design, to confirm with the payroll rules certifier (Loi 45-75 text not found online).
-- Off by default. Paid by the employer (no third-party lender). The share is set per grade category (10% to 50%).

set local app.bypass = 'on';

insert into payroll_setting (company_id, key, value)
select c.id, s.key, s.value
from company c cross join (values
    ('ADVANCE_ENABLED', 'false'),
    ('ADVANCE_APPROVAL', 'AUTO'),          -- AUTO: within the rules it is approved at once; HR: HR approves each request
    ('ADVANCE_MAX_PER_MONTH', '2'),        -- requests a person may make in one month
    ('ADVANCE_CLOSING_DAYS', '5'),         -- no request in the last days of the month, while the payroll is worked out
    ('ADVANCE_MIN_SERVICE_DAYS', '30'),    -- days under contract before a first request (stands in for the trial period)
    ('ADVANCE_MAX_DEBT_MONTHS', '3')       -- no advance when loans and advances owed exceed this many months of net pay
) as s(key, value)
on conflict do nothing;

-- The share of the net already earned a person of the category may take, from 10% to 50%; none: not for the category.
alter table grade_category add column advance_percent numeric(5, 2) default 50 check (advance_percent between 10 and 50);
update grade_category set advance_percent = 50;

insert into pay_element (company_id, code, name, kind, method, subject_to_cnss, taxable_ipr, prorated, default_for_all,
                         system, display_order)
select c.id, 'EARNED_ADVANCE', case when c.language = 'fr' then 'Avance sur salaire à la demande' else 'Salary advance on demand' end,
       'DEDUCTION', 'COMPUTED', false, false, false, false, true, 144
from company c
where exists (select 1 from pay_element p where p.company_id = c.id and p.code = 'BASIC')
  and not exists (select 1 from pay_element p where p.company_id = c.id and p.code = 'EARNED_ADVANCE');

-- Each month's advances are paid through payment files, like a pay run: one row per company and month.
create table advance_run (
    id          bigserial primary key,
    month       date not null
);
select hrpay_company_table('advance_run');
create unique index advance_run_month on advance_run (company_id, month);

-- REQUESTED (waits for HR) -> APPROVED (numbered, on the month's payslip, to pay) -> PAID; or REJECTED / CANCELLED.
create table salary_advance (
    id              bigserial primary key,
    run_id          bigint not null references advance_run (id),
    user_id         bigint not null references app_user (id),
    month           date not null,
    number          varchar(30),
    amount          numeric(14, 2) not null check (amount > 0),
    currency        char(3) not null,
    available       numeric(14, 2) not null,    -- what the person could take when asking
    earned_net      numeric(14, 2) not null,
    percent         numeric(5, 2) not null,
    explanation     jsonb not null default '[]',
    status          varchar(10) not null default 'REQUESTED'
                    check (status in ('REQUESTED', 'APPROVED', 'PAID', 'REJECTED', 'CANCELLED')),
    signed_name     varchar(200) not null,
    signed_at       timestamptz not null,
    signed_ip       varchar(64),
    signed_channels varchar(40),
    sha256          varchar(64) not null,        -- fingerprint of the receipt text the person confirmed
    decided_by      varchar(320),
    decided_at      timestamptz,
    decision_note   varchar(500),
    unit_id         bigint,
    created_at      timestamptz not null default now()
);
select hrpay_company_table('salary_advance');
create unique index salary_advance_number on salary_advance (company_id, number) where number is not null;
create index salary_advance_user_month on salary_advance (user_id, month);

-- Payment files serve advances too: a file is for a payroll run, a quinzaine or a month's advances.
alter table payment_batch add column advance_run_id bigint references advance_run (id);
alter table payment_batch drop constraint payment_batch_source;
alter table payment_batch add constraint payment_batch_source
    check (num_nonnulls(run_id, quinzaine_run_id, advance_run_id) = 1);
alter table payment_line add column advance_id bigint unique references salary_advance (id);
alter table payment_line drop constraint payment_line_source;
alter table payment_line add constraint payment_line_source
    check (num_nonnulls(payslip_id, quinzaine_line_id, advance_id) = 1);

-- HR (and the administrator) decide on requests and set the rules; employees ask with "my" endpoints.
insert into permission (code, category, description) values
    ('advance.manage', 'payroll', 'Decide on salary advance requests and set the rules of salary advances');
insert into role_permission (company_id, role_id, permission_code)
select r.company_id, r.id, 'advance.manage' from role r where r.code in ('ADMIN', 'HR');
