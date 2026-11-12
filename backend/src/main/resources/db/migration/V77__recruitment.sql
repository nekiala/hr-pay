-- Recruitment: a manager asks to hire, HR approves, the vacancy follows each candidate to an offer and the hire.
--   DRC: an employer may recruit directly; ads must not be false or discriminatory and job seekers pay nothing
--   (AM 012/CAB.MIN/ETPS/062/08 art. 15-16; Code du travail art. 1, 62, 128). Vacancies are declared to the placement
--   office, and a foreigner is hired only when the post stayed unfilled 30 days (AD 87/005 art. 3-4): the vacancy keeps
--   the date it was declared to ONEM. Candidate files are kept 12 months after the vacancy closes unless the candidate
--   agreed to be kept for later offers.

set local app.bypass = 'on';

insert into permission (code, category, description) values
    ('recruitment.manage', 'users', 'Requests to hire, vacancies, candidates, interviews, offers and hiring')
on conflict do nothing;
insert into role_permission (company_id, role_id, permission_code)
select r.company_id, r.id, 'recruitment.manage' from role r where r.code in ('ADMIN', 'HR')
on conflict do nothing;

create table hiring_request (
    id                bigserial primary key,
    unit_id           bigint not null references org_unit (id),
    occupation_id     bigint not null references occupation (id),
    posts             integer not null default 1 check (posts between 1 and 100),
    reason            varchar(12) not null check (reason in ('NEW_POST', 'REPLACEMENT', 'TEMPORARY')),
    replaces_user_id  bigint references app_user (id),
    contract_type     varchar(12) not null check (contract_type in ('PERMANENT', 'FIXED_TERM', 'INTERN', 'CONSULTANT', 'DAILY')),
    wanted_on         date,
    grade_id          bigint references grade (id),
    note              varchar(1000),
    post_warning      varchar(400),     -- no free approved post when it was asked
    status            varchar(10) not null default 'PENDING' check (status in ('PENDING', 'APPROVED', 'REFUSED', 'CANCELLED')),
    requested_by      bigint not null references app_user (id),
    requested_at      timestamptz not null default now(),
    decided_by        varchar(320),
    decided_at        timestamptz,
    decision_comment  varchar(1000)
);
select hrpay_company_table('hiring_request');

create table vacancy (
    id                bigserial primary key,
    request_id        bigint references hiring_request (id),
    unit_id           bigint not null references org_unit (id),
    occupation_id     bigint not null references occupation (id),
    posts             integer not null default 1 check (posts between 1 and 100),
    title             varchar(200) not null,
    ad_text           text not null,
    contract_type     varchar(12) not null check (contract_type in ('PERMANENT', 'FIXED_TERM', 'INTERN', 'CONSULTANT', 'DAILY')),
    grade_id          bigint references grade (id),
    place             varchar(200),
    closes_on         date,
    visibility        varchar(14) not null default 'PUBLIC' check (visibility in ('INTERNAL', 'PUBLIC', 'INTERNAL_FIRST')),
    internal_days     integer not null default 0 check (internal_days between 0 and 90),
    status            varchar(10) not null default 'DRAFT' check (status in ('DRAFT', 'OPEN', 'ON_HOLD', 'FILLED', 'CLOSED', 'CANCELLED')),
    onem_declared_on  date,
    onem_reminded_at  timestamptz,
    opened_on         date,
    closed_on         date,
    created_by        varchar(320) not null,
    created_at        timestamptz not null default now()
);
select hrpay_company_table('vacancy');
create index vacancy_status on vacancy (company_id, status);

-- One person who applied, kept once even when they apply to several vacancies (matched by email, then phone).
create table candidate (
    id                bigserial primary key,
    first_name        varchar(100) not null,
    middle_name       varchar(100),
    last_name         varchar(100) not null,
    email             varchar(254),
    phone             varchar(20),
    birth_date        date,
    gender            varchar(6) check (gender in ('FEMALE', 'MALE')),
    nationality_code  varchar(2) references country (code),
    city              varchar(100),
    current_job       varchar(200),
    cv                bytea,
    cv_name           varchar(200),
    cv_type           varchar(100),
    keep_for_future   boolean not null default false,
    consent_at        timestamptz,
    delete_after      date,             -- set when the person's last vacancy closes
    user_id           bigint references app_user (id),   -- staff who applied, or the person once hired
    note              varchar(1000),
    created_at        timestamptz not null default now()
);
select hrpay_company_table('candidate');
create index candidate_email on candidate (company_id, lower(email));

create table application (
    id                  bigserial primary key,
    vacancy_id          bigint not null references vacancy (id) on delete cascade,
    candidate_id        bigint not null references candidate (id) on delete cascade,
    source              varchar(10) not null default 'OTHER' check (source in ('CAREERS', 'INTERNAL', 'REFERRAL', 'AGENCY', 'OTHER')),
    stage               varchar(10) not null default 'APPLIED'
                        check (stage in ('APPLIED', 'SCREENED', 'INTERVIEW', 'OFFER', 'HIRED', 'REJECTED', 'WITHDRAWN', 'ON_HOLD')),
    stage_at            timestamptz not null default now(),
    cover_letter        varchar(4000),
    reject_reason       varchar(400),
    no_refusal_email    boolean not null default false,
    refusal_sent_at     timestamptz,
    -- the offer
    offer_grade_id      bigint references grade (id),
    offer_net           numeric(14, 2) check (offer_net > 0),
    offer_start         date,
    offer_end           date,
    offer_trial_months  integer check (offer_trial_months between 0 and 6),
    offer_body          text,
    offer_sent_on       date,
    offer_answer        varchar(8) check (offer_answer in ('ACCEPTED', 'DECLINED')),
    offer_answer_on     date,
    minor_waiver_on     date,           -- DRC art. 6: a hire aged 15 to 17 needs the waiver of the Président du Tribunal de paix
    hired_user_id       bigint references app_user (id),
    created_at          timestamptz not null default now(),
    unique (vacancy_id, candidate_id)
);
select hrpay_company_table('application');

create table application_event (
    id              bigserial primary key,
    application_id  bigint not null references application (id) on delete cascade,
    at              timestamptz not null default now(),
    by_name         varchar(200),
    stage           varchar(10),
    note            varchar(1000)
);
select hrpay_company_table('application_event');
create index application_event_app on application_event (application_id);

create table interview (
    id              bigserial primary key,
    application_id  bigint not null references application (id) on delete cascade,
    starts_at       timestamptz not null,
    place           varchar(200),
    note            varchar(1000),
    created_by      varchar(320) not null,
    created_at      timestamptz not null default now()
);
select hrpay_company_table('interview');

create table interview_panel (
    interview_id  bigint not null references interview (id) on delete cascade,
    user_id       bigint not null references app_user (id),
    primary key (interview_id, user_id)
);
select hrpay_company_table('interview_panel');

-- One panel member's scores: each skill of the job description from 1 to 5, an overall view and a comment.
create table scorecard (
    id              bigserial primary key,
    interview_id    bigint not null references interview (id) on delete cascade,
    user_id         bigint not null references app_user (id),
    scores          jsonb not null default '[]',
    overall         integer not null check (overall between 1 and 5),
    recommendation  varchar(5) not null check (recommendation in ('YES', 'MAYBE', 'NO')),
    comment         varchar(2000),
    saved_at        timestamptz not null default now(),
    unique (interview_id, user_id)
);
select hrpay_company_table('scorecard');
