-- Phase 5: the organizational structure. A tree of units whose levels are data; every employee belongs to exactly
-- one unit at a time; a unit has at most one head and one deputy at a time, possibly none.

insert into permission (code, category, description) values
    ('org.manage', 'organization', 'Edit levels, units, unit heads and employee assignments');
insert into role_permission (role_id, permission_code)
select r.id, 'org.manage' from role r where r.code = 'HR'
on conflict do nothing;

create table org_level (
    id            bigserial primary key,
    code          varchar(32) not null unique,
    name          varchar(100) not null,
    rank          int not null check (rank between 1 and 20),   -- 1 = top; a unit's parent has a smaller rank
    head_title    varchar(100) not null,
    staff         boolean not null default false,               -- a staff unit (Cellule) may sit under any unit
    active        boolean not null default true
);
insert into org_level (code, name, rank, head_title, staff) values
    ('DG',          'Direction générale', 1, 'Directeur général',    false),
    ('DIRECTION',   'Direction',          2, 'Directeur',            false),
    ('DEPARTEMENT', 'Département',        3, 'Chef de département',  false),
    ('SERVICE',     'Service',            4, 'Chef de service',      false),
    ('SECTION',     'Section',            5, 'Chef de section',      false),
    ('EQUIPE',      'Équipe',             6, 'Chef d''équipe',       false),
    ('CELLULE',     'Cellule',            2, 'Responsable',          true);

create table org_unit (
    id            bigserial primary key,
    code          varchar(32) not null unique,
    name          varchar(150) not null,
    level_id      bigint not null references org_level (id),
    parent_id     bigint references org_unit (id),
    cost_centre   varchar(40),
    valid_from    date not null,
    valid_to      date,                                          -- closed units stay for history
    created_by    varchar(320),
    created_at    timestamptz not null default now(),
    check (valid_to is null or valid_to >= valid_from),
    check (parent_id is null or parent_id <> id)
);
create index org_unit_parent on org_unit (parent_id);

-- Where each employee works, over time. Exactly one open row per employee.
create table org_assignment (
    id          bigserial primary key,
    user_id     bigint not null references app_user (id),
    unit_id     bigint not null references org_unit (id),
    valid_from  date not null,
    valid_to    date,
    reason      varchar(16) not null check (reason in ('HIRE', 'TRANSFER', 'PROMOTION', 'REORGANISATION', 'CORRECTION')),
    note        varchar(300),
    created_by  varchar(320),
    created_at  timestamptz not null default now(),
    check (valid_to is null or valid_to >= valid_from)
);
create unique index org_assignment_open on org_assignment (user_id) where valid_to is null;
create index org_assignment_unit on org_assignment (unit_id);

-- Who heads a unit (or deputises), over time. A person may head any number of units.
create table org_head (
    id          bigserial primary key,
    unit_id     bigint not null references org_unit (id),
    user_id     bigint not null references app_user (id),
    role        varchar(6) not null check (role in ('HEAD', 'DEPUTY')),
    interim     boolean not null default false,
    title       varchar(100),                                    -- overrides the level's head title
    valid_from  date not null,
    valid_to    date,
    created_by  varchar(320),
    created_at  timestamptz not null default now(),
    check (valid_to is null or valid_to >= valid_from)
);
create unique index org_head_open on org_head (unit_id, role) where valid_to is null;
create index org_head_user on org_head (user_id);

-- Start with the top of the tree and put everyone in it; HR then builds the structure and moves people.
insert into org_unit (code, name, level_id, valid_from, created_by)
select 'DG', 'Direction générale', id, date '2000-01-01', 'migration' from org_level where code = 'DG';

insert into org_assignment (user_id, unit_id, valid_from, reason, note, created_by)
select u.id, (select id from org_unit where code = 'DG'), coalesce(u.hire_date, u.created_at::date), 'HIRE',
       'Placed at the top of the structure when it was introduced', 'migration'
from app_user u;
