-- Loans and salary advances, repaid by payroll.
create table loan (
    id           bigserial primary key,
    user_id      bigint not null references app_user (id),
    kind         varchar(8) not null check (kind in ('LOAN', 'ADVANCE')),
    principal    numeric(14, 2) not null check (principal > 0),
    currency     char(3) not null check (currency in ('USD', 'CDF')),
    installment  numeric(14, 2) not null check (installment > 0),
    start_month  date not null check (extract(day from start_month) = 1),
    granted_on   date not null,
    status       varchar(10) not null default 'ACTIVE' check (status in ('ACTIVE', 'REPAID', 'CANCELLED')),
    note         varchar(300),
    created_by   varchar(320) not null,
    created_at   timestamptz not null default now(),
    check (installment <= principal)
);
create index loan_user on loan (user_id);

-- A month in which a loan's installment is not taken (every pause is logged).
create table loan_pause (
    id          bigserial primary key,
    loan_id     bigint not null references loan (id),
    month       date not null check (extract(day from month) = 1),
    reason      varchar(300) not null,
    created_by  varchar(320) not null,
    created_at  timestamptz not null default now(),
    unique (loan_id, month)
);

-- One payroll per month. DRAFT -> CALCULATED (HR) -> APPROVED (Finance) -> CLOSED (HR, payslips locked).
create table payroll_run (
    id              bigserial primary key,
    month           date not null unique check (extract(day from month) = 1),
    status          varchar(10) not null check (status in ('CALCULATED', 'APPROVED', 'CLOSED')),
    usd_cdf         numeric(18, 6) not null,
    rate_date       date not null,
    rate_source     varchar(100) not null,
    employee_count  int not null default 0,
    projected       boolean not null default false,   -- calculated before the month ended; remaining days counted as worked
    calculated_by   varchar(320) not null,
    calculated_at   timestamptz not null,
    approved_by     varchar(320),
    approved_at     timestamptz,
    closed_by       varchar(320),
    closed_at       timestamptz
);

create table payslip (
    id                 bigserial primary key,
    run_id             bigint not null references payroll_run (id) on delete cascade,
    user_id            bigint not null references app_user (id),
    number             varchar(30) unique,                 -- given when the month is closed
    currency           char(3) not null,
    gross              numeric(14, 2) not null,
    cnss_base          numeric(14, 2) not null,
    taxable_base       numeric(14, 2) not null,
    taxable_base_cdf   numeric(16, 2) not null,
    employee_deductions numeric(14, 2) not null,
    net                numeric(14, 2) not null,
    employer_charges   numeric(14, 2) not null,
    scheduled_minutes  int not null,
    paid_minutes       int not null,
    days_worked        int not null,
    snapshot           jsonb not null,                     -- employee, contract, grade, dependants, payment details at calculation
    warnings           jsonb not null default '[]',
    unique (run_id, user_id)
);

create table payslip_line (
    id             bigserial primary key,
    payslip_id     bigint not null references payslip (id) on delete cascade,
    element_code   varchar(32) not null,
    label          varchar(200) not null,
    kind           varchar(16) not null check (kind in ('EARNING', 'DEDUCTION', 'EMPLOYER_CHARGE')),
    base           numeric(16, 4),
    rate           numeric(16, 6),
    amount         numeric(14, 2) not null,
    display_order  int not null,
    loan_id        bigint references loan (id)
);
create index payslip_line_payslip on payslip_line (payslip_id);

-- Repayments are written when a month is closed; a loan's balance is principal minus its repayments.
create table loan_repayment (
    id          bigserial primary key,
    loan_id     bigint not null references loan (id),
    run_id      bigint not null references payroll_run (id),
    amount      numeric(14, 2) not null check (amount > 0),
    unique (loan_id, run_id)
);

insert into statutory_rate_type (code, name, unit, description, display_order) values
    ('DEBT_MAX_SHARE', 'Loan deductions, at most', 'PERCENT',
     'Loan and advance installments together may take at most this share of the net pay; the rest waits.', 120);
insert into statutory_rate (code, effective_from, value, note) values ('DEBT_MAX_SHARE', '2020-01-01', 30, 'Sample, to verify');

insert into role_permission (role_id, permission_code)
select r.id, 'payslip.read.own' from role r where r.code in ('HR', 'FINANCE', 'MANAGER')
on conflict do nothing;
