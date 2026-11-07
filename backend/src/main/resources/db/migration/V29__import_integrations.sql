-- Phase 15: imports from spreadsheets, the public API with access keys, webhooks, biometric time clocks and export
-- layouts for the customer's accounting and banking tools.

-- The number an employee types or is enrolled under on a time clock. Empty: the registration number.
alter table app_user add column clock_pin varchar(20);
create unique index app_user_clock_pin_uk on app_user (company_id, clock_pin) where clock_pin is not null;

-- Check-ins now also come from time clocks and from other systems through the API.
alter table clock_event drop constraint if exists clock_event_source_check;
alter table clock_event add constraint clock_event_source_check
    check (source in ('SELF', 'HR_MANUAL', 'AUTO_CLOSE', 'MISSION', 'BIOMETRIC', 'API'));

-- Each import of a spreadsheet: what was imported, by whom, and the rows it saved.
create table import_batch (
    id          bigserial primary key,
    kind        varchar(20) not null,
    file_name   varchar(200),
    row_count   integer not null,
    created_by  varchar(320) not null,
    created_at  timestamptz not null default now()
);
select hrpay_company_table('import_batch');

-- Year-to-date totals paid before HR Pay, for a person who joins the app during the year: the year summaries and
-- annual statements add them to the months closed in HR Pay.
create table ytd_opening (
    id               bigserial primary key,
    user_id          bigint not null references app_user (id),
    year             integer not null,
    through_month    integer not null check (through_month between 1 and 12),
    currency         varchar(3) not null,
    gross            numeric(14, 2) not null,
    cnss             numeric(14, 2) not null default 0,
    tax              numeric(14, 2) not null default 0,
    other_deductions numeric(14, 2) not null default 0,
    net              numeric(14, 2) not null,
    taxable_cdf      numeric(16, 2) not null default 0,
    tax_cdf          numeric(16, 2) not null default 0,
    note             varchar(300),
    created_by       varchar(320) not null,
    created_at       timestamptz not null default now(),
    unique (user_id, year, currency)
);
select hrpay_company_table('ytd_opening');

-- Access keys of the public API. Only a hash of the secret is kept; the prefix finds the key.
create table api_key (
    id            bigserial primary key,
    name          varchar(100) not null,
    prefix        varchar(16) not null unique,
    secret_hash   varchar(64) not null,
    permissions   text[] not null,
    created_by    varchar(320) not null,
    created_at    timestamptz not null default now(),
    expires_on    date,
    last_used_at  timestamptz,
    last_used_ip  varchar(64),
    revoked_at    timestamptz,
    revoked_by    varchar(320)
);
select hrpay_company_table('api_key');

-- Webhooks: HR Pay calls the customer's address when something happens (a payroll closed, a person hired).
create table webhook (
    id          bigserial primary key,
    url         varchar(500) not null,
    events      text[] not null,
    secret      varchar(200) not null,
    active      boolean not null default true,
    created_by  varchar(320) not null,
    created_at  timestamptz not null default now()
);
select hrpay_company_table('webhook');

create table webhook_delivery (
    id              bigserial primary key,
    webhook_id      bigint not null references webhook (id) on delete cascade,
    event           varchar(60) not null,
    payload         jsonb not null,
    status          varchar(10) not null default 'PENDING' check (status in ('PENDING', 'SENT', 'FAILED')),
    attempts        integer not null default 0,
    next_attempt_at timestamptz not null default now(),
    last_code       integer,
    last_error      varchar(300),
    created_at      timestamptz not null default now(),
    sent_at         timestamptz
);
select hrpay_company_table('webhook_delivery');
create index webhook_delivery_due on webhook_delivery (next_attempt_at) where status = 'PENDING';

-- Biometric time clocks (ZKTeco and compatible) that push their punches to HR Pay. The serial number finds the
-- device and its company; a device only records once it is active.
create table biometric_device (
    id            bigserial primary key,
    serial_no     varchar(40) not null unique,
    name          varchar(100) not null,
    site_id       bigint not null references site (id),
    active        boolean not null default true,
    allowed_ips   varchar(300),
    -- Most clocks send "check-in" for every punch unless a key is pressed: then the direction is worked out instead.
    use_status    boolean not null default false,
    attlog_stamp  varchar(20),
    last_seen_at  timestamptz,
    last_ip       varchar(64),
    created_by    varchar(320) not null,
    created_at    timestamptz not null default now()
);
select hrpay_company_table('biometric_device');

-- Every punch received, kept as sent, with what became of it.
create table biometric_punch (
    id             bigserial primary key,
    device_id      bigint not null references biometric_device (id) on delete cascade,
    pin            varchar(20) not null,
    punched_at     timestamp not null,
    status_code    integer,
    verify_code    integer,
    user_id        bigint references app_user (id),
    clock_event_id bigint references clock_event (id),
    outcome        varchar(12) not null check (outcome in ('RECORDED', 'UNKNOWN_PIN', 'IGNORED')),
    detail         varchar(200),
    received_at    timestamptz not null default now(),
    unique (device_id, pin, punched_at)
);
select hrpay_company_table('biometric_punch');
create index biometric_punch_received on biometric_punch (received_at desc);

-- Layouts of the files for the customer's accounting software and bank: which columns, in which order, and how
-- numbers, dates and characters are written.
create table export_layout (
    id            bigserial primary key,
    target        varchar(12) not null check (target in ('ACCOUNTING', 'BANK', 'MOBILE_MONEY', 'CASH')),
    name          varchar(100) not null,
    columns       jsonb not null,
    separator     varchar(3) not null default ';',
    decimal_comma boolean not null default false,
    date_format   varchar(20) not null default 'yyyy-MM-dd',
    header_row    boolean not null default true,
    encoding      varchar(12) not null default 'UTF-8' check (encoding in ('UTF-8', 'WINDOWS-1252')),
    extension     varchar(4) not null default 'csv' check (extension in ('csv', 'txt')),
    created_by    varchar(320) not null,
    created_at    timestamptz not null default now()
);
select hrpay_company_table('export_layout');

insert into permission (code, category, description) values
    ('data.import', 'user', 'Import employees, contracts, salaries, leave balances and year-to-date totals from a spreadsheet'),
    ('api.manage', 'platform', 'Create API access keys and webhooks, and connect time clocks');
insert into role_permission (company_id, role_id, permission_code)
select r.company_id, r.id, 'data.import' from role r where r.code in ('ADMIN', 'HR');
insert into role_permission (company_id, role_id, permission_code)
select r.company_id, r.id, 'api.manage' from role r where r.code = 'ADMIN';
