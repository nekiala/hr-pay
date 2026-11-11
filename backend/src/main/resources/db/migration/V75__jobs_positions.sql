-- Job descriptions (fiches de poste), career ladders and approved positions per unit.
--   The occupations list becomes the job catalogue: each job gets its purpose, duties, requirements, the grade range it
--   is paid in (so its legal category, Ordonnance 67-442bis annexe II) and whether it is unskilled (a trial of one month
--   at most, DRC Code du travail art. 43). Every saved description is kept as a version; a contract keeps the version
--   in force when it started. A ladder lists the jobs a person can move up to, with the conditions. Positions are the
--   posts each unit may fill in a year; who fills them comes from the unit assignments and the contracts.

set local app.bypass = 'on';

insert into permission (code, category, description) values
    ('jobs.manage', 'users', 'Job descriptions, career ladders and the approved positions of each unit')
on conflict do nothing;
insert into role_permission (company_id, role_id, permission_code)
select r.company_id, r.id, 'jobs.manage' from role r where r.code in ('ADMIN', 'HR')
on conflict do nothing;

create table job_family (
    id      bigserial primary key,
    code    varchar(32) not null,
    name    varchar(150) not null,
    active  boolean not null default true
);
select hrpay_company_table('job_family');
create unique index job_family_code on job_family (company_id, upper(code));

alter table occupation add column family_id      bigint references job_family (id);
alter table occupation add column purpose        varchar(1000);
alter table occupation add column duties         varchar(4000);
alter table occupation add column education      varchar(300);
alter table occupation add column experience     varchar(300);
alter table occupation add column skills         varchar(1000);
alter table occupation add column conditions     varchar(1000);
alter table occupation add column min_grade_id   bigint references grade (id);
alter table occupation add column max_grade_id   bigint references grade (id);
alter table occupation add column reports_to_id  bigint references occupation (id);
alter table occupation add column unskilled      boolean not null default false;
alter table occupation add column version        integer not null default 0;   -- 0: no description saved yet
alter table occupation add column updated_by     varchar(320);
alter table occupation add column updated_at     timestamptz;
alter table occupation add constraint occupation_not_own_boss check (reports_to_id is null or reports_to_id <> id);

-- Every saved description, the current one included, so a contract can show the text it was signed with.
create table occupation_version (
    id             bigserial primary key,
    occupation_id  bigint not null references occupation (id) on delete cascade,
    version        integer not null,
    name           varchar(150) not null,
    purpose        varchar(1000),
    duties         varchar(4000),
    education      varchar(300),
    experience     varchar(300),
    skills         varchar(1000),
    conditions     varchar(1000),
    min_grade      varchar(100),
    max_grade      varchar(100),
    unskilled      boolean not null default false,
    saved_by       varchar(320),
    saved_at       timestamptz not null default now(),
    unique (occupation_id, version)
);
select hrpay_company_table('occupation_version');

alter table employee_contract add column occupation_version integer;

-- The jobs a person can move up to from a job, and what it takes.
create table career_path (
    id                  bigserial primary key,
    from_occupation_id  bigint not null references occupation (id) on delete cascade,
    to_occupation_id    bigint not null references occupation (id) on delete cascade,
    min_years           numeric(4, 1) not null default 0 check (min_years >= 0),
    min_score           numeric(5, 2) check (min_score between 0 and 100),   -- average of the approved evaluations
    requirement         varchar(300),                                         -- a training or diploma, checked by hand
    unique (from_occupation_id, to_occupation_id),
    check (from_occupation_id <> to_occupation_id)
);
select hrpay_company_table('career_path');

-- The posts a unit may fill in a year, for a job. The budgeted cost is yearly, in US dollars.
create table position_budget (
    id             bigserial primary key,
    year           integer not null check (year between 2000 and 2100),
    unit_id        bigint not null references org_unit (id),
    occupation_id  bigint not null references occupation (id),
    posts          integer not null check (posts between 0 and 10000),
    budget_cost    numeric(14, 2) check (budget_cost >= 0),
    note           varchar(300),
    created_by     varchar(320),
    created_at     timestamptz not null default now()
);
select hrpay_company_table('position_budget');
create unique index position_budget_post on position_budget (company_id, year, unit_id, occupation_id);
