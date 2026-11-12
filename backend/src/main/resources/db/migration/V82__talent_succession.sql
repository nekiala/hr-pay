-- Talent review and succession: critical posts, a yearly review placing people in a 9-box grid (performance from the
--   last approved evaluation, potential proposed by the manager, both settled by HR and the Direction), successors for
--   each critical post with their readiness and development actions. Confidential: permission talent.manage (HR by
--   default; the head of the Direction générale also sees it). Employees never see it; it is left out of the
--   personal-data export.

set local app.bypass = 'on';

insert into permission (code, category, description) values
    ('talent.manage', 'users', 'Talent review, critical posts and successors (confidential)')
on conflict do nothing;
insert into role_permission (company_id, role_id, permission_code)
select r.company_id, r.id, 'talent.manage' from role r where r.code in ('ADMIN', 'HR')
on conflict do nothing;

-- A post that is hard to replace: the head of a unit, or a job in a unit.
create table critical_post (
    id             bigserial primary key,
    unit_id        bigint not null references org_unit (id),
    head           boolean not null default false,
    occupation_id  bigint references occupation (id),
    title          varchar(200),
    reason         varchar(500),
    active         boolean not null default true,
    created_by     varchar(320),
    created_at     timestamptz not null default now(),
    check (head or occupation_id is not null)
);
select hrpay_company_table('critical_post');
create unique index critical_post_uk on critical_post (company_id, unit_id, head, coalesce(occupation_id, 0)) where active;

create table talent_review (
    id           bigserial primary key,
    year         int not null check (year between 2000 and 2100),
    name         varchar(150) not null,
    category_id  bigint references grade_category (id),
    unit_id      bigint references org_unit (id),
    status       varchar(6) not null default 'OPEN' check (status in ('OPEN', 'CLOSED')),
    note         varchar(1000),
    opened_by    varchar(320),
    opened_at    timestamptz not null default now(),
    closed_at    timestamptz
);
select hrpay_company_table('talent_review');

create table talent_review_member (
    id                  bigserial primary key,
    review_id           bigint not null references talent_review (id) on delete cascade,
    user_id             bigint not null references app_user (id),
    score               numeric(6, 2),                 -- the last approved evaluation, in percent
    performance_auto    smallint check (performance_auto between 1 and 3),
    performance         smallint check (performance between 1 and 3),
    performance_reason  varchar(500),                  -- required when HR moves away from the evaluation
    potential_proposed  smallint check (potential_proposed between 1 and 3),
    potential_reason    varchar(500),
    proposed_by         bigint references app_user (id),
    proposed_at         timestamptz,
    potential           smallint check (potential between 1 and 3),
    flight_risk         varchar(6) check (flight_risk in ('LOW', 'MEDIUM', 'HIGH')),
    mobility            varchar(5) check (mobility in ('NOW', 'LATER', 'NO')),
    note                varchar(1000),
    settled_by          varchar(320),
    settled_at          timestamptz,
    unique (review_id, user_id)
);
select hrpay_company_table('talent_review_member');

create table successor (
    id          bigserial primary key,
    post_id     bigint not null references critical_post (id) on delete cascade,
    user_id     bigint not null references app_user (id),
    readiness   varchar(10) not null check (readiness in ('NOW', 'ONE_TWO', 'THREE_PLUS')),
    note        varchar(500),
    created_by  varchar(320),
    created_at  timestamptz not null default now(),
    unique (post_id, user_id)
);
select hrpay_company_table('successor');

create table successor_action (
    id              bigserial primary key,
    successor_id    bigint not null references successor (id) on delete cascade,
    kind            varchar(7) not null check (kind in ('COURSE', 'MISSION', 'MENTOR', 'ACTING', 'OTHER')),
    title           varchar(300) not null,
    course_id       bigint references training_course (id),
    mentor_user_id  bigint references app_user (id),
    due_on          date,
    done_on         date,
    need_id         bigint references training_need (id) on delete set null,
    created_at      timestamptz not null default now()
);
select hrpay_company_table('successor_action');

-- A course for a successor shows in the training needs.
alter table training_need drop constraint if exists training_need_source_check;
alter table training_need add constraint training_need_source_check
    check (source in ('EMPLOYEE', 'MANAGER', 'OBJECTIVE', 'EVALUATION', 'SKILL_GAP', 'HR', 'SUCCESSION'));

insert into payroll_setting (company_id, key, value)
select c.id, s.key, case when s.key = 'TALENT_RETIREMENT_AGE' then case when c.country_code = 'CD' then '65' else '60' end else s.value end
from company c cross join (values
    ('TALENT_RETIREMENT_AGE', ''),   -- the coverage report flags holders who reach it within 2 years
    ('TALENT_PERF_LOW', '60'),       -- an evaluation below this percent is low performance
    ('TALENT_PERF_HIGH', '80')       -- at or above this percent, high performance
) as s(key, value)
on conflict do nothing;
