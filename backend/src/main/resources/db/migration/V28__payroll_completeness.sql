-- Phase 14: final settlement on departure, corrections of a closed month, back pay, accounting entries, the
-- sign-off of rates and formulas.

-- A payslip is the month's regular one, or a complementary one that corrects a closed month (the difference, or the
-- whole payslip reversed). A complementary payslip waits for Finance's approval before it gets its number.
alter table payslip add column kind varchar(14) not null default 'REGULAR' check (kind in ('REGULAR', 'COMPLEMENTARY'));
alter table payslip add column corrects_id bigint references payslip (id);
alter table payslip add column reason varchar(300);
alter table payslip add column created_by varchar(320);
alter table payslip add column approved_by varchar(320);
alter table payslip add column approved_at timestamptz;
alter table payslip drop constraint payslip_run_id_user_id_key;
create unique index payslip_one_regular on payslip (run_id, user_id) where kind = 'REGULAR';
create index payslip_corrects on payslip (corrects_id);

-- Each correction of a closed payslip: a complementary payslip, or the difference carried into a later payroll.
-- basic_monthly is the basic salary the correction worked with, so back pay is not asked twice.
create table payroll_correction (
    id               bigserial primary key,
    payslip_id       bigint not null references payslip (id),
    user_id          bigint not null references app_user (id),
    mode             varchar(14) not null check (mode in ('COMPLEMENTARY', 'NEXT_PAYROLL', 'REVERSAL')),
    gross_change     numeric(14, 2) not null,
    currency         varchar(3) not null,
    basic_monthly    numeric(14, 2),
    target_month     date check (extract(day from target_month) = 1),
    complementary_id bigint references payslip (id) on delete cascade,
    reason           varchar(300) not null,
    created_by       varchar(320) not null,
    created_at       timestamptz not null default now()
);
create index payroll_correction_payslip on payroll_correction (payslip_id);
select hrpay_company_table('payroll_correction');

-- A departure and its final settlement (décompte final). Confirming it writes the settlement's pay entries into the
-- month of the last day and ends the contract; the last payslip then carries them.
create table departure (
    id                bigserial primary key,
    user_id           bigint not null references app_user (id),
    last_day          date not null,
    reason            varchar(20) not null check (reason in ('RESIGNATION', 'DISMISSAL', 'GROSS_MISCONDUCT', 'REDUNDANCY',
                                                             'END_OF_CONTRACT', 'MUTUAL_AGREEMENT', 'RETIREMENT', 'DEATH')),
    notice            varchar(8) not null check (notice in ('WORKED', 'PAID', 'NONE')),
    currency          varchar(3) not null,
    basic_monthly     numeric(14, 2) not null,
    daily_rate        numeric(14, 4) not null,
    service_years     numeric(6, 2) not null,
    notice_days       numeric(6, 2) not null default 0,
    notice_amount     numeric(14, 2) not null default 0,
    leave_days        numeric(6, 2) not null default 0,
    leave_amount      numeric(14, 2) not null default 0,
    severance_amount  numeric(14, 2) not null default 0,
    thirteenth_amount numeric(14, 2) not null default 0,
    note              varchar(500),
    status            varchar(10) not null default 'DRAFT' check (status in ('DRAFT', 'CONFIRMED')),
    contract_end_before date,
    created_by        varchar(320) not null,
    created_at        timestamptz not null default now(),
    confirmed_by      varchar(320),
    confirmed_at      timestamptz,
    certificate_no    varchar(30)
);
create unique index departure_one_per_person on departure (user_id);
select hrpay_company_table('departure');

-- Who signed off the rates and formulas, and when. Rates changed after it are shown as such.
create table rate_certification (
    id            bigserial primary key,
    certified_by  varchar(150) not null,
    certified_on  date not null,
    note          varchar(500),
    created_by    varchar(320) not null,
    created_at    timestamptz not null default now()
);
select hrpay_company_table('rate_certification');

-- Accounting accounts of each pay element (SYSCOHADA). Empty: the app's default for the element.
alter table pay_element add column expense_account varchar(12);
alter table pay_element add column liability_account varchar(12);

-- Every company gets the new elements, rates and settings: this migration reads past the company separation.
set local app.bypass = 'on';

insert into pay_element (company_id, code, name, kind, method, subject_to_cnss, taxable_ipr, prorated, default_for_all,
                         system, display_order)
select c.id, e.code, case when c.language = 'fr' then e.fr else e.en end, 'EARNING', 'COMPUTED', e.cnss, e.tax,
       false, false, true, e.ord
from company c cross join (values
    ('NOTICE_PAY',    'Notice pay (not worked)',        'Indemnité compensatrice de préavis', true,  true,  82),
    ('SEVERANCE',     'Severance pay (to verify)',       'Indemnité de licenciement (à vérifier)', false, false, 84),
    ('BACK_PAY',      'Back pay, earlier month',         'Rappel de salaire, mois antérieur', true,  true,  17),
    ('BACK_PAY_LESS', 'Overpaid earlier month (less)',   'Trop-perçu d''un mois antérieur (moins)', true, true, 18)
) as e(code, en, fr, cnss, tax, ord)
where exists (select 1 from pay_element p where p.company_id = c.id and p.code = 'BASIC');

-- Final settlement rules. Samples from memory, to verify with Albert Mbodo.
insert into statutory_rate_type (company_id, code, name, unit, description, display_order)
select c.id, t.code, case when c.language = 'fr' then t.fr else t.en end, 'COUNT',
       case when c.language = 'fr' then t.dfr else t.den end, t.ord
from company c cross join (values
    ('SETTLEMENT_DAYS_PER_MONTH', 'Days per month for the daily rate', 'Jours par mois pour le taux journalier',
     'The final settlement prices a day as the monthly basic salary divided by this.',
     'Le décompte final calcule un jour comme le salaire de base mensuel divisé par ce nombre.', 300),
    ('NOTICE_BASE_DAYS', 'Notice, base days', 'Préavis, jours de base',
     'Days of notice for any length of service.', 'Jours de préavis quelle que soit l''ancienneté.', 310),
    ('NOTICE_DAYS_PER_YEAR', 'Notice, days per year of service', 'Préavis, jours par année d''ancienneté',
     'Days of notice added for each full year of service.', 'Jours de préavis ajoutés par année complète d''ancienneté.', 320),
    ('SEVERANCE_DAYS_PER_YEAR', 'Severance, days per year of service', 'Indemnité de licenciement, jours par année',
     'Days of pay per year of service on dismissal, redundancy or retirement, from the law or the collective agreement.',
     'Jours de salaire par année d''ancienneté en cas de licenciement, de licenciement économique ou de retraite, selon la loi ou la convention collective.', 330)
) as t(code, en, fr, den, dfr, ord)
where exists (select 1 from statutory_rate_type s where s.company_id = c.id and s.code = 'CNSS_EMPLOYEE');

insert into statutory_rate (company_id, code, effective_from, value, note)
select c.id, r.code, '2020-01-01', case when c.country_code = 'CG' then r.cg else r.cd end,
       case when c.country_code = 'CG' then 'Sample, to verify (Republic of the Congo)' else 'Sample, to verify' end
from company c cross join (values
    ('SETTLEMENT_DAYS_PER_MONTH', 26, 30),
    ('NOTICE_BASE_DAYS', 14, 30),
    ('NOTICE_DAYS_PER_YEAR', 7, 0),
    ('SEVERANCE_DAYS_PER_YEAR', 0, 7.5)
) as r(code, cd, cg)
where exists (select 1 from statutory_rate_type s where s.company_id = c.id and s.code = 'CNSS_EMPLOYEE');

-- The accounts the payroll entries use when an element names none: SYSCOHADA defaults, to verify.
insert into payroll_setting (company_id, key, value)
select c.id, s.key, s.value from company c cross join (values
    ('ACCOUNT_NET_PAY', '4220'),          -- Personnel, rémunérations dues
    ('ACCOUNTING_JOURNAL', 'PAIE')
) as s(key, value)
where exists (select 1 from payroll_setting p where p.company_id = c.id and p.key = 'RATE_DAY');

insert into permission (code, category, description) values
    ('accounting.export', 'payroll', 'Export the monthly payroll accounting entries');
insert into role_permission (company_id, role_id, permission_code)
select r.company_id, r.id, 'accounting.export' from role r where r.code in ('ADMIN', 'FINANCE');
