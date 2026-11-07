-- Billing for the SaaS installation: a price per plan, each company's subscription (free trial, then monthly or
-- yearly invoices in USD or CDF), payments recorded against invoices, read-only suspension after an unpaid invoice,
-- and the provider's support access, which a company's administrator grants and which is logged.

-- Prices in USD, excluding tax. Sample amounts: the provider sets the real ones in the console.
alter table plan
    add column monthly_price numeric(12, 2) not null default 0 check (monthly_price >= 0),
    add column yearly_price  numeric(12, 2) not null default 0 check (yearly_price >= 0);
update plan set monthly_price = 60,  yearly_price = 600  where code = 'UP_TO_50';
update plan set monthly_price = 200, yearly_price = 2000 where code = 'UP_TO_300';
update plan set monthly_price = 500, yearly_price = 5000 where code = 'UP_TO_1000';
update plan set monthly_price = 900, yearly_price = 9000 where code = 'OVER_1000';

-- The provider's billing rules, one row for the installation.
create table billing_setting (
    id                 int primary key default 1 check (id = 1),
    trial_days         int           not null default 30 check (trial_days between 0 and 365),
    payment_days       int           not null default 15 check (payment_days between 0 and 120),
    suspend_after_days int           not null default 15 check (suspend_after_days between 0 and 365),
    tax_label          varchar(20)   not null default 'TVA',
    tax_rate           numeric(5, 2) not null default 16 check (tax_rate >= 0 and tax_rate < 100),
    invoice_prefix     varchar(10)   not null default 'HP',
    bank_details       text,
    mobile_money       text,
    invoice_note       text,
    updated_at         timestamptz,
    updated_by         varchar(320)
);
insert into billing_setting default values;

-- Invoice numbers run per year across the installation: HP-2026-00001.
create table invoice_counter (
    year int primary key,
    last int not null
);

-- Why a company is suspended: an unpaid invoice (lifted when it is paid) or the provider's decision.
alter table company
    add column suspended_reason varchar(8) check (suspended_reason in ('UNPAID', 'MANUAL')),
    add column suspended_at     timestamptz;
update company set suspended_reason = 'MANUAL', suspended_at = now() where status = 'SUSPENDED';

create table subscription (
    id              bigserial primary key,
    company_id      bigint      not null unique,
    status          varchar(10) not null default 'TRIAL' check (status in ('TRIAL', 'ACTIVE', 'CANCELLED')),
    cycle           varchar(8)  not null default 'MONTHLY' check (cycle in ('MONTHLY', 'YEARLY')),
    currency        char(3)     not null default 'USD' check (currency in ('USD', 'CDF')),
    trial_ends      date,
    next_invoice_on date,                  -- null: no more invoices (cancelled)
    auto_suspend    boolean     not null default true,
    started_at      timestamptz not null default now(),
    cancelled_at    timestamptz,
    updated_at      timestamptz,
    updated_by      varchar(320)
);

create table invoice (
    id                  bigserial primary key,
    company_id          bigint        not null,
    number              varchar(30)   not null unique,
    issued_on           date          not null,
    due_on              date          not null,
    period_from         date          not null,
    period_to           date          not null,
    plan_code           varchar(20)   not null references plan (code),
    plan_name           varchar(60)   not null,
    cycle               varchar(8)    not null check (cycle in ('MONTHLY', 'YEARLY')),
    currency            char(3)       not null check (currency in ('USD', 'CDF')),
    usd_cdf             numeric(14, 4),              -- the BCC rate used for an invoice in CDF
    price_usd           numeric(12, 2) not null,
    subtotal            numeric(16, 2) not null,
    tax_label           varchar(20)   not null,
    tax_rate            numeric(5, 2) not null,
    tax                 numeric(16, 2) not null,
    total               numeric(16, 2) not null,
    status              varchar(8)    not null default 'OPEN' check (status in ('OPEN', 'PAID', 'VOID')),
    paid_at             timestamptz,
    voided_at           timestamptz,
    void_reason         text,
    customer_name       varchar(150)  not null,
    customer_address    text,
    customer_tax_number varchar(60),
    created_at          timestamptz   not null default now(),
    created_by          varchar(320)
);
-- One invoice per company and period, even if two servers run the billing job at once.
create unique index invoice_period_uk on invoice (company_id, period_from) where status <> 'VOID';

create table invoice_payment (
    id          bigserial primary key,
    company_id  bigint         not null,
    invoice_id  bigint         not null references invoice (id),
    method      varchar(16)    not null check (method in ('BANK_TRANSFER', 'MPESA', 'ORANGE_MONEY', 'AIRTEL_MONEY', 'CASH', 'OTHER')),
    reference   varchar(100)   not null,
    amount      numeric(16, 2) not null check (amount > 0),
    paid_on     date           not null,
    note        text,
    recorded_at timestamptz    not null default now(),
    recorded_by varchar(320)
);
-- The same bank or mobile money transaction is never recorded twice.
create unique index invoice_payment_reference_uk on invoice_payment (method, lower(reference));

-- Support access: a company's administrator lets the provider look at its data, read-only, until a set time.
create table support_grant (
    id         bigserial primary key,
    company_id bigint       not null,
    granted_by varchar(320) not null,
    granted_at timestamptz  not null default now(),
    expires_at timestamptz  not null,
    reason     text,
    revoked_at timestamptz,
    revoked_by varchar(320)
);

-- Every request the provider's staff make under a grant.
create table support_visit (
    id         bigserial primary key,
    company_id bigint       not null,
    grant_id   bigint       not null references support_grant (id),
    visitor    varchar(320) not null,
    method     varchar(8)   not null,
    path       varchar(300) not null,
    at         timestamptz  not null default now()
);

select hrpay_company_table('subscription');
select hrpay_company_table('invoice');
select hrpay_company_table('invoice_payment');
select hrpay_company_table('support_grant');
select hrpay_company_table('support_visit');

create or replace function hrpay_shared_tables() returns text[] language sql immutable as $$
    select array['company', 'plan', 'installation', 'permission', 'exchange_rate', 'spring_session',
                 'spring_session_attributes', 'job_lock', 'backup_run', 'system_alert', 'login_failure',
                 'flyway_schema_history', 'billing_setting', 'invoice_counter']
$$;

-- Companies already on the installation start a free trial now; the provider's own company is never billed.
insert into subscription (company_id, trial_ends, next_invoice_on)
select id, current_date + 30, current_date + 30 from company where id <> 1;

insert into permission (code, category, description) values
    ('subscription.manage', 'audit', 'See the subscription and invoices, and allow the provider''s support access');
insert into role_permission (company_id, role_id, permission_code)
select r.company_id, r.id, 'subscription.manage' from role r where r.code = 'ADMIN';
