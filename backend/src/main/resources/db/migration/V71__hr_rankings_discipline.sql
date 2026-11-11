-- HR rankings (who is late, absent, on overtime most...) and the discipline register.
--   DRC Code du travail (Loi 015/2002 as amended by Loi 16/010): art. 54 lists the sanctions (blâme, réprimande, mise à
--   pied, dismissal) weighed by "la gravité, la répétition de la faute"; art. 57-5 allows a mise à pied of at most two
--   times fifteen days a year, only when the contract, the collective agreement or the company rules provide it, and the
--   contract is then suspended (no pay, art. 59 and 86); art. 111 and 112 forbid fines and pay cuts as damages (penalty
--   art. 321); art. 62 lets the worker defend himself before any decision on his conduct; art. 61 ter: written notice with
--   the reason; art. 72: a dismissal for serious misconduct is notified within 15 working days of knowing the facts.
--   Arrêté 70/0016 du 11/08/1970: company rules (règlement d'entreprise) from 20 workers set the discipline scale.
--   So a sanction never creates a pay line: only a mise à pied makes its days unpaid, as unpaid leave HR records.
--   Republic of the Congo (Loi 45-75): not verified yet, the same rules apply until checked.

set local app.bypass = 'on';

insert into permission (code, category, description) values
    ('report.hr', 'users', 'HR rankings: lateness, absences, overtime, sick leave, missions, sanctions and turnover'),
    ('discipline.manage', 'users', 'Discipline register: open cases, accept proposals, convene and record hearings, notify'),
    ('discipline.decide', 'users', 'Decide the sanction of a discipline case, or close it without one'),
    ('discipline.propose', 'users', 'Propose a discipline case for someone of the team, for HR to accept or reject')
on conflict do nothing;
insert into role_permission (company_id, role_id, permission_code)
select r.company_id, r.id, m.permission_code from role r
join (values ('ADMIN', 'report.hr'), ('HR', 'report.hr'), ('HR', 'discipline.manage'), ('HR', 'discipline.decide'),
             ('MANAGER', 'discipline.propose')) as m(role_code, permission_code) on m.role_code = r.code
on conflict do nothing;

-- Leave HR records itself (a disciplinary mise à pied), never requested by the employee.
alter table leave_type add column hr_only boolean not null default false;
insert into leave_type (company_id, code, name, paid, uses_balance, requires_document, max_days, active, pay_percent,
                        circumstance, hr_only)
select c.id, 'SUSPENSION', case when c.language = 'fr' then 'Mise à pied disciplinaire' else 'Disciplinary suspension' end,
       false, false, false, 15, true, 0, false, true
from company c
on conflict do nothing;

insert into payroll_setting (company_id, key, value)
select c.id, s.key, s.value
from company c cross join (values
    ('DISCIPLINE_RANKING_MONTHS', '24'),       -- months a sanction counts in the rankings (no erasure period in the code)
    ('LATENESS_FOLLOWUP_DAYS', '4'),           -- late days in a month that call for HR's follow-up
    ('DISCIPLINE_SUSPENSION_ALLOWED', 'false'),-- the company rules provide the mise à pied (art. 57-5)
    ('DISCIPLINE_SUSPENSION_MAX_DAYS', '15'),  -- days of one mise à pied
    ('DISCIPLINE_SUSPENSION_YEAR_DAYS', '30')  -- days of mise à pied in a calendar year
) as s(key, value)
on conflict do nothing;

-- The sanctions the company uses, lightest first; the weight orders the sanction ranking.
create table sanction_type (
    id          bigserial primary key,
    code        varchar(30)  not null,
    name        varchar(150) not null,
    weight      integer      not null default 1 check (weight between 0 and 10),
    suspends    boolean      not null default false,   -- a mise à pied: unpaid days
    dismissal   boolean      not null default false,   -- ends the contract (then the departure is recorded)
    letter_code varchar(40),                           -- the letter template that notifies it
    position    integer      not null default 0,
    active      boolean      not null default true
);
select hrpay_company_table('sanction_type');
create unique index sanction_type_code_uk on sanction_type (company_id, code);

insert into sanction_type (company_id, code, name, weight, suspends, dismissal, letter_code, position)
select c.id, s.code, case when c.language = 'fr' then s.name_fr else s.name_en end, s.weight, s.suspends, s.dismissal,
       s.letter_code, s.position
from company c cross join (values
    ('WARNING',    'Written warning',                   'Avertissement écrit',             1, false, false, 'DISCIPLINE_WARNING',    10),
    ('REPRIMAND',  'Reprimand',                         'Réprimande',                      1, false, false, 'DISCIPLINE_REPRIMAND',  20),
    ('BLAME',      'Blame',                             'Blâme',                           2, false, false, 'DISCIPLINE_BLAME',      30),
    ('SUSPENSION', 'Disciplinary suspension',           'Mise à pied disciplinaire',       3, true,  false, 'DISCIPLINE_SUSPENSION', 40),
    ('DISMISSAL',  'Dismissal for serious misconduct',  'Licenciement pour faute lourde',  5, false, true,  'DISCIPLINE_DISMISSAL',  50)
) as s(code, name_en, name_fr, weight, suspends, dismissal, letter_code, position)
on conflict do nothing;

-- A discipline case: the facts, the hearing (the employee's defence), HR's decision and its letter.
--   PROPOSED (a head proposed it) -> OPEN (HR opened or accepted it) -> HEARING (convened) -> HEARD -> DECIDED -> NOTIFIED
--   or CLOSED (no sanction), REJECTED (HR refused the proposal), CANCELLED (the sanction was withdrawn).
create table discipline_case (
    id                bigserial primary key,
    user_id           bigint not null references app_user (id),
    number            varchar(20) not null,
    status            varchar(10) not null default 'OPEN' check (status in ('PROPOSED', 'OPEN', 'HEARING', 'HEARD',
                                                                            'DECIDED', 'NOTIFIED', 'CLOSED', 'REJECTED', 'CANCELLED')),
    origin            varchar(4) not null default 'HR' check (origin in ('HR', 'HEAD')),
    facts_date        date not null,
    known_date        date not null,
    facts             text not null,
    rule_broken       varchar(300),
    lateness_month    date,                -- opened from the lateness follow-up list for this month
    opened_by         varchar(320) not null,
    opened_by_name    varchar(200),
    opened_at         timestamptz not null default now(),
    reviewed_by       varchar(320),
    reviewed_at       timestamptz,
    review_note       varchar(500),
    hearing_at        timestamp,           -- local date and time of the hearing
    hearing_place     varchar(200),
    hearing_letter_id bigint references employee_letter (id),
    heard_on          date,
    attended          boolean,
    statement         text,                -- what the employee said in their defence
    hearing_note      text,                -- HR's notes of the hearing
    heard_by          varchar(320),
    sanction_type_id  bigint references sanction_type (id),
    decision_note     text,
    decided_on        date,
    decided_by        varchar(320),
    decided_at        timestamptz,
    suspension_from   date,
    suspension_to     date,
    suspension_days   integer,
    leave_request_id  bigint references leave_request (id),
    letter_id         bigint references employee_letter (id),
    notified_at       timestamptz,
    contest           text,                -- the employee's written contest
    contested_at      timestamptz,
    cancelled_by      varchar(320),
    cancelled_at      timestamptz,
    cancel_reason     varchar(500),
    check (known_date >= facts_date),
    check ((suspension_from is null) = (suspension_days is null))
);
select hrpay_company_table('discipline_case');
create unique index discipline_case_number on discipline_case (company_id, number);
create index discipline_case_user on discipline_case (user_id, decided_on);
