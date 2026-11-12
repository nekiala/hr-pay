-- Skills: a catalogue with four levels, the skills each job needs, each person's levels (the employee rates
--   themselves, the manager confirms; HR may set a level directly), the courses that build a skill, and the
--   licences and certifications kept as documents with an expiry date.

set local app.bypass = 'on';

insert into permission (code, category, description) values
    ('skills.manage', 'users', 'Skills catalogue, the skills each job needs, people''s levels and the gap matrix')
on conflict do nothing;
insert into role_permission (company_id, role_id, permission_code)
select r.company_id, r.id, 'skills.manage' from role r where r.code in ('ADMIN', 'HR')
on conflict do nothing;

create table skill (
    id           bigserial primary key,
    family       varchar(11) not null check (family in ('TECHNICAL', 'BEHAVIOURAL', 'LANGUAGE', 'TOOL')),
    name         varchar(150) not null,
    description  varchar(1000),
    level1       varchar(300),     -- notions
    level2       varchar(300),     -- practitioner
    level3       varchar(300),     -- confirmed
    level4       varchar(300),     -- expert
    active       boolean not null default true,
    created_at   timestamptz not null default now()
);
select hrpay_company_table('skill');
create unique index skill_name_uk on skill (company_id, upper(name));

-- The skills a job needs, and the level.
create table occupation_skill (
    id             bigserial primary key,
    occupation_id  bigint not null references occupation (id) on delete cascade,
    skill_id       bigint not null references skill (id) on delete cascade,
    level          smallint not null check (level between 1 and 4),
    unique (occupation_id, skill_id)
);
select hrpay_company_table('occupation_skill');

-- The skills a course builds.
create table training_course_skill (
    id         bigserial primary key,
    course_id  bigint not null references training_course (id) on delete cascade,
    skill_id   bigint not null references skill (id) on delete cascade,
    unique (course_id, skill_id)
);
select hrpay_company_table('training_course_skill');

-- A person's level in a skill. The confirmed level (level) is the one that counts; self_level waits for the manager
-- while status is SELF.
create table person_skill (
    id              bigserial primary key,
    user_id         bigint not null references app_user (id),
    skill_id        bigint not null references skill (id) on delete cascade,
    self_level      smallint check (self_level between 1 and 4),
    self_note       varchar(500),
    self_at         timestamptz,
    level           smallint check (level between 1 and 4),
    confirmed_by    bigint references app_user (id),
    confirmed_note  varchar(500),
    confirmed_at    timestamptz,
    status          varchar(9) not null default 'SELF' check (status in ('SELF', 'CONFIRMED')),
    unique (user_id, skill_id)
);
select hrpay_company_table('person_skill');
create index person_skill_waiting on person_skill (company_id, status) where status = 'SELF';

-- Licences and certifications, with an expiry date: they join the existing expiry reminders.
insert into document_type (company_id, code, name, has_expiry, remind_days, employee_can_upload, position)
select c.id, 'CERTIFICATION', case when c.language = 'fr' then 'Permis et certifications' else 'Licences and certifications' end,
       true, 30, true, 106
from company c where c.lifecycle_seeded
on conflict do nothing;
