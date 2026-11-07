-- Pay elements: every line that can appear on a payslip, and how the engine treats it.
-- COMPUTED elements are calculated by the payroll engine (basic, overtime, CNSS, IPR, loans, employer charges);
-- the others take an amount from the element, an employee's standing assignment or a one-off entry.

create table pay_element (
    id               bigserial primary key,
    code             varchar(32) not null unique,
    name             varchar(150) not null,
    kind             varchar(16) not null check (kind in ('EARNING', 'DEDUCTION', 'EMPLOYER_CHARGE')),
    method           varchar(16) not null
                     check (method in ('COMPUTED', 'FIXED', 'PERCENT_OF_BASIC', 'PER_DAY_WORKED', 'PER_CHILD', 'MANUAL')),
    default_amount   numeric(14, 2) check (default_amount >= 0),
    default_currency char(3) check (default_currency in ('USD', 'CDF')),
    percent          numeric(6, 3) check (percent >= 0 and percent <= 100),
    subject_to_cnss  boolean not null default false,
    taxable_ipr      boolean not null default false,
    -- Share that is free of IPR, as a % of basic (e.g. housing within legal limits); null = no cap.
    ipr_exempt_cap_percent numeric(6, 3) check (ipr_exempt_cap_percent >= 0 and ipr_exempt_cap_percent <= 100),
    prorated         boolean not null default false,
    default_for_all  boolean not null default false,
    system           boolean not null default false,
    display_order    int not null default 100,
    active           boolean not null default true,
    check (method <> 'PERCENT_OF_BASIC' or percent is not null),
    check (method not in ('FIXED', 'PER_DAY_WORKED', 'PER_CHILD') or default_currency is not null)
);

-- Sample values only: a DRC payroll specialist must confirm treatment and amounts before go-live.
insert into pay_element (code, name, kind, method, default_amount, default_currency, percent, subject_to_cnss,
                         taxable_ipr, ipr_exempt_cap_percent, prorated, default_for_all, system, display_order) values
    ('BASIC',            'Basic salary',                  'EARNING',         'COMPUTED',         null, null, null, true,  true,  null, true,  false, true,  10),
    ('OVERTIME',         'Overtime',                      'EARNING',         'COMPUTED',         null, null, null, true,  true,  null, false, false, true,  20),
    ('HOUSING',          'Housing allowance (sample)',    'EARNING',         'PERCENT_OF_BASIC', null, null, 30,   false, false, null, true,  true,  false, 30),
    ('TRANSPORT',        'Transport allowance (sample)',  'EARNING',         'PER_DAY_WORKED',   2,   'USD', null, false, false, null, false, true,  false, 40),
    ('FAMILY_ALLOWANCE', 'Family allowances (sample)',    'EARNING',         'PER_CHILD',        10,  'USD', null, false, false, null, false, true,  false, 50),
    ('PERF_BONUS',       'Performance bonus',             'EARNING',         'MANUAL',           null, null, null, true,  true,  null, false, false, false, 60),
    ('OTHER_BONUS',      'Other bonus',                   'EARNING',         'MANUAL',           null, null, null, true,  true,  null, false, false, false, 70),
    ('LEAVE_PAYOUT',     'Unused leave paid out',         'EARNING',         'COMPUTED',         null, null, null, true,  true,  null, false, false, true,  80),
    ('CNSS_EMPLOYEE',    'CNSS (employee share)',         'DEDUCTION',       'COMPUTED',         null, null, null, false, false, null, false, false, true,  110),
    ('IPR',              'IPR (income tax)',              'DEDUCTION',       'COMPUTED',         null, null, null, false, false, null, false, false, true,  120),
    ('LOAN',             'Loan repayment',                'DEDUCTION',       'COMPUTED',         null, null, null, false, false, null, false, false, true,  130),
    ('ADVANCE',          'Salary advance',                'DEDUCTION',       'COMPUTED',         null, null, null, false, false, null, false, false, true,  140),
    ('OTHER_DEDUCTION',  'Other deduction',               'DEDUCTION',       'MANUAL',           null, null, null, false, false, null, false, false, false, 150),
    ('CNSS_EMPLOYER',    'CNSS (employer share)',         'EMPLOYER_CHARGE', 'COMPUTED',         null, null, null, false, false, null, false, false, true,  210),
    ('INPP',             'INPP (training)',               'EMPLOYER_CHARGE', 'COMPUTED',         null, null, null, false, false, null, false, false, true,  220),
    ('ONEM',             'ONEM (employment office)',      'EMPLOYER_CHARGE', 'COMPUTED',         null, null, null, false, false, null, false, false, true,  230),
    ('IERE',             'IERE (expatriate pay tax)',     'EMPLOYER_CHARGE', 'COMPUTED',         null, null, null, false, false, null, false, false, true,  240);

-- Standing items for one employee: an amount different from the element's default, or an exclusion.
create table employee_pay_element (
    id           bigserial primary key,
    user_id      bigint not null references app_user (id),
    element_id   bigint not null references pay_element (id),
    amount       numeric(14, 2) check (amount >= 0),
    currency     char(3) check (currency in ('USD', 'CDF')),
    percent      numeric(6, 3) check (percent >= 0 and percent <= 100),
    excluded     boolean not null default false,
    start_month  date not null check (extract(day from start_month) = 1),
    end_month    date check (end_month is null or (extract(day from end_month) = 1 and end_month >= start_month)),
    note         varchar(300),
    created_by   varchar(320) not null,
    created_at   timestamptz not null default now(),
    check (amount is null or currency is not null)
);
create index employee_pay_element_user on employee_pay_element (user_id);

-- One-off lines for one month: this month's bonus, a deduction. One per employee, element and month.
create table pay_entry (
    id          bigserial primary key,
    user_id     bigint not null references app_user (id),
    element_id  bigint not null references pay_element (id),
    month       date not null check (extract(day from month) = 1),
    amount      numeric(14, 2) not null check (amount > 0),
    currency    char(3) not null check (currency in ('USD', 'CDF')),
    note        varchar(300),
    created_by  varchar(320) not null,
    created_at  timestamptz not null default now(),
    unique (user_id, element_id, month)
);
