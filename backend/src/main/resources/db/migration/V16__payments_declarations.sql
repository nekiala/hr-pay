-- Phase 4: paying a closed payroll (bank, mobile money, cash) and the monthly declarations.

insert into permission (code, category, description) values
    ('payroll.pay',        'payroll', 'Make payment files for a closed payroll and record payments'),
    ('declaration.manage', 'payroll', 'Export the monthly CNSS, IPR, INPP, ONEM and IERE declarations and record them as filed'),
    ('company.manage',     'payroll', 'Edit the employer details printed on declarations and payment files');

insert into role_permission (role_id, permission_code)
select r.id, p.code from role r cross join (values ('payroll.pay'), ('declaration.manage')) as p(code)
where r.code = 'FINANCE'
on conflict do nothing;
insert into role_permission (role_id, permission_code)
select r.id, p.code from role r cross join (values ('declaration.manage'), ('company.manage')) as p(code)
where r.code = 'HR'
on conflict do nothing;

-- The employer, as it appears on declarations and payment files. One row.
create table company (
    id              int primary key default 1 check (id = 1),
    name            varchar(150) not null,
    address         varchar(300),
    cnss_number     varchar(40),       -- employer number at the CNSS
    tax_number      varchar(40),       -- NIF at the DGI
    national_id     varchar(40),       -- Id. Nat.
    rccm            varchar(60),       -- trade register
    inpp_number     varchar(40),
    onem_number     varchar(40),
    bank_name       varchar(100),
    bank_account    varchar(60),       -- account the salaries are paid from
    updated_by      varchar(320),
    updated_at      timestamptz not null default now()
);
insert into company (name, address) values ('Sample employer', 'Kinshasa, DR Congo');

-- A payment file: the payslips of one closed month paid one way (one bank, one mobile money operator, or cash),
-- in one currency. Lines copy the payment details used, so the file can be produced again exactly.
create table payment_batch (
    id            bigserial primary key,
    run_id        bigint not null references payroll_run (id),
    method        varchar(14) not null check (method in ('BANK', 'MOBILE_MONEY', 'CASH')),
    provider      varchar(100) not null,              -- bank name, operator, or 'Cash'
    currency      char(3) not null,
    line_count    int not null,
    total         numeric(16, 2) not null,
    status        varchar(10) not null check (status in ('PREPARED', 'PAID', 'CANCELLED')),
    created_by    varchar(320) not null,
    created_at    timestamptz not null default now(),
    paid_on       date,
    reference     varchar(100),                       -- bank order or operator transaction reference
    paid_by       varchar(320),
    paid_at       timestamptz,
    cancelled_by  varchar(320),
    cancelled_at  timestamptz,
    cancel_reason varchar(300)
);
create index payment_batch_run on payment_batch (run_id);

create table payment_line (
    id            bigserial primary key,
    batch_id      bigint not null references payment_batch (id) on delete cascade,
    payslip_id    bigint not null unique references payslip (id),   -- a payslip is in at most one live file
    user_id       bigint not null references app_user (id),
    beneficiary   varchar(150) not null,
    registration_no varchar(40),
    account       varchar(60),                         -- bank account or mobile number (243...)
    amount        numeric(14, 2) not null,
    currency      char(3) not null
);

-- A monthly declaration recorded as filed (the amounts themselves are always computed from the closed payslips).
create table declaration (
    id           bigserial primary key,
    month        date not null check (extract(day from month) = 1),
    kind         varchar(8) not null check (kind in ('CNSS', 'IPR', 'INPP', 'ONEM', 'IERE')),
    total_cdf    numeric(18, 2) not null,
    filed_on     date not null,
    reference    varchar(100),
    note         varchar(300),
    filed_by     varchar(320) not null,
    filed_at     timestamptz not null default now(),
    unique (month, kind)
);
