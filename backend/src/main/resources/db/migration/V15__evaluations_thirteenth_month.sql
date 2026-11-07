-- Evaluations: weighted criteria, scored by the direct manager per period, acknowledged or contested by the
-- employee, approved by HR. The year's approved scores drive the optional 13th month.

create table evaluation_criterion (
    id           bigserial primary key,
    code         varchar(32) not null unique,
    name         varchar(150) not null,
    description  varchar(400),
    source       varchar(12) not null default 'MANAGER' check (source in ('MANAGER', 'ATTENDANCE')),
    active       boolean not null default true
);
insert into evaluation_criterion (code, name, description, source) values
    ('PRODUCTIVITY', 'Productivity',     'Volume of work done against what was expected.', 'MANAGER'),
    ('QUALITY',      'Quality of work',  'Accuracy, care and few corrections needed.', 'MANAGER'),
    ('TEAMWORK',     'Teamwork',         'Helps colleagues, shares information, respects others.', 'MANAGER'),
    ('PUNCTUALITY',  'Punctuality',      'From attendance: 100 less 5 points per late day and 10 per absent day.', 'ATTENDANCE');

create table evaluation_template (
    id                 bigserial primary key,
    name               varchar(150) not null,
    frequency          varchar(12) not null check (frequency in ('MONTHLY', 'QUARTERLY', 'SEMI_ANNUAL')),
    grade_category_id  bigint references grade_category (id),     -- null: everyone without a more specific template
    active             boolean not null default true
);

create table evaluation_template_criterion (
    template_id   bigint not null references evaluation_template (id) on delete cascade,
    criterion_id  bigint not null references evaluation_criterion (id),
    weight        numeric(5, 2) not null check (weight > 0 and weight <= 100),
    primary key (template_id, criterion_id)
);

insert into evaluation_template (name, frequency) values ('Quarterly review (sample)', 'QUARTERLY');
insert into evaluation_template_criterion (template_id, criterion_id, weight)
select t.id, c.id, w.weight from evaluation_template t, evaluation_criterion c
join (values ('PRODUCTIVITY', 35), ('QUALITY', 35), ('TEAMWORK', 15), ('PUNCTUALITY', 15)) as w(code, weight) on w.code = c.code;

create table evaluation (
    id                bigserial primary key,
    user_id           bigint not null references app_user (id),
    template_id       bigint not null references evaluation_template (id),
    period_start      date not null,
    period_end        date not null,
    evaluator_id      bigint not null references app_user (id),
    status            varchar(12) not null default 'DRAFT'
                      check (status in ('DRAFT', 'SUBMITTED', 'ACKNOWLEDGED', 'CONTESTED', 'APPROVED')),
    total_percent     numeric(6, 2),
    manager_comment   varchar(1000),
    employee_comment  varchar(1000),
    hr_comment        varchar(1000),
    submitted_at      timestamptz,
    answered_at       timestamptz,
    approved_by       varchar(320),
    approved_at       timestamptz,
    created_at        timestamptz not null default now(),
    unique (user_id, period_start),
    check (period_end >= period_start)
);
create index evaluation_status on evaluation (status);

create table evaluation_score (
    evaluation_id  bigint not null references evaluation (id) on delete cascade,
    criterion_id   bigint not null references evaluation_criterion (id),
    weight         numeric(5, 2) not null,           -- copied from the template when the evaluation starts
    score          numeric(5, 2) check (score >= 0 and score <= 100),
    comment        varchar(500),
    primary key (evaluation_id, criterion_id)
);

-- 13th month settings.
insert into payroll_setting (key, value) values
    ('THIRTEENTH_ENABLED', 'true'),
    ('THIRTEENTH_BASE', 'AVERAGE_BASIC'),          -- AVERAGE_BASIC of the months worked, or LAST_BASIC
    ('THIRTEENTH_FALLBACK_PERCENT', '50'),         -- used when an employee has no approved evaluation that year
    ('THIRTEENTH_PAY_MONTH', '12'),
    ('THIRTEENTH_PRORATE', 'true');                -- months worked in the year / 12

insert into pay_element (code, name, kind, method, subject_to_cnss, taxable_ipr, system, display_order) values
    ('THIRTEENTH_MONTH', '13th month', 'EARNING', 'COMPUTED', true, true, true, 75);
