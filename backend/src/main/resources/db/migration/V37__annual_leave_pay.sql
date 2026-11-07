-- Annual leave pay: whether the company pays annual leave, how unused days carry over, and the leave allowance
-- (allocation de congé, the "pécule") paid before the person leaves.
--   DRC, Code du travail (Loi 015/2002): art. 140 only half of the leave may be carried, over two years; art. 142 the
--   allowance is the pay at departure plus the 12-month average of commissions, bonuses and overtime; art. 145 it is
--   paid at the latest on the last working day before the leave.
--   Republic of the Congo (Loi 45-75, as cited by secondary sources): art. 120 the right lapses after 3 years;
--   art. 122 the allowance is at least the average pay of the 12 months before the leave, paid on departure.
-- To verify with the payroll rules certifier.

set local app.bypass = 'on';

-- Company settings, by country.
insert into payroll_setting (company_id, key, value)
select c.id, s.key, case when c.country_code = 'CG' then s.cg else s.cd end
from company c cross join (values
    ('LEAVE_ANNUAL_PAID', 'true', 'true'),
    ('LEAVE_CARRY_PERCENT', '50', '100'),   -- share of a year's leave that may still be taken after the year
    ('LEAVE_CARRY_YEARS', '1', '3')         -- years after the leave year in which it may still be taken
) as s(key, cd, cg)
on conflict do nothing;

-- Days that lapse when a leave year is closed.
alter table leave_ledger drop constraint leave_ledger_kind_check;
alter table leave_ledger add constraint leave_ledger_kind_check
    check (kind in ('GRANT', 'TAKEN', 'RETURNED', 'ADJUSTMENT', 'PAYOUT', 'EXPIRED'));

-- Pay elements: the variable part of the allowance (an earning) and the allowance paid in advance (taken back on the
-- payslip of the leave month, which already pays the leave days).
insert into pay_element (company_id, code, name, kind, method, subject_to_cnss, taxable_ipr, prorated, default_for_all,
                         system, display_order)
select c.id, e.code, case when c.language = 'fr' then e.fr else e.en end, e.kind, 'COMPUTED', e.cnss, e.cnss, false, false,
       true, e.ord
from company c cross join (values
    ('LEAVE_ALLOWANCE', 'Leave allowance (average of bonuses and overtime)', 'Allocation de congé (moyenne des primes et heures supplémentaires)', 'EARNING', true, 81),
    ('LEAVE_ADVANCE', 'Leave allowance paid in advance', 'Allocation de congé versée d''avance', 'DEDUCTION', false, 142)
) as e(code, en, fr, kind, cnss, ord)
where exists (select 1 from pay_element p where p.company_id = c.id and p.code = 'BASIC')
  and not exists (select 1 from pay_element p where p.company_id = c.id and p.code = e.code);

-- One leave allowance per approved annual leave. CALCULATED (HR) -> APPROVED (Finance) -> PAID, or CANCELLED.
create table leave_allowance (
    id               bigserial primary key,
    leave_request_id bigint not null references leave_request (id),
    user_id          bigint not null references app_user (id),
    currency         char(3) not null check (currency in ('USD', 'CDF', 'XAF')),
    days             numeric(6, 2) not null check (days > 0),
    monthly_salary   numeric(14, 2) not null,
    days_per_month   numeric(5, 2) not null,
    daily_rate       numeric(14, 4) not null,
    salary_part      numeric(14, 2) not null,
    variable_12m     numeric(14, 2) not null,   -- commissions, bonuses and overtime of the 12 months before
    months_counted   int not null,
    variable_part    numeric(14, 2) not null,
    gross            numeric(14, 2) not null,
    net_share        numeric(6, 4) not null,    -- net / gross of the last payslip, to pay the allowance net
    advance          numeric(14, 2) not null check (advance >= 0),
    pay_by           date not null,             -- the last working day before the leave
    status           varchar(10) not null default 'CALCULATED' check (status in ('CALCULATED', 'APPROVED', 'PAID', 'CANCELLED')),
    explanation      jsonb not null default '[]',
    calculated_by    varchar(320) not null,
    calculated_at    timestamptz not null default now(),
    approved_by      varchar(320),
    approved_at      timestamptz,
    paid_by          varchar(320),
    paid_on          date,
    payment_reference varchar(100),
    cancelled_by     varchar(320),
    cancelled_at     timestamptz,
    cancel_note      varchar(300)
);
select hrpay_company_table('leave_allowance');
create unique index leave_allowance_one_open on leave_allowance (company_id, leave_request_id) where status <> 'CANCELLED';

-- How the allowance falls into the payslips of the months the leave covers.
create table leave_allowance_month (
    id              bigserial primary key,
    allowance_id    bigint not null references leave_allowance (id) on delete cascade,
    month           date not null check (extract(day from month) = 1),
    days            numeric(6, 2) not null,
    variable_amount numeric(14, 2) not null,
    advance_amount  numeric(14, 2) not null,
    unique (allowance_id, month)
);
select hrpay_company_table('leave_allowance_month');
