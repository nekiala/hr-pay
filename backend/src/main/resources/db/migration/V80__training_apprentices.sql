-- Batch D, PR 1: training, the congé d'éducation ouvrière and apprentices.
--   DRC Code du travail art. 8: every employer trains its workers (formation, perfectionnement, adaptation), with the
--   INPP's means if it wishes. Ordonnance 71-055 of 26 March 1971: systematic training plans leaving no category out
--   (art. 17), national managers first (art. 18), drawn up with the workers' representatives (art. 19), a training
--   department or named people in charge (art. 22).
--   Congé d'éducation ouvrière (art. 268-271): 12 working days a year, paid, asked 30 days ahead.
--   Apprentices (art. 7, 18-35): written contract of at most 4 years with the ONEM's visa (art. 21), a medical certificate
--   (art. 22), 18 years old or 15 with the Tribunal de paix's waiver (art. 6), an end certificate (art. 24) and the end
--   reported to the inspector and ONEM (art. 33); they may be bound to stay up to 2 years afterwards (art. 27).
set local app.bypass = 'on';

insert into permission (code, category, description) values
    ('training.manage', 'users', 'Training: courses, the annual plan, sessions, requests and apprentices')
on conflict do nothing;
insert into role_permission (company_id, role_id, permission_code)
select r.company_id, r.id, 'training.manage' from role r where r.code in ('ADMIN', 'HR')
on conflict do nothing;

-- A course the company offers or buys.
create table training_course (
    id               bigserial primary key,
    title            varchar(200) not null,
    provider_kind    varchar(8) not null default 'INTERNAL' check (provider_kind in ('INTERNAL', 'INPP', 'EXTERNAL')),
    provider_name    varchar(200),
    mode             varchar(10) not null default 'CLASSROOM' check (mode in ('CLASSROOM', 'ON_THE_JOB', 'ONLINE')),
    hours            numeric(6, 1) not null check (hours > 0 and hours <= 2000),
    cost_per_person  numeric(14, 2) not null default 0 check (cost_per_person >= 0),
    currency         char(3) not null default 'USD',
    description      varchar(2000),
    active           boolean not null default true,
    created_at       timestamptz not null default now()
);
select hrpay_company_table('training_course');

-- The annual plan (O. 71-055 art. 17-22).
create table training_plan (
    id                       bigserial primary key,
    year                     int not null check (year between 2000 and 2100),
    status                   varchar(9) not null default 'DRAFT' check (status in ('DRAFT', 'CONSULTED', 'APPROVED', 'CLOSED')),
    budget                   numeric(14, 2) check (budget >= 0),
    currency                 char(3) not null default 'USD',
    consulted_on             date,               -- the workers' representatives were consulted (art. 19)
    representatives_opinion  varchar(2000),
    approved_by              varchar(320),
    approved_at              timestamptz,
    note                     varchar(2000),
    created_at               timestamptz not null default now()
);
select hrpay_company_table('training_plan');
create unique index training_plan_year_uk on training_plan (company_id, year);

create table training_plan_line (
    id                 bigserial primary key,
    plan_id            bigint not null references training_plan (id) on delete cascade,
    course_id          bigint references training_course (id),
    topic              varchar(300) not null,
    category_id        bigint references grade_category (id),   -- null: every category
    unit_id            bigint references org_unit (id),          -- null: the whole company
    quarter            int check (quarter between 1 and 4),
    people             int not null default 1 check (people between 1 and 10000),
    budget             numeric(14, 2) check (budget >= 0),
    national_managers  boolean not null default false,          -- trains national managers (art. 18)
    note               varchar(500)
);
select hrpay_company_table('training_plan_line');
create index training_plan_line_plan on training_plan_line (plan_id);

-- A need: asked by the employee or the manager, or found in objectives, evaluations or (later) skill gaps.
create table training_need (
    id                bigserial primary key,
    year              int not null check (year between 2000 and 2100),
    user_id           bigint references app_user (id),
    unit_id           bigint references org_unit (id),
    topic             varchar(300) not null,
    course_id         bigint references training_course (id),
    source            varchar(10) not null check (source in ('EMPLOYEE', 'MANAGER', 'OBJECTIVE', 'EVALUATION', 'SKILL_GAP', 'HR')),
    source_ref        bigint,                   -- the objective or evaluation it came from
    priority          varchar(6) not null default 'MEDIUM' check (priority in ('HIGH', 'MEDIUM', 'LOW')),
    status            varchar(9) not null default 'OPEN'
                      check (status in ('REQUESTED', 'OPEN', 'PLANNED', 'DONE', 'REFUSED', 'DROPPED')),
    reason            varchar(1000),
    manager_opinion   varchar(7) check (manager_opinion in ('SUPPORT', 'OPPOSE')),
    manager_comment   varchar(1000),
    manager_at        timestamptz,
    decided_by        varchar(320),
    decided_at        timestamptz,
    decision_comment  varchar(1000),
    plan_line_id      bigint references training_plan_line (id) on delete set null,
    requested_by      bigint references app_user (id),
    created_at        timestamptz not null default now(),
    check (user_id is not null or unit_id is not null)
);
select hrpay_company_table('training_need');
create index training_need_year on training_need (company_id, year, status);
create unique index training_need_source_uk on training_need (company_id, source, source_ref, coalesce(user_id, 0), topic)
    where source_ref is not null;

create table training_session (
    id            bigserial primary key,
    course_id     bigint not null references training_course (id),
    plan_line_id  bigint references training_plan_line (id) on delete set null,
    starts_on     date not null,
    ends_on       date not null check (ends_on >= starts_on),
    place         varchar(200),
    trainer       varchar(200),
    status        varchar(9) not null default 'PLANNED' check (status in ('PLANNED', 'DONE', 'CANCELLED')),
    actual_cost   numeric(14, 2) check (actual_cost >= 0),
    currency      char(3) not null default 'USD',
    note          varchar(1000),
    created_by    varchar(320) not null,
    created_at    timestamptz not null default now()
);
select hrpay_company_table('training_session');

create table training_attendee (
    id               bigserial primary key,
    session_id       bigint not null references training_session (id) on delete cascade,
    user_id          bigint not null references app_user (id),
    need_id          bigint references training_need (id) on delete set null,
    attended         boolean,
    rating           int check (rating between 1 and 5),       -- the attendee's view of the session
    rating_comment   varchar(1000),
    rated_at         timestamptz,
    effect_due_on    date,                                      -- the manager is asked three months after
    effect_asked_on  date,
    effect           varchar(6) check (effect in ('YES', 'PARTLY', 'NO')),
    effect_comment   varchar(1000),
    effect_by        bigint references app_user (id),
    effect_at        timestamptz,
    document_id      bigint references employee_document (id),
    unique (session_id, user_id)
);
select hrpay_company_table('training_attendee');
create index training_attendee_user on training_attendee (user_id);

-- Apprentices: a contract type of its own, and what the Code asks for beside it.
alter table employee_contract drop constraint employee_contract_contract_type_check;
alter table employee_contract add constraint employee_contract_contract_type_check
    check (contract_type in ('PERMANENT', 'FIXED_TERM', 'INTERN', 'CONSULTANT', 'DAILY', 'APPRENTICE'));
alter table hiring_request drop constraint hiring_request_contract_type_check;
alter table hiring_request add constraint hiring_request_contract_type_check
    check (contract_type in ('PERMANENT', 'FIXED_TERM', 'INTERN', 'CONSULTANT', 'DAILY', 'APPRENTICE'));
alter table vacancy drop constraint vacancy_contract_type_check;
alter table vacancy add constraint vacancy_contract_type_check
    check (contract_type in ('PERMANENT', 'FIXED_TERM', 'INTERN', 'CONSULTANT', 'DAILY', 'APPRENTICE'));

create table apprenticeship (
    contract_id         bigint primary key references employee_contract (id) on delete cascade,
    user_id             bigint not null references app_user (id),
    trade               varchar(200),
    master_user_id      bigint references app_user (id),
    onem_visa_on        date,
    onem_visa_no        varchar(60),
    medical_on          date,
    waiver_on           date,          -- Tribunal de paix waiver for an apprentice aged 15 to 17 (art. 6)
    allowance_review_on date,          -- the allowance rises each year (art. 25)
    end_certificate_on  date,
    end_reported_on     date,          -- to the labour inspector and ONEM (art. 33)
    stay_months         int check (stay_months between 0 and 24),
    note                varchar(1000),
    reminded_on         date,
    updated_by          varchar(320),
    updated_at          timestamptz not null default now()
);
select hrpay_company_table('apprenticeship');

-- Congé d'éducation ouvrière: union members and delegates, 12 working days a year, paid.
insert into leave_type (company_id, code, name, paid, uses_balance, requires_document, max_days, active, pay_percent,
                        circumstance, hr_only)
select c.id, 'WORKER_EDUCATION', case when c.language = 'fr' then 'Congé d''éducation ouvrière' else 'Workers'' education leave' end,
       true, false, true, null, c.country_code = 'CD', 100, false, false
from company c
on conflict do nothing;

insert into payroll_setting (company_id, key, value)
select c.id, s.key, s.value
from company c cross join (values
    ('TRAINING_LEAD', ''),                     -- the person or department in charge of training (O. 71-055 art. 22)
    ('TRAINING_EFFECT_MONTHS', '3'),           -- months after a session when the manager says whether it changed the work
    ('APPRENTICE_CONTRIBUTIONS', 'NONE'),      -- NONE: no CNSS or tax on the apprentice allowance; SALARY: as a salary
    ('WORKER_EDUCATION_DAYS', '12'),           -- art. 268
    ('WORKER_EDUCATION_NOTICE_DAYS', '30')     -- art. 269
) as s(key, value)
on conflict do nothing;
