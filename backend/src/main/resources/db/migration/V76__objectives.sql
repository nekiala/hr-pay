-- Individual objectives, scored in the evaluation.
--   The direct manager (or HR) writes a person's objectives for the year, with a weight each (they add up to 100),
--   shares them, and the person acknowledges them and may comment. A unit can have its own objectives, which a
--   person's objective may follow. Around mid-year the manager records a check-in (progress and comment) and, if
--   needed, a development need. At evaluation time a criterion of source OBJECTIVES scores each shared objective;
--   the weighted result is that criterion's score, with the criterion's weight set in the evaluation template.
--   Written objectives and their reviews are also the evidence of a dismissal for poor work (DRC Code art. 62, 72).

set local app.bypass = 'on';

alter table evaluation_criterion drop constraint if exists evaluation_criterion_source_check;
alter table evaluation_criterion add constraint evaluation_criterion_source_check
    check (source in ('MANAGER', 'ATTENDANCE', 'OBJECTIVES'));

create table objective (
    id                bigserial primary key,
    user_id           bigint references app_user (id),          -- a person's objective
    org_unit_id       bigint references org_unit (id),          -- or a unit's
    year              int not null check (year between 2000 and 2100),
    parent_id         bigint references objective (id) on delete set null,
    title             varchar(200) not null,
    measure           varchar(400),
    target            varchar(200),
    weight            numeric(5, 2) not null default 0 check (weight >= 0 and weight <= 100),
    due_date          date,
    status            varchar(12) not null default 'DRAFT'
                      check (status in ('DRAFT', 'SHARED', 'ACKNOWLEDGED', 'CANCELLED')),
    set_by            bigint references app_user (id),
    employee_comment  varchar(1000),
    acknowledged_at   timestamptz,
    progress          int check (progress between 0 and 100),
    checkin_comment   varchar(1000),
    checkin_at        timestamptz,
    checkin_by        bigint references app_user (id),
    development_need  varchar(400),
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    check ((user_id is null) <> (org_unit_id is null))
);
select hrpay_company_table('objective');
create index objective_person on objective (company_id, user_id, year);
create index objective_unit on objective (company_id, org_unit_id, year);

-- The objectives scored in one evaluation, with the weight they had when the evaluation started.
create table evaluation_objective (
    evaluation_id  bigint not null references evaluation (id) on delete cascade,
    objective_id   bigint not null references objective (id),
    weight         numeric(5, 2) not null,
    score          numeric(5, 2) check (score >= 0 and score <= 100),
    comment        varchar(500),
    primary key (evaluation_id, objective_id)
);
select hrpay_company_table('evaluation_objective');
