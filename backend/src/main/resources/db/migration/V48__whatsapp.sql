-- WhatsApp for employees (Meta WhatsApp Cloud API).
--   Messages go out from the platform's number (the provider's Meta account) unless a company turns on its own.
--   An employee receives WhatsApp messages only after confirming their number with a code and agreeing; they can stop
--   at any time, in the app or by replying STOP. Each consent and withdrawal is kept (DRC Code du numérique,
--   Ordonnance-loi 23/010; Republic of the Congo, Loi 29-2019 on personal data).
--   Pay figures never travel by WhatsApp: the payslip message carries a link that opens it in HR Pay after sign-in.
-- Tokens and app secrets are encrypted with the data key (HRPAY_DATA_KEY) and never sent back to the browser.

set local app.bypass = 'on';

-- One row for the whole installation: the provider's Meta account.
create table platform_whatsapp (
    id               int primary key check (id = 1),
    enabled          boolean not null default false,
    phone_number_id  varchar(40) not null,
    business_id      varchar(40) not null,          -- the WhatsApp Business Account (WABA) id, for the templates
    display_number   varchar(20),
    access_token     text not null,
    app_secret       text,                          -- checks that webhook calls come from Meta
    verify_token     varchar(64) not null,          -- Meta sends it back when the webhook is registered
    templates        jsonb not null default '{}',   -- name|language -> {status, id, reason, checkedAt}
    updated_by       varchar(320) not null,
    updated_at       timestamptz not null default now()
);

-- Each company: whether its people get WhatsApp messages, which kinds, and optionally its own Meta account.
create table company_whatsapp (
    id               bigserial primary key,
    enabled          boolean not null default false,
    kinds            text[] not null default array['PAYSLIP', 'DECISIONS', 'ACTIONS', 'REMINDERS'],
    own_number       boolean not null default false,
    phone_number_id  varchar(40),
    business_id      varchar(40),
    display_number   varchar(20),
    access_token     text,
    app_secret       text,
    verify_token     varchar(64),
    templates        jsonb not null default '{}',
    alerted_at       timestamptz,
    updated_by       varchar(320) not null,
    updated_at       timestamptz not null default now(),
    check (not own_number or (phone_number_id is not null and business_id is not null and access_token is not null))
);
select hrpay_company_table('company_whatsapp');
create unique index company_whatsapp_one on company_whatsapp (company_id);
create unique index company_whatsapp_number on company_whatsapp (phone_number_id) where own_number;

-- The employee's consent: the number confirmed by a code, and when they agreed or stopped.
alter table app_user add column whatsapp_phone varchar(16);       -- the number confirmed; consent ends if it changes
alter table app_user add column whatsapp_opt_in_at timestamptz;   -- null: no WhatsApp messages

create table whatsapp_consent (
    id          bigserial primary key,
    user_id     bigint not null references app_user (id),
    action      varchar(4) not null check (action in ('IN', 'OUT')),
    phone       varchar(16) not null,
    source      varchar(8) not null check (source in ('APP', 'REPLY', 'HR', 'PHONE')),   -- PHONE: the number changed
    created_at  timestamptz not null default now()
);
select hrpay_company_table('whatsapp_consent');
create index whatsapp_consent_user on whatsapp_consent (user_id, created_at desc);

-- Notifications also go by WhatsApp: when they went (or were skipped).
alter table notification add column whatsapp_at timestamptz;
update notification set whatsapp_at = now();

-- Meta's message id and what Meta reports back: sent, delivered, read or failed.
alter table outbox_message add column provider_id varchar(128);
alter table outbox_message add column delivered_at timestamptz;
alter table outbox_message add column read_at timestamptz;
create index outbox_message_provider on outbox_message (provider_id) where provider_id is not null;

create or replace function hrpay_shared_tables() returns text[] language sql immutable as $$
    select array['company', 'plan', 'installation', 'permission', 'exchange_rate', 'spring_session',
                 'spring_session_attributes', 'job_lock', 'backup_run', 'system_alert', 'login_failure',
                 'flyway_schema_history', 'billing_setting', 'invoice_counter', 'audit_chain_head', 'legal_document',
                 'status_incident', 'status_update', 'trial_request', 'platform_mail_server', 'calculator_lead',
                 'platform_whatsapp']
$$;
