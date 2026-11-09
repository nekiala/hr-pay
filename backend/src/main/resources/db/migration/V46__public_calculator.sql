-- The website's salary calculator: visitors who ask for their result by email, for the provider to contact.
create table calculator_lead (
    id            bigserial primary key,
    email         varchar(320) not null,
    company_name  varchar(150),
    phone         varchar(40),
    country_code  varchar(2) not null,
    employees     varchar(12),
    language      varchar(2) not null default 'fr',
    summary       varchar(2000) not null,
    ip            varchar(64),
    status        varchar(10) not null default 'NEW' check (status in ('NEW', 'CONTACTED')),
    contacted_by  varchar(320),
    contacted_at  timestamptz,
    created_at    timestamptz not null default now()
);
create index calculator_lead_created on calculator_lead (created_at desc);

-- Shared by the installation, like the trial requests.
create or replace function hrpay_shared_tables() returns text[] language sql immutable as $$
    select array['company', 'plan', 'installation', 'permission', 'exchange_rate', 'spring_session',
                 'spring_session_attributes', 'job_lock', 'backup_run', 'system_alert', 'login_failure',
                 'flyway_schema_history', 'billing_setting', 'invoice_counter', 'audit_chain_head', 'legal_document',
                 'status_incident', 'status_update', 'trial_request', 'platform_mail_server', 'calculator_lead']
$$;
