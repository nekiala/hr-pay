-- When an anniversary raise starts: the first day of the anniversary month, or of the month after.
alter table grade_category add column raise_timing varchar(20) not null default 'NEXT_MONTH'
    check (raise_timing in ('ANNIVERSARY_MONTH', 'NEXT_MONTH'));

-- One row per employee and anniversary, so a raise is never applied twice.
create table anniversary_raise (
    id                bigserial primary key,
    user_id           bigint not null references app_user (id),
    anniversary_date  date not null,
    years             int not null,
    effective_from    date not null,
    grade_id          bigint not null references grade (id),
    percent           numeric(5, 2) not null,
    grade_base        numeric(14, 2) not null,
    raise_amount      numeric(14, 2) not null,
    old_basic         numeric(14, 2) not null,
    new_basic         numeric(14, 2) not null,
    currency          char(3) not null,
    created_at        timestamptz not null default now(),
    notified_at       timestamptz,
    unique (user_id, anniversary_date)
);

-- Anniversaries before this date are not raised automatically (the salaries entered at go-live already include them).
insert into payroll_setting (key, value) values ('RAISES_FROM', '2026-11-01');
