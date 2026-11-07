-- Phase 1: work schedules, overtime policy and public holidays.

create table work_schedule (
    id                        bigserial primary key,
    code                      varchar(32)  not null unique,
    name                      varchar(150) not null,
    grace_minutes             integer      not null default 0 check (grace_minutes between 0 and 120),
    deduct_late_arrival       boolean      not null default true,
    deduct_early_departure    boolean      not null default true,
    active                    boolean      not null default true,
    created_at                timestamptz  not null default now(),
    updated_at                timestamptz  not null default now()
);

-- One row per weekday (1 = Monday ... 7 = Sunday, ISO).
create table schedule_day (
    schedule_id    bigint   not null references work_schedule (id) on delete cascade,
    weekday        integer  not null check (weekday between 1 and 7),
    workday        boolean  not null,
    start_time     time,
    end_time       time,
    lunch_enabled  boolean  not null default false,
    lunch_start    time,
    lunch_minutes  integer  check (lunch_minutes between 0 and 240),
    lunch_paid     boolean  not null default false,
    primary key (schedule_id, weekday),
    constraint schedule_day_hours check (not workday or (start_time is not null and end_time is not null and start_time < end_time)),
    constraint schedule_day_lunch check (not lunch_enabled or (lunch_start is not null and lunch_minutes > 0))
);

-- Which schedule applies to a user from a given date. The latest row on or before a date wins.
create table user_schedule (
    id             bigserial primary key,
    user_id        bigint not null references app_user (id) on delete cascade,
    schedule_id    bigint not null references work_schedule (id),
    effective_from date   not null,
    unique (user_id, effective_from)
);

create table overtime_policy (
    id                         bigserial primary key,
    country_code               varchar(2)  not null,
    effective_from             date        not null,
    allowed                    boolean     not null default true,
    requires_approval          boolean     not null default true,
    rounding_minutes           integer     not null default 15 check (rounding_minutes between 1 and 60),
    max_weekly_overtime_minutes integer,
    unique (country_code, effective_from)
);

create table overtime_rate_tier (
    id            bigserial primary key,
    policy_id     bigint        not null references overtime_policy (id) on delete cascade,
    kind          varchar(16)   not null check (kind in ('WEEKDAY', 'REST_DAY', 'HOLIDAY')),
    -- Overtime hours within the week covered by this tier: [from_hour, to_hour). Null to_hour = no limit.
    from_hour     integer       not null default 0,
    to_hour       integer,
    multiplier    numeric(4, 2) not null check (multiplier >= 1),
    unique (policy_id, kind, from_hour)
);

create table holiday_definition (
    id            bigserial primary key,
    country_code  varchar(2)   not null,
    name          varchar(150) not null,
    usual_month   integer      not null check (usual_month between 1 and 12),
    usual_day     integer      not null check (usual_day between 1 and 31),
    paid          boolean      not null default true,
    active        boolean      not null default true,
    unique (country_code, usual_month, usual_day)
);

create table holiday_occurrence (
    id               bigserial primary key,
    definition_id    bigint       not null references holiday_definition (id) on delete cascade,
    year             integer      not null,
    official_date    date         not null,
    observed_date    date         not null,
    announcement_ref varchar(255),
    set_by           varchar(254),
    set_at           timestamptz,
    unique (definition_id, year)
);
create index holiday_occurrence_observed_idx on holiday_occurrence (observed_date);

-- DRC overtime: +30% for the first 6 overtime hours of the week, +60% beyond, +100% on rest days and holidays.
insert into overtime_policy (country_code, effective_from, allowed, requires_approval) values ('CD', '2020-01-01', true, true);
insert into overtime_rate_tier (policy_id, kind, from_hour, to_hour, multiplier)
select id, t.kind, t.from_hour, t.to_hour, t.multiplier
from overtime_policy, (values ('WEEKDAY', 0, 6, 1.30), ('WEEKDAY', 6, null, 1.60),
                              ('REST_DAY', 0, null, 2.00), ('HOLIDAY', 0, null, 2.00))
                     as t(kind, from_hour, to_hour, multiplier)
where country_code = 'CD';

-- Starting list of DR Congo public holidays. HR checks it against the current official text and edits it.
insert into holiday_definition (country_code, name, usual_month, usual_day) values
    ('CD', 'New Year''s Day', 1, 1),
    ('CD', 'Martyrs of Independence Day', 1, 4),
    ('CD', 'Laurent-Désiré Kabila Day', 1, 16),
    ('CD', 'Patrice Lumumba Day', 1, 17),
    ('CD', 'Simon Kimbangu Day', 4, 6),
    ('CD', 'Labour Day', 5, 1),
    ('CD', 'Liberation Day', 5, 17),
    ('CD', 'Independence Day', 6, 30),
    ('CD', 'Parents'' Day', 8, 1),
    ('CD', 'Congolese Genocide Remembrance Day', 8, 2),
    ('CD', 'Christmas Day', 12, 25);

-- A standard schedule: Monday to Friday 08:00-17:00 with an unpaid hour of lunch at 12:00.
insert into work_schedule (code, name, grace_minutes) values ('STANDARD', 'Mon-Fri 08:00-17:00', 10);
insert into schedule_day (schedule_id, weekday, workday, start_time, end_time, lunch_enabled, lunch_start, lunch_minutes)
select id, d, d <= 5, case when d <= 5 then time '08:00' end, case when d <= 5 then time '17:00' end,
       d <= 5, case when d <= 5 then time '12:00' end, case when d <= 5 then 60 end
from work_schedule, generate_series(1, 7) as d
where code = 'STANDARD';
