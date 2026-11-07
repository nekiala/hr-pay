-- Phase 17: going to market. The set-up guide of a new company, support requests to the provider, the terms a company
-- accepts, incidents on the public status page, trial requests from the website, and the management dashboards.

-- ---- set-up guide -------------------------------------------------------------------------------------------------

-- Steps the company marks done itself (the others are read from its data), and whether it put the guide away.
create table setup_step (
    id        bigserial primary key,
    step      varchar(30) not null,
    done_by   varchar(320) not null,
    done_at   timestamptz not null default now()
);
select hrpay_company_table('setup_step');
create unique index setup_step_uk on setup_step (company_id, step);
alter table company add column setup_hidden_at timestamptz;

-- ---- support --------------------------------------------------------------------------------------------------------

create table support_ticket (
    id           bigserial primary key,
    number       varchar(20) not null,
    user_id      bigint not null references app_user (id),
    subject      varchar(150) not null,
    category     varchar(12) not null check (category in ('PROBLEM', 'QUESTION', 'IDEA')),
    status       varchar(10) not null default 'OPEN' check (status in ('OPEN', 'ANSWERED', 'CLOSED')),
    page         varchar(300),
    app_version  varchar(40),
    user_agent   varchar(300),
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    closed_at    timestamptz,
    closed_by    varchar(320)
);
select hrpay_company_table('support_ticket');
create unique index support_ticket_number_uk on support_ticket (number);
create index support_ticket_status on support_ticket (status, updated_at desc);

create table support_message (
    id            bigserial primary key,
    ticket_id     bigint not null references support_ticket (id) on delete cascade,
    from_provider boolean not null,
    author        varchar(320) not null,
    author_name   varchar(200) not null,
    body          text not null check (length(body) <= 5000),
    created_at    timestamptz not null default now()
);
select hrpay_company_table('support_message');
create index support_message_ticket on support_message (ticket_id, id);

-- ---- terms ---------------------------------------------------------------------------------------------------------

-- The provider's documents, one version per language; a company's administrator accepts the current version.
create table legal_document (
    id           bigserial primary key,
    kind         varchar(8) not null check (kind in ('TERMS', 'DPA', 'PRIVACY', 'SLA')),
    version      int not null,
    language     varchar(2) not null check (language in ('fr', 'en')),
    title        varchar(150) not null,
    body         text not null,
    must_accept  boolean not null,
    published_at timestamptz not null default now(),
    published_by varchar(320) not null,
    unique (kind, version, language)
);

create table company_agreement (
    id          bigserial primary key,
    kind        varchar(8) not null,
    version     int not null,
    accepted_by varchar(320) not null,
    accepted_at timestamptz not null default now(),
    ip          varchar(64)
);
select hrpay_company_table('company_agreement');
create unique index company_agreement_uk on company_agreement (company_id, kind, version);

-- ---- status page ---------------------------------------------------------------------------------------------------

create table status_incident (
    id          bigserial primary key,
    title       varchar(150) not null,
    impact      varchar(8) not null check (impact in ('MINOR', 'MAJOR', 'OUTAGE', 'PLANNED')),
    started_at  timestamptz not null,
    resolved_at timestamptz,
    created_by  varchar(320) not null,
    created_at  timestamptz not null default now()
);

create table status_update (
    id          bigserial primary key,
    incident_id bigint not null references status_incident (id) on delete cascade,
    body        varchar(1000) not null,
    created_by  varchar(320) not null,
    created_at  timestamptz not null default now()
);

-- ---- trial requests ------------------------------------------------------------------------------------------------

create table trial_request (
    id            bigserial primary key,
    company_name  varchar(150) not null,
    country_code  varchar(2) not null,
    employees     varchar(12) not null,
    first_name    varchar(100) not null,
    last_name     varchar(100) not null,
    email         varchar(320) not null,
    phone         varchar(40) not null,
    message       varchar(1000),
    language      varchar(2) not null default 'fr',
    ip            varchar(64),
    status        varchar(10) not null default 'NEW' check (status in ('NEW', 'APPROVED', 'DECLINED')),
    company_id    bigint references company (id),
    decided_by    varchar(320),
    decided_at    timestamptz,
    created_at    timestamptz not null default now()
);
create index trial_request_status on trial_request (status, created_at desc);

-- Shared by the installation, like the other provider tables.
create or replace function hrpay_shared_tables() returns text[] language sql immutable as $$
    select array['company', 'plan', 'installation', 'permission', 'exchange_rate', 'spring_session',
                 'spring_session_attributes', 'job_lock', 'backup_run', 'system_alert', 'login_failure',
                 'flyway_schema_history', 'billing_setting', 'invoice_counter', 'audit_chain_head', 'legal_document',
                 'status_incident', 'status_update', 'trial_request']
$$;

-- ---- dashboards ----------------------------------------------------------------------------------------------------

insert into permission (code, category, description) values
    ('dashboard.read', 'payroll', 'See the management dashboards: headcount, turnover, absence and payroll cost by direction');
insert into role_permission (company_id, role_id, permission_code)
select r.company_id, r.id, 'dashboard.read' from role r where r.code in ('ADMIN', 'HR', 'FINANCE');
