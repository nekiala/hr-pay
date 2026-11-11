-- General salary increase (revision of the grade scale): HR prepares a scenario (a percentage of the grade bases from a
-- month, for some grade categories or grades), sees what it costs, and a second person approves it. Approval raises
-- the grade bases and each person's basic on those grades by the same amount (DRC Code du travail art. 86: equal pay
-- for equal work), and pays the months already closed as back pay ("Rappel de salaire") on the next payroll.
-- Art. 88: pay is set freely by contract or collective agreement, never below the minimums of art. 87.

insert into permission (code, category, description) values
    ('payroll.increase', 'payroll', 'Prepare and simulate a general salary increase'),
    ('payroll.increase.approve', 'payroll', 'Approve, reject or undo a general salary increase');
insert into role_permission (company_id, role_id, permission_code)
select r.company_id, r.id, m.permission_code from role r
join (values ('HR', 'payroll.increase'), ('FINANCE', 'payroll.increase.approve'), ('ADMIN', 'payroll.increase.approve'))
    as m(role_code, permission_code) on m.role_code = r.code
on conflict do nothing;

create table salary_increase (
    id               bigserial primary key,
    name             varchar(120) not null,
    percent          numeric(6, 3) not null check (percent > 0 and percent <= 100),
    minimum_amount   numeric(14, 2) check (minimum_amount > 0),
    minimum_currency char(3) check (minimum_currency in ('USD', 'CDF', 'XAF')),
    rounding         numeric(10, 2) check (rounding > 0),
    start_month      date not null check (extract(day from start_month) = 1),
    category_ids     bigint[] not null default '{}',
    grade_ids        bigint[] not null default '{}',
    status           varchar(10) not null default 'DRAFT'
                     check (status in ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'UNDONE')),
    summary          jsonb,
    arrears_month    date,
    created_by       varchar(320) not null,
    created_at       timestamptz not null default now(),
    submitted_by     varchar(320),
    submitted_at     timestamptz,
    decided_by       varchar(320),
    decided_at       timestamptz,
    decision_note    varchar(300),
    check ((minimum_amount is null) = (minimum_currency is null))
);
select hrpay_company_table('salary_increase');

-- What an approval changed, so that it can be undone before a later payroll is approved.
create table salary_increase_change (
    id          bigserial primary key,
    increase_id bigint not null references salary_increase (id) on delete cascade,
    kind        varchar(14) not null check (kind in ('GRADE', 'SALARY_NEW', 'SALARY_RAISED', 'ARREARS')),
    user_id     bigint references app_user (id),
    ref_id      bigint not null,                         -- grade, employee_salary or pay_entry
    old_amount  numeric(14, 2),
    new_amount  numeric(14, 2) not null,
    currency    char(3) not null,
    applied     boolean not null default true            -- a grade base waits for the start month
);
create index salary_increase_change_increase on salary_increase_change (increase_id);
select hrpay_company_table('salary_increase_change');

-- Back pay of the months already paid, on the next payslip: subject to CNSS and income tax like the salary it completes.
insert into pay_element (company_id, code, name, kind, method, subject_to_cnss, taxable_ipr, prorated, default_for_all,
                         system, display_order)
select c.id, 'SALARY_ARREARS', case when c.language = 'fr' then 'Rappel de salaire' else 'Salary arrears (back pay)' end,
       'EARNING', 'MANUAL', true, true, false, false, true, 15
from company c
where exists (select 1 from pay_element p where p.company_id = c.id and p.code = 'BASIC')
  and not exists (select 1 from pay_element p where p.company_id = c.id and p.code = 'SALARY_ARREARS');
