-- Health and safety at work: medical visits, the work accident register, and the health and safety committee (CSHE).
--   DRC Code du travail art. 38 (medical exam before hiring, paid by the employer); arrêté départemental 28/75: the
--   fitness certificate is valid one year (six months under 18), a worker off 30 days or more sees the doctor before
--   going back, the doctor's findings stay secret (only fit or not reaches the employer). Arrêté 01/76: one exam a year
--   for every worker; the medical service grows with the headcount; a yearly activity report in the first quarter.
--   Loi 16/009 (social security) art. 65: an accident is declared to the CNSS within 60 days, an occupational disease
--   within 120 days, with a copy to the labour inspection and to the victim. Code art. 106: during an incapacity the
--   worker keeps two thirds of the pay for six months. Arrêté 043/2008: a committee (CSHE) from 20 workers; worker
--   members 2 (20-50), 4 (51-100), 6 (101-500), 8 (over 500), 10 (1,000 and over), for two years; a meeting at least
--   every two months; a monthly inspection walk; a yearly report to the Minister through the inspection by 31 March.
--   Republic of the Congo (CongoPaie, to verify): accidents declared within 48 hours; no committee rule found.

set local app.bypass = 'on';

insert into permission (code, category, description) values
    ('health.safety', 'users', 'Health and safety: medical visits, work accidents, the safety committee')
on conflict do nothing;
insert into role_permission (company_id, role_id, permission_code)
select r.company_id, r.id, m.permission_code from role r
join (values ('ADMIN', 'health.safety'), ('HR', 'health.safety')) as m(role_code, permission_code)
  on m.role_code = r.code
on conflict do nothing;

insert into payroll_setting (company_id, key, value)
select c.id, s.key, case when c.country_code = 'CD' then s.cd else s.other end
from company c cross join (values
    ('MEDICAL_VALID_MONTHS',        '12', '12'),   -- a fitness certificate lasts one year (AD 28/75)
    ('MEDICAL_VALID_MONTHS_MINOR',  '6',  '6'),    -- six months under 18
    ('MEDICAL_PROVISIONAL_MONTHS',  '3',  '3'),    -- a nurse's provisional certificate: the doctor sees the worker within 3 months
    ('MEDICAL_RETURN_AFTER_DAYS',   '30', '30'),   -- days off after which a return-to-work visit is due
    ('MEDICAL_REMIND_DAYS',         '30', '30'),   -- days before a visit is due when HR is reminded
    ('MEDICAL_SERVICE',             'OUTSIDE', 'OUTSIDE'), -- OWN, SHARED or OUTSIDE (an outside doctor or clinic)
    ('ACCIDENT_DECLARE_DAYS',       '60', '2'),    -- days to declare an accident to the CNSS (Congo: 48 hours, to verify)
    ('DISEASE_DECLARE_DAYS',        '120', '2'),   -- an occupational disease
    ('ACCIDENT_PAY_MONTHS',         '6',  '6'),    -- months the accident leave keeps its share of the pay (art. 106)
    ('SAFETY_COMMITTEE_FROM',       '20', '0'),    -- headcount from which the committee is set up; 0 = not required
    ('SAFETY_MEETING_MONTHS',       '2',  '2'),
    ('SAFETY_TERM_YEARS',           '2',  '2')
) as s(key, cd, other)
on conflict do nothing;

-- Work accident leave, paid at two thirds (art. 106); HR records it from the accident register.
insert into leave_type (company_id, code, name, paid, uses_balance, requires_document, max_days, active, pay_percent,
                        circumstance, hr_only)
select c.id, 'WORK_ACCIDENT', case when c.language = 'fr' then 'Accident du travail' else 'Work accident' end,
       true, false, false, null, true, case when c.country_code = 'CD' then 66.67 else 100 end, false, true
from company c
on conflict do nothing;

-- A medical visit: planned (no result yet) or done. Never a diagnosis: only the result the doctor gives the employer.
create table medical_visit (
    id               bigserial primary key,
    user_id          bigint not null references app_user (id),
    kind             varchar(11) not null check (kind in ('HIRING', 'PROVISIONAL', 'YEARLY', 'RETURN', 'SPECIAL')),
    due_on           date,                        -- when it should take place (planned visits)
    visited_on       date,                        -- when it took place
    doctor           varchar(150),
    result           varchar(15) check (result in ('FIT', 'FIT_RESTRICTED', 'UNFIT_TEMPORARY', 'UNFIT')),
    restrictions     varchar(500),
    valid_until      date,
    note             varchar(500),                -- practical note (place, time); never medical findings
    leave_request_id bigint references leave_request (id) on delete set null,
    accident_id      bigint,
    file_name        varchar(200),
    file_type        varchar(100),
    file_data        bytea,
    created_by       varchar(320) not null,
    created_at       timestamptz not null default now(),
    check (visited_on is not null or due_on is not null),
    check ((visited_on is null) = (result is null))
);
select hrpay_company_table('medical_visit');
create index medical_visit_user on medical_visit (user_id, visited_on desc nulls first);
create unique index medical_visit_return on medical_visit (leave_request_id) where leave_request_id is not null;

-- The work accident register: accidents at work, on the way to or from work, and occupational diseases.
create table work_accident (
    id               bigserial primary key,
    user_id          bigint not null references app_user (id),
    kind             varchar(9) not null check (kind in ('WORK', 'COMMUTING', 'DISEASE')),
    occurred_at      timestamptz not null,        -- for a disease: the day it was first noted by a doctor
    place            varchar(200),
    description      text not null,
    witnesses        varchar(500),
    injury           varchar(500),                -- what is hurt (hand, back...), as told; no diagnosis
    first_aid        varchar(500),
    off_from         date,                        -- days off: first and last day (the accident day is paid in full)
    off_to           date,
    status           varchar(8) not null default 'REPORTED' check (status in ('REPORTED', 'RECORDED')),
    reported_by      varchar(320) not null,
    reported_at      timestamptz not null default now(),
    declared_on      date,                        -- sent to the CNSS
    declaration_ref  varchar(100),
    inspection_copy_on date,
    victim_copy_on   date,
    letter_id        bigint references employee_letter (id) on delete set null,
    leave_request_id bigint references leave_request (id) on delete set null,
    note             varchar(1000),
    check (off_to is null or off_from is not null and off_to >= off_from)
);
select hrpay_company_table('work_accident');
create index work_accident_day on work_accident (occurred_at desc);
alter table medical_visit add constraint medical_visit_accident foreign key (accident_id) references work_accident (id) on delete set null;

-- The health and safety committee (CSHE): members, meetings, monthly inspection walks, actions, the yearly report.
create table safety_member (
    id          bigserial primary key,
    user_id     bigint references app_user (id),
    name        varchar(150),                     -- someone from outside (the doctor), when not an employee
    role        varchar(9) not null check (role in ('PRESIDENT', 'SECRETARY', 'DOCTOR', 'TRAINING', 'WORKER')),
    from_on     date not null,
    term_end    date not null,
    ended_on    date,
    created_by  varchar(320) not null,
    created_at  timestamptz not null default now(),
    check (user_id is not null or name is not null),
    check (term_end > from_on)
);
select hrpay_company_table('safety_member');

create table safety_event (
    id           bigserial primary key,
    kind         varchar(14) not null check (kind in ('MEETING', 'INSPECTION', 'REPORT', 'MEDICAL_REPORT')),
    held_on      date not null,                   -- for the yearly report: the day it was sent
    year         integer,                         -- the year a report covers
    title        varchar(200) not null,
    note         text,                            -- agenda, findings
    file_name    varchar(200),
    file_type    varchar(100),
    file_data    bytea,
    created_by   varchar(320) not null,
    created_at   timestamptz not null default now()
);
select hrpay_company_table('safety_event');
create index safety_event_day on safety_event (kind, held_on desc);

create table safety_action (
    id          bigserial primary key,
    event_id    bigint references safety_event (id) on delete set null,
    accident_id bigint references work_accident (id) on delete set null,
    risk        varchar(500) not null,
    action      varchar(500) not null,
    owner_id    bigint references app_user (id),
    due_on      date,
    done_on     date,
    created_by  varchar(320) not null,
    created_at  timestamptz not null default now()
);
select hrpay_company_table('safety_action');
