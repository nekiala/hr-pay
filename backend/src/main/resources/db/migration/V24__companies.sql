-- Several companies on one installation. Every company's rows carry its id, and PostgreSQL itself hides the rows of
-- other companies (row-level security): the app sets app.company_id on each database connection it uses, and a query
-- that forgets a filter still sees only its own company. app.bypass = 'on' is kept for the few platform tasks that
-- span companies (finding which company an email belongs to at sign-in, listing companies for scheduled jobs).

create or replace function current_company_id() returns bigint language sql stable
as $$ select nullif(current_setting('app.company_id', true), '')::bigint $$;

create or replace function tenancy_bypass() returns boolean language sql stable
as $$ select coalesce(current_setting('app.bypass', true), '') = 'on' $$;

-- Plans by company size; each company picks the modules it pays for.
create table plan (
    code          varchar(20) primary key,
    name          varchar(60) not null,
    max_employees int,                       -- null: no limit
    display_order int not null
);
insert into plan (code, name, max_employees, display_order) values
    ('UP_TO_50',   'Up to 50 employees',       50,   10),
    ('UP_TO_300',  '51 to 300 employees',      300,  20),
    ('UP_TO_1000', '301 to 1,000 employees',   1000, 30),
    ('OVER_1000',  'Over 1,000 employees',     null, 40);

-- The company table held the one employer; it now holds every company of the installation.
alter table company drop constraint company_id_check;
alter table company alter column id drop default;
alter table company alter column id type bigint;
create sequence company_id_seq owned by company.id;
select setval('company_id_seq', (select max(id) from company));
alter table company alter column id set default nextval('company_id_seq');
alter table company
    add column country_code varchar(2)  not null default 'CD',
    add column plan_code    varchar(20) not null default 'UP_TO_1000' references plan (code),
    add column modules      varchar(20)[] not null default '{ATTENDANCE,PAYROLL,EVALUATIONS}',
    add column status       varchar(16) not null default 'ACTIVE' check (status in ('ACTIVE', 'SUSPENDED')),
    add column created_at   timestamptz not null default now(),
    add column created_by   varchar(320);
alter table company add constraint company_modules_check
    check (modules <@ array['ATTENDANCE', 'PAYROLL', 'EVALUATIONS']::varchar[]);

-- This installation: when it started (on-premise, for the evaluation period) and its licence file, if any.
create table installation (
    id                   int primary key default 1 check (id = 1),
    started_at           timestamptz not null default now(),
    licence              text,
    licence_installed_at timestamptz,
    licence_installed_by varchar(320)
);
insert into installation default values;

insert into permission (code, category, description) values
    ('platform.manage', 'audit', 'Create companies, change their plan and modules, and install the licence');
insert into role_permission (role_id, permission_code)
select r.id, 'platform.manage' from role r where r.code = 'ADMIN';

-- Every other table belongs to one company, except these, which are shared by the installation.
create or replace function hrpay_shared_tables() returns text[] language sql immutable as $$
    select array['company', 'plan', 'installation', 'permission', 'exchange_rate', 'spring_session',
                 'spring_session_attributes', 'job_lock', 'backup_run', 'system_alert', 'login_failure',
                 'flyway_schema_history']
$$;

-- Puts a table under company separation: a company key filled from the connection, and the isolation policy.
-- Later migrations call it for each new table.
create or replace function hrpay_company_table(t text) returns void language plpgsql as $$
begin
    if not exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = t
                   and column_name = 'company_id') then
        execute format('alter table %I add column company_id bigint not null default 1', t);
    end if;
    execute format('alter table %I alter column company_id set default current_company_id()', t);
    execute format('alter table %I add constraint %I foreign key (company_id) references company (id)', t, t || '_company_fk');
    execute format('create index %I on %I (company_id)', t || '_company_ix', t);
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format('create policy company_isolation on %I using (company_id = current_company_id() or tenancy_bypass())'
                   ' with check (company_id = current_company_id() or tenancy_bypass())', t);
end $$;

do $$
declare
    t text;
begin
    for t in select tablename from pg_tables where schemaname = 'public'
             and tablename <> all (hrpay_shared_tables()) order by tablename loop
        perform hrpay_company_table(t);
    end loop;
end $$;

-- A failed sign-in with an unknown email belongs to no company: the audit log allows that one case.
alter table audit_log alter column company_id drop not null;

-- Codes, numbers and months were unique in the installation; they are now unique within a company. Keys that
-- include a reference to another row are already scoped through that row and stay as they are; so does the
-- email, which stays unique across companies because it finds the company at sign-in.
do $$
declare
    r record;
begin
    for r in
        select c.conname, c.conrelid::regclass::text as tbl, c.contype, pg_get_constraintdef(c.oid) as def
        from pg_constraint c join pg_class cl on cl.oid = c.conrelid
        where c.contype in ('p', 'u') and cl.relnamespace = 'public'::regnamespace
          and cl.relname <> all (hrpay_shared_tables())
          and pg_get_constraintdef(c.oid) not in ('PRIMARY KEY (id)')
          and not exists (select 1 from pg_constraint f where f.conrelid = c.conrelid and f.contype = 'f'
                          and f.conkey && c.conkey)
    loop
        execute format('alter table %I drop constraint %I', r.tbl, r.conname);
        execute format('alter table %I add constraint %I %s', r.tbl, r.conname,
                       regexp_replace(r.def, '^(PRIMARY KEY|UNIQUE) \(', '\1 (company_id, '));
    end loop;

    for r in
        select ic.relname as idx, tc.relname as tbl, pg_get_indexdef(i.indexrelid) as def
        from pg_index i join pg_class ic on ic.oid = i.indexrelid join pg_class tc on tc.oid = i.indrelid
        where i.indisunique and not i.indisprimary and tc.relnamespace = 'public'::regnamespace
          and tc.relname <> all (hrpay_shared_tables())
          and not exists (select 1 from pg_constraint c where c.conindid = i.indexrelid)
          and ic.relname <> 'app_user_email_uk'
          and not exists (select 1 from pg_constraint f where f.conrelid = i.indrelid and f.contype = 'f'
                          and f.conkey && string_to_array(i.indkey::text, ' ')::int2[])
    loop
        execute format('drop index %I', r.idx);
        execute replace(r.def, ' USING btree (', ' USING btree (company_id, ');
    end loop;
end $$;

-- Each company sees only its own row of the company table.
alter table company enable row level security;
alter table company force row level security;
create policy company_self on company using (id = current_company_id() or tenancy_bypass())
    with check (id = current_company_id() or tenancy_bypass());

-- The app works through this role, which row-level security applies to even when the app signs in to the database
-- as its owner or as a superuser (a superuser otherwise skips the policies). Creating it needs the CREATEROLE right;
-- without it the app must sign in as a plain (non-superuser) owner, which the policies apply to as well.
do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'hrpay_app') then
        begin
            create role hrpay_app nologin;
        exception when insufficient_privilege then
            raise notice 'Could not create the hrpay_app role; sign the app in as a non-superuser owner instead.';
        end;
    end if;
    if exists (select 1 from pg_roles where rolname = 'hrpay_app') then
        grant usage on schema public to hrpay_app;
        grant select, insert, update, delete on all tables in schema public to hrpay_app;
        grant usage, select, update on all sequences in schema public to hrpay_app;
        alter default privileges in schema public grant select, insert, update, delete on tables to hrpay_app;
        alter default privileges in schema public grant usage, select, update on sequences to hrpay_app;
    end if;
end $$;

-- Signed-in sessions now carry the company; everyone signs in again once after this upgrade.
delete from spring_session;
