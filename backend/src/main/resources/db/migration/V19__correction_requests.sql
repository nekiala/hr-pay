-- Phase 7: attendance correction requests. An employee asks, a head of their branch proposes (or HR goes straight
-- ahead), and HR applies it, which adds or voids a clock entry the usual audited way.
create table correction_request (
    id             bigserial primary key,
    user_id        bigint not null references app_user (id),
    work_date      date not null,
    action         varchar(8) not null check (action in ('ADD', 'VOID')),
    event_type     varchar(3) check (event_type in ('IN', 'OUT')),   -- ADD: which entry is missing
    local_time     time,                                             -- ADD: at what time (site time)
    event_id       bigint references clock_event (id),               -- VOID: the wrong entry
    reason         varchar(500) not null,
    status         varchar(10) not null default 'PENDING'
                   check (status in ('PENDING', 'PROPOSED', 'APPLIED', 'REJECTED', 'CANCELLED')),
    requested_by   varchar(320) not null,
    requested_at   timestamptz not null default now(),
    proposed_by    varchar(320),
    proposed_at    timestamptz,
    decided_by     varchar(320),
    decided_at     timestamptz,
    decision_note  varchar(300),
    check ((action = 'ADD' and event_type is not null and local_time is not null) or (action = 'VOID' and event_id is not null))
);
create index correction_request_status on correction_request (status, work_date);
create index correction_request_user on correction_request (user_id, work_date);

insert into permission (code, category, description) values
    ('attendance.correct.request', 'attendance', 'Ask for a correction of own attendance');
insert into role_permission (role_id, permission_code)
select r.id, 'attendance.correct.request' from role r where r.code in ('EMPLOYEE', 'MANAGER', 'HR', 'FINANCE');
