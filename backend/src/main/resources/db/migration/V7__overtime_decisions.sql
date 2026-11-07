-- One decision per employee and work day. Approved minutes never exceed the overtime computed for the day.
create table overtime_decision (
    id               bigserial primary key,
    user_id          bigint not null references app_user (id),
    work_date        date not null,
    computed_minutes integer not null check (computed_minutes > 0),
    approved_minutes integer not null check (approved_minutes >= 0),
    kind             varchar(10) not null check (kind in ('WEEKDAY', 'REST_DAY', 'HOLIDAY')),
    status           varchar(10) not null check (status in ('APPROVED', 'REJECTED')),
    note             varchar(300),
    decided_by       varchar(320) not null,
    decided_at       timestamptz not null default now(),
    unique (user_id, work_date),
    check (approved_minutes <= computed_minutes)
);
