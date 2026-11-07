-- Security and compliance: two-step sign-in, encrypted identifiers, signed-in sessions, personal data rights and an
-- audit log whose rows are chained by hashes so that a change made behind the app's back shows.

-- Roles whose members must sign in with a second step. Administrators, HR and finance by default.
alter table role add column two_step_required boolean not null default false;
update role set two_step_required = true where code in ('ADMIN', 'HR', 'FINANCE');

-- A person's second step: an authenticator app (TOTP secret, encrypted) or a code sent by email or WhatsApp.
create table user_two_step (
    user_id        bigint primary key references app_user (id),
    company_id     bigint not null,
    method         varchar(8) not null check (method in ('TOTP', 'CODE')),
    totp_secret    text,                        -- encrypted with the data key
    pending_secret text,                        -- a new secret waiting for its first code
    last_step      bigint,                      -- the last authenticator time step used: a code works once
    recovery_codes text[] not null default '{}', -- hashes of the one-time recovery codes
    confirmed_at   timestamptz,
    updated_at     timestamptz not null default now()
);
select hrpay_company_table('user_two_step');

-- Identifiers kept encrypted: the encrypted text is longer than the number itself.
alter table employee_profile
    alter column cnss_number type text,
    alter column tax_number type text,
    alter column bank_account type text,
    alter column mobile_money_number type text;

-- Each signed-in browser, so a person sees where they are signed in and can sign the others out.
create table user_session (
    session_id   varchar(100) primary key,
    company_id   bigint       not null,
    user_id      bigint       not null references app_user (id),
    method       varchar(20)  not null,
    ip           varchar(64),
    user_agent   varchar(300),
    signed_in_at timestamptz  not null default now(),
    last_seen_at timestamptz  not null default now(),
    ended_at     timestamptz,
    ended_by     varchar(320)
);
select hrpay_company_table('user_session');
create index user_session_user_ix on user_session (user_id, ended_at);

-- A person asks HR to correct something about them; HR applies it or says why not.
create table data_correction_request (
    id          bigserial primary key,
    company_id  bigint        not null,
    user_id     bigint        not null references app_user (id),
    field       varchar(60)   not null,
    current     text,
    requested   text          not null,
    reason      text,
    status      varchar(10)   not null default 'OPEN' check (status in ('OPEN', 'DONE', 'REFUSED')),
    answer      text,
    created_at  timestamptz   not null default now(),
    decided_at  timestamptz,
    decided_by  varchar(320)
);
select hrpay_company_table('data_correction_request');

-- How long a former employee's personal data is kept after they leave, before it is erased (payroll amounts stay).
alter table company add column erase_after_years int not null default 10 check (erase_after_years between 1 and 50);
alter table app_user add column erased_at timestamptz;

-- Sign-in with Microsoft 365 is accepted only from the company's own Microsoft tenant (directory id).
alter table company add column microsoft_tenant varchar(64);

-- The audit chain: each row's hash covers its content and the hash of the row before it. Rows join the chain in the
-- order they are committed (chain_seq), which can differ from their ids when two are written at once.
alter table audit_log add column chain_seq bigint, add column prev_hash char(64), add column hash char(64);

create or replace function audit_log_content(a audit_log) returns text language sql stable as $$
    select concat_ws('|', a.id, to_char(a.occurred_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US'), a.actor,
                     a.action, a.entity_type, a.entity_id, a.details::text, a.ip, a.company_id)
$$;

-- The head of the chain, shared by every company (the chain runs across the installation). Its row lock makes
-- writers join the chain one at a time.
create table audit_chain_head (
    id   int primary key check (id = 1),
    seq  bigint not null,
    hash char(64)
);
create or replace function hrpay_shared_tables() returns text[] language sql immutable as $$
    select array['company', 'plan', 'installation', 'permission', 'exchange_rate', 'spring_session',
                 'spring_session_attributes', 'job_lock', 'backup_run', 'system_alert', 'login_failure',
                 'flyway_schema_history', 'billing_setting', 'invoice_counter', 'audit_chain_head']
$$;

create or replace function audit_log_chain() returns trigger language plpgsql as $$
begin
    update audit_chain_head set seq = seq + 1 where id = 1 returning seq, hash into new.chain_seq, new.prev_hash;
    new.hash := encode(sha256(convert_to(coalesce(new.prev_hash, '') || '|' || audit_log_content(new), 'UTF8')), 'hex');
    update audit_chain_head set hash = new.hash where id = 1;
    return new;
end;
$$;

-- Existing rows join the chain in order.
alter table audit_log disable trigger audit_log_no_update;
do $$
declare
    r audit_log;
    prev char(64);
    n bigint := 0;
begin
    for r in select * from audit_log order by id loop
        n := n + 1;
        update audit_log set chain_seq = n, prev_hash = prev,
               hash = encode(sha256(convert_to(coalesce(prev, '') || '|' || audit_log_content(r), 'UTF8')), 'hex')
        where id = r.id returning hash into prev;
    end loop;
    insert into audit_chain_head (id, seq, hash) values (1, n, prev);
end $$;
alter table audit_log enable trigger audit_log_no_update;
create unique index audit_log_chain_ix on audit_log (chain_seq);

create trigger audit_log_chain before insert on audit_log for each row execute function audit_log_chain();

insert into permission (code, category, description) values
    ('privacy.manage', 'users', 'Answer correction requests, export a person''s data and erase former employees after the retention period');
insert into role_permission (company_id, role_id, permission_code)
select r.company_id, r.id, 'privacy.manage' from role r where r.code in ('ADMIN', 'HR');
