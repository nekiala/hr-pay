-- Batch C, PR 3: onboarding. The new hire fills their file before day one from a one-time link (no account), each
-- hiring step has an owner and a due day, a buddy is named, a welcome page opens on day one, and the manager reviews the
-- trial period (after 1 month, and 2 weeks before it ends).
set local app.bypass = 'on';

-- Who does each step, and when: days from the start date (negative: before it).
alter table checklist_template_item
    add column owner    varchar(10) not null default 'HR' check (owner in ('HR', 'MANAGER', 'IT', 'NEW_HIRE', 'BUDDY')),
    add column due_days integer check (due_days between -90 and 90);
alter table employee_checklist_item
    add column owner       varchar(10) not null default 'HR' check (owner in ('HR', 'MANAGER', 'IT', 'NEW_HIRE', 'BUDDY')),
    add column due_on      date,
    add column reminded_on date;

-- Sensible defaults for the steps HR Pay created: papers before day one, equipment and welcome on day one.
update checklist_template_item set due_days = -3, owner = 'NEW_HIRE' where kind = 'HIRING' and action = 'DOCUMENT';
update checklist_template_item set due_days = -2 where kind = 'HIRING' and action = 'CONTRACT_SIGNED';
update checklist_template_item set due_days = 0, owner = 'MANAGER' where kind = 'HIRING' and action = 'EQUIPMENT';
update checklist_template_item set due_days = 0, owner = 'MANAGER'
    where kind = 'HIRING' and label in ('Welcomed: team, rules and safety', 'Accueil : équipe, règlement intérieur et sécurité');
update checklist_template_item set due_days = 8
    where kind = 'HIRING' and label in ('Registered with the CNSS', 'Immatriculation à la CNSS');
update checklist_template_item set due_days = 0, owner = 'IT'
    where kind = 'HIRING' and label in ('Enrolled on the time clock', 'Enregistrement sur la pointeuse');

-- The words the new hire reads on the welcome page: rules, where to go on day one, who to ask.
alter table company add column welcome_text text;

create table onboarding (
    id                  bigserial primary key,
    user_id             bigint not null references app_user (id),
    start_on            date not null,
    status              varchar(10) not null default 'OPEN' check (status in ('OPEN', 'DONE')),
    -- The one-time link: valid until the start date, then the account takes over.
    token               uuid unique,
    token_expires_on    date,
    link_sent_at        timestamptz,
    answers             text,                  -- encrypted JSON: details, ID, accounts, family
    submitted_at        timestamptz,
    details_applied_at  timestamptz,
    details_applied_by  varchar(320),
    buddy_user_id       bigint references app_user (id),
    welcome_seen_at     timestamptz,
    created_by          varchar(320),
    created_at          timestamptz not null default now(),
    closed_at           timestamptz
);
select hrpay_company_table('onboarding');
create unique index onboarding_user_ux on onboarding (user_id);

-- Papers sent from the link: HR checks each one before it goes into the employee file.
create table onboarding_document (
    id                bigserial primary key,
    onboarding_id     bigint not null references onboarding (id) on delete cascade,
    document_type_id  bigint not null references document_type (id),
    file_name         varchar(200) not null,
    content_type      varchar(100) not null,
    data              bytea not null,
    status            varchar(10) not null default 'PENDING' check (status in ('PENDING', 'ACCEPTED', 'REJECTED')),
    reason            varchar(300),
    checked_by        varchar(320),
    checked_at        timestamptz,
    uploaded_at       timestamptz not null default now()
);
select hrpay_company_table('onboarding_document');
create index onboarding_document_onboarding_ix on onboarding_document (onboarding_id);

-- The manager's reviews during the trial: MID after one month, END two weeks before the trial ends.
create table trial_review (
    id              bigserial primary key,
    user_id         bigint not null references app_user (id),
    contract_id     bigint not null references employee_contract (id) on delete cascade,
    kind            varchar(4) not null check (kind in ('MID', 'END')),
    due_on          date not null,
    trial_ends_on   date not null,
    reviewer_id     bigint references app_user (id),
    status          varchar(8) not null default 'PLANNED' check (status in ('PLANNED', 'DONE', 'CANCELLED')),
    rating          integer check (rating between 1 and 5),
    strengths       varchar(2000),
    improvements    varchar(2000),
    recommendation  varchar(8) check (recommendation in ('CONFIRM', 'END')),
    comment         varchar(2000),
    done_by         varchar(320),
    done_at         timestamptz,
    reminded_on     date
);
select hrpay_company_table('trial_review');
create unique index trial_review_contract_kind_ux on trial_review (contract_id, kind);
