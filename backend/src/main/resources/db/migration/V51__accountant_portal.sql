-- Accountant portal: an accounting firm has its own space (a company of kind FIRM) with its own staff. A client
-- company's administrator invites the firm, chooses what it may do (some of the client's roles) and accepts the data
-- processing clause; the firm accepts and chooses who pays HR Pay for that client: the client (the firm earns a
-- commission) or the firm (at a wholesale price). The firm's staff then work in the client with those rights only;
-- every change is in the client's audit log, as "person (firm)", and every page they open is kept for the client.

set local app.bypass = 'on';

alter table company add column kind varchar(8) not null default 'COMPANY' check (kind in ('COMPANY', 'FIRM'));
alter table company add column firm_registration varchar(60);      -- ONEC (DRC) or ONECCA (Congo) number, as given

alter table trial_request add column kind varchar(8) not null default 'COMPANY' check (kind in ('COMPANY', 'FIRM'));
alter table trial_request add column firm_registration varchar(60);

-- Links span two companies, so they are shared tables: only the accountant portal's service reads them.
create table firm_client (
    id              bigserial primary key,
    firm_id         bigint not null references company (id),
    client_id       bigint not null references company (id),
    status          varchar(8) not null default 'INVITED' check (status in ('INVITED', 'ACTIVE', 'DECLINED', 'ENDED')),
    role_codes      text[] not null,                  -- the client's roles whose permissions the firm gets
    billing         varchar(10) check (billing in ('COMMISSION', 'WHOLESALE')),   -- chosen by the firm on accepting
    clause_version  varchar(20) not null,             -- the data processing clause the client's administrator accepted
    invited_by      varchar(320) not null,
    invited_at      timestamptz not null default now(),
    answered_by     varchar(320),
    answered_at     timestamptz,
    ended_by        varchar(320),
    ended_at        timestamptz,
    end_reason      varchar(500),
    check (firm_id <> client_id),
    check (status <> 'ACTIVE' or billing is not null)
);
create unique index firm_client_open on firm_client (firm_id, client_id) where status in ('INVITED', 'ACTIVE');
create index firm_client_client on firm_client (client_id);

-- The firm's staff who work for a client (the firm's administrators work for all its clients).
create table firm_client_staff (
    link_id  bigint not null references firm_client (id) on delete cascade,
    user_id  bigint not null references app_user (id),
    primary key (link_id, user_id)
);

-- What the firm's staff opened or changed in a client, for the client's administrator.
create table firm_visit (
    id       bigserial primary key,
    link_id  bigint not null references firm_client (id),
    visitor  varchar(320) not null,
    method   varchar(8) not null,
    path     varchar(300) not null,
    at       timestamptz not null default now()
);
create index firm_visit_link on firm_visit (link_id, at desc);

-- The firm's commission on its clients' paid invoices (clients that pay HR Pay themselves), paid out by hand.
create table firm_commission (
    id              bigserial primary key,
    firm_id         bigint not null references company (id),
    client_id       bigint not null references company (id),
    invoice_id      bigint not null unique references invoice (id),
    invoice_number  varchar(30) not null,
    base            numeric(16, 2) not null,          -- the invoice before tax
    percent         numeric(5, 2) not null,
    amount          numeric(16, 2) not null,
    currency        char(3) not null,
    created_at      timestamptz not null default now(),
    paid_out_at     timestamptz,
    paid_out_by     varchar(320),
    paid_out_ref    varchar(120)
);
create index firm_commission_firm on firm_commission (firm_id, created_at desc);

-- An invoice the firm pays for its client, at the wholesale price.
alter table invoice add column paid_by_firm_id bigint references company (id);
alter table invoice add column firm_discount_percent numeric(5, 2);
alter table invoice add column client_name varchar(150);   -- the company the invoice is for, when the firm is billed

alter table billing_setting add column firm_commission_percent numeric(5, 2) not null default 10
    check (firm_commission_percent >= 0 and firm_commission_percent <= 50);
alter table billing_setting add column firm_discount_percent numeric(5, 2) not null default 20
    check (firm_discount_percent >= 0 and firm_discount_percent <= 50);

create or replace function hrpay_shared_tables() returns text[] language sql immutable as $$
    select array['company', 'plan', 'installation', 'permission', 'exchange_rate', 'spring_session',
                 'spring_session_attributes', 'job_lock', 'backup_run', 'system_alert', 'login_failure',
                 'flyway_schema_history', 'billing_setting', 'invoice_counter', 'audit_chain_head', 'legal_document',
                 'status_incident', 'status_update', 'trial_request', 'platform_mail_server', 'calculator_lead',
                 'platform_whatsapp', 'firm_client', 'firm_client_staff', 'firm_visit', 'firm_commission']
$$;

insert into permission (code, category, description) values
    ('firm.manage', 'users', 'Work with an accounting firm (invite it, choose its rights, end the link); in a firm, manage its clients and staff');
insert into role_permission (company_id, role_id, permission_code)
select r.company_id, r.id, 'firm.manage' from role r where r.code = 'ADMIN';
