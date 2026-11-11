-- Follow-up of trials and fixed-term contracts, day workers, and the staff representation (délégation syndicale).
--   DRC Code du travail (Loi 015/2002, Loi 16/010): art. 43 a trial is written, at most 1 month for an unskilled
--   labourer and 6 months for others, and work continued after it confirms the contract; art. 71 during the trial
--   either party ends it with 3 working days' notice, none in the first 3 days; art. 40 a day worker who worked 22 days
--   in two months and is hired again in that time is under a permanent contract; art. 41 a fixed-term contract lasts at
--   most 2 years, 2 contracts, renewed once (checked when the contract is written, ContractTerms).
--   Arrêté 12/CAB/VPM/METPS/048/2015 (amends the 2004 election arrêté): a délégation syndicale in every establishment of
--   at least 10 workers (art. 3): 1 delegate up to 19 workers, 3 up to 99, 5 up to 499, 9 up to 999, then 1 more per
--   1,000, each with an alternate; elected for 3 years, renewable (art. 30); the election is announced 3 weeks ahead
--   (art. 6); 15 to 35 paid hours a month for the mandate (art. 35); a meeting with the employer every three months,
--   minutes to the labour inspector within eight days (art. 37). Arrêté 036/95: dismissing or transferring a delegate or
--   an alternate needs the labour inspector's decision after an inquiry, or it is void. Code art. 62: no dismissal for
--   holding a mandate; art. 157: the company rules go to the workers' representatives and the inspector.
--   Republic of the Congo (Loi 45-75 via CongoPaie, to verify): delegates from 7 workers (art. 173); a fixed-term
--   contract's trial is at most 15 days for 6 months or less, one month above (art. 32); no notice during a trial
--   unless the contract says so (art. 38).

set local app.bypass = 'on';

insert into permission (code, category, description) values
    ('staff.representation', 'users', 'Staff representation: delegates, elections, meetings, inspector authorisations, delegation hours')
on conflict do nothing;
insert into role_permission (company_id, role_id, permission_code)
select r.company_id, r.id, m.permission_code from role r
join (values ('ADMIN', 'staff.representation'), ('HR', 'staff.representation')) as m(role_code, permission_code)
  on m.role_code = r.code
on conflict do nothing;

-- A day worker (travailleur journalier, art. 40).
alter table employee_contract drop constraint employee_contract_contract_type_check;
alter table employee_contract add constraint employee_contract_contract_type_check
    check (contract_type in ('PERMANENT', 'FIXED_TERM', 'INTERN', 'CONSULTANT', 'DAILY'));

-- The trial ended by either party (art. 71).
alter table departure drop constraint departure_reason_check;
alter table departure add constraint departure_reason_check
    check (reason in ('RESIGNATION', 'DISMISSAL', 'GROSS_MISCONDUCT', 'REDUNDANCY', 'END_OF_CONTRACT', 'END_OF_TRIAL',
                      'MUTUAL_AGREEMENT', 'RETIREMENT', 'DEATH'));

insert into payroll_setting (company_id, key, value)
select c.id, s.key, case when c.country_code = 'CD' then s.cd else s.other end
from company c cross join (values
    ('TRIAL_REMIND_DAYS',      '30,15,5', '30,15,5'),  -- days before a trial ends when HR and the head are reminded
    ('CDD_REMIND_DAYS',        '60,30,15', '60,30,15'),-- days before a fixed-term contract ends
    ('TRIAL_END_NOTICE_DAYS',  '3',  '0'),             -- working days' notice to end a trial after its first 3 days (art. 71)
    ('DAILY_WORKER_MAX_DAYS',  '22', '22'),            -- days in the window after which a day worker is permanent (art. 40)
    ('DAILY_WORKER_WARN_DAYS', '18', '18'),
    ('DAILY_WORKER_WINDOW_MONTHS', '2', '2'),
    ('DELEGATES_FROM_WORKERS', '10', '7'),             -- headcount from which delegates are elected
    ('DELEGATE_TERM_YEARS',    '3',  '3'),
    ('DELEGATE_HOURS_MIN',     '15', '0'),             -- paid hours a month for the mandate (AM 048/2015 art. 35)
    ('DELEGATE_HOURS_MAX',     '35', '0'),
    ('DELEGATE_MEETING_MONTHS','3',  '3')              -- the employer meets the délégation every three months (art. 37)
) as s(key, cd, other)
on conflict do nothing;

-- What HR decided when a trial or a fixed-term contract came to its end.
--   kind TRIAL: CONFIRMED (letter sent), CONFIRMED_BY_LAW (the trial ended with no decision), ENDED (departure).
--   kind FIXED_TERM: RENEWED (new fixed-term contract), MADE_PERMANENT (new permanent contract), ENDED (departure).
create table contract_followup (
    id               bigserial primary key,
    user_id          bigint not null references app_user (id),
    contract_id      bigint not null references employee_contract (id) on delete cascade,
    kind             varchar(10) not null check (kind in ('TRIAL', 'FIXED_TERM')),
    due_on           date not null,              -- the trial's last day, or the contract's end date
    decision         varchar(16) not null check (decision in ('CONFIRMED', 'CONFIRMED_BY_LAW', 'ENDED', 'RENEWED', 'MADE_PERMANENT')),
    decided_on       date not null,
    decided_by       varchar(320) not null,
    note             varchar(300),
    letter_id        bigint references employee_letter (id) on delete set null,
    new_contract_id  bigint references employee_contract (id) on delete set null,
    created_at       timestamptz not null default now(),
    unique (user_id, kind, due_on)
);
select hrpay_company_table('contract_followup');
create index contract_followup_user on contract_followup (user_id);

-- The délégation syndicale: who holds a mandate, from the election, for three years.
create table staff_delegate (
    id            bigserial primary key,
    user_id       bigint not null references app_user (id),
    seat          varchar(9) not null check (seat in ('TITULAR', 'ALTERNATE')),
    union_name    varchar(150),
    elected_on    date not null,
    term_end      date not null,
    ended_on      date,                           -- the mandate ended early (left, resigned, replaced)
    end_reason    varchar(300),
    created_by    varchar(320) not null,
    created_at    timestamptz not null default now(),
    check (term_end > elected_on),
    check (ended_on is null or ended_on >= elected_on)
);
select hrpay_company_table('staff_delegate');
create index staff_delegate_user on staff_delegate (user_id);

-- Elections, meetings with the délégation, and the company rules sent to it: dates, minutes and the inspector's copy.
create table staff_rep_event (
    id                 bigserial primary key,
    kind               varchar(10) not null check (kind in ('ELECTION', 'MEETING', 'RULES_SENT')),
    held_on            date not null,
    announced_on       date,                      -- an election is announced at least 3 weeks before (art. 6)
    title              varchar(200) not null,
    note               text,
    inspector_copy_on  date,                      -- minutes or rules sent to the labour inspector
    file_name          varchar(200),
    file_type          varchar(100),
    file_data          bytea,
    created_by         varchar(320) not null,
    created_at         timestamptz not null default now()
);
select hrpay_company_table('staff_rep_event');
create index staff_rep_event_day on staff_rep_event (kind, held_on desc);

-- The labour inspector's decision to dismiss or transfer a delegate (arrêté 036/95).
create table delegate_authorisation (
    id            bigserial primary key,
    user_id       bigint not null references app_user (id),
    action        varchar(9) not null check (action in ('DISMISSAL', 'TRANSFER')),
    decided_on    date not null,
    reference     varchar(100) not null,
    granted       boolean not null default true,
    note          varchar(500),
    file_name     varchar(200),
    file_type     varchar(100),
    file_data     bytea,
    created_by    varchar(320) not null,
    created_at    timestamptz not null default now()
);
select hrpay_company_table('delegate_authorisation');
create index delegate_authorisation_user on delegate_authorisation (user_id, action, decided_on desc);

-- Hours spent on the mandate, paid as working time (AM 048/2015 art. 35).
create table delegation_hours (
    id          bigserial primary key,
    user_id     bigint not null references app_user (id),
    on_day      date not null,
    hours       numeric(4, 2) not null check (hours > 0 and hours <= 12),
    note        varchar(300),
    created_by  varchar(320) not null,
    created_at  timestamptz not null default now()
);
select hrpay_company_table('delegation_hours');
create index delegation_hours_user on delegation_hours (user_id, on_day);
