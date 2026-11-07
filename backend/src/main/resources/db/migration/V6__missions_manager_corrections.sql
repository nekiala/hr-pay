-- Direct manager, used for team approvals (missions, overtime, leave, evaluations).
alter table app_user add column manager_id bigint references app_user (id);
alter table app_user add constraint app_user_not_own_manager check (manager_id is null or manager_id <> id);

insert into permission (code, category, description) values
    ('mission.request',     'attendance', 'Request a mission (work away from the site)'),
    ('mission.approve.all', 'attendance', 'Approve missions for all employees');

-- Staff in every role but Administrator are employees too: they check in, see their month and request missions.
insert into role_permission (role_id, permission_code)
select r.id, m.permission_code
from role r
join (values
    ('EMPLOYEE', 'mission.request'),
    ('MANAGER', 'mission.request'), ('MANAGER', 'attendance.read.own'), ('MANAGER', 'salary.read.own'),
    ('HR', 'mission.request'), ('HR', 'attendance.read.own'), ('HR', 'salary.read.own'), ('HR', 'mission.approve.all'),
    ('FINANCE', 'mission.request'), ('FINANCE', 'attendance.read.own'), ('FINANCE', 'salary.read.own')
) as m(role_code, permission_code) on m.role_code = r.code
on conflict do nothing;

create table mission (
    id            bigserial primary key,
    user_id       bigint not null references app_user (id),
    work_date     date not null,
    start_time    time not null,
    end_time      time not null check (end_time > start_time),
    location      varchar(200) not null,
    purpose       varchar(500) not null,
    status        varchar(12) not null default 'PENDING' check (status in ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    requested_by  varchar(320) not null,
    requested_at  timestamptz not null default now(),
    decided_by    varchar(320),
    decided_at    timestamptz,
    decision_note varchar(300)
);
create index mission_user_date on mission (user_id, work_date);

alter table clock_event add constraint clock_event_mission_fk foreign key (mission_id) references mission (id);
