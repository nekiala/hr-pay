-- The 13th month becomes its own pay run, for companies that pay one: HR works it out for a year and the payroll month
-- that pays it, Finance approves it (four eyes), and the month-end payroll of that month puts each approved amount on
-- the payslip, where CNSS and income tax are worked out with the rest of the month. Closing that payroll marks it paid.
create table thirteenth_run (
    id               bigserial primary key,
    year             int not null check (year between 2000 and 2100),
    month            date not null check (extract(day from month) = 1),   -- the month-end payroll that pays it
    base             varchar(13) not null check (base in ('AVERAGE_BASIC', 'LAST_BASIC')),
    fallback_percent numeric(5, 2) not null,
    prorate          boolean not null,
    status           varchar(10) not null default 'CALCULATED' check (status in ('CALCULATED', 'APPROVED', 'PAID', 'CANCELLED')),
    skipped          jsonb not null default '[]',
    excluded         jsonb not null default '[]',    -- [{userId, name, reason}] HR left out; kept when worked out again
    calculated_by    varchar(320) not null,
    calculated_at    timestamptz not null default now(),
    approved_by      varchar(320),
    approved_at      timestamptz,
    paid_at          timestamptz,
    cancelled_by     varchar(320),
    cancelled_at     timestamptz,
    cancel_note      varchar(300)
);
select hrpay_company_table('thirteenth_run');
create unique index thirteenth_run_one_open on thirteenth_run (company_id, year) where status <> 'CANCELLED';

create table thirteenth_line (
    id            bigserial primary key,
    run_id        bigint not null references thirteenth_run (id) on delete cascade,
    user_id       bigint not null references app_user (id),
    currency      char(3) not null check (currency in ('USD', 'CDF', 'XAF')),
    score_percent numeric(6, 2) not null,
    score_source  varchar(11) not null check (score_source in ('EVALUATIONS', 'FALLBACK')),
    evaluations   int not null,
    base          numeric(14, 2) not null,
    months_worked int not null,
    amount        numeric(14, 2) not null check (amount > 0),
    unit_id       bigint references org_unit (id),
    unique (run_id, user_id)
);
select hrpay_company_table('thirteenth_line');
