-- Each company may send its emails through its own server (SMTP); otherwise the platform's server, set by the
-- provider in its console (it takes over from SMTP_* in .env); otherwise the .env server; otherwise emails are only
-- written on the Messages page (outbox). The password is encrypted with the data key (HRPAY_DATA_KEY) like the other
-- secrets, and never sent back to the browser.

create table company_mail_server (
    id             bigserial primary key,
    enabled        boolean not null default false,
    protocol       varchar(5) not null default 'SMTP' check (protocol in ('SMTP', 'SMTPS')),
    host           varchar(255) not null,
    port           int not null check (port between 1 and 65535),
    encryption     varchar(8) not null default 'STARTTLS' check (encryption in ('NONE', 'STARTTLS', 'SSL_TLS')),
    from_address   varchar(320) not null,
    from_name      varchar(120) not null,
    bcc            varchar(320),
    username       varchar(320),
    password       text,
    ehlo_domain    varchar(255),
    alerted_at     timestamptz,     -- the last "your email server failed" notice, at most one an hour
    updated_by     varchar(320) not null,
    updated_at     timestamptz not null default now()
);
select hrpay_company_table('company_mail_server');
create unique index company_mail_server_one on company_mail_server (company_id);

-- One row for the whole installation.
create table platform_mail_server (
    id             int primary key check (id = 1),
    enabled        boolean not null default false,
    protocol       varchar(5) not null default 'SMTP' check (protocol in ('SMTP', 'SMTPS')),
    host           varchar(255) not null,
    port           int not null check (port between 1 and 65535),
    encryption     varchar(8) not null default 'STARTTLS' check (encryption in ('NONE', 'STARTTLS', 'SSL_TLS')),
    from_address   varchar(320) not null,
    from_name      varchar(120) not null,
    bcc            varchar(320),
    username       varchar(320),
    password       text,
    ehlo_domain    varchar(255),
    updated_by     varchar(320) not null,
    updated_at     timestamptz not null default now()
);

create or replace function hrpay_shared_tables() returns text[] language sql immutable as $$
    select array['company', 'plan', 'installation', 'permission', 'exchange_rate', 'spring_session',
                 'spring_session_attributes', 'job_lock', 'backup_run', 'system_alert', 'login_failure',
                 'flyway_schema_history', 'billing_setting', 'invoice_counter', 'audit_chain_head', 'legal_document',
                 'status_incident', 'status_update', 'trial_request', 'platform_mail_server']
$$;

-- Which server sent each email: COMPANY, PLATFORM, ENV (the .env settings) or NONE (only written here).
alter table outbox_message add column via varchar(8) check (via in ('COMPANY', 'PLATFORM', 'ENV', 'NONE'));
