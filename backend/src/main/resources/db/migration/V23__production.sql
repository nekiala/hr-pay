-- Phase 9: sessions and sign-in lockout shared by every API server, and the record of database backups.

-- Spring Session (JDBC), schema as shipped with spring-session-jdbc for PostgreSQL.
create table spring_session (
    primary_id            char(36)     not null,
    session_id            char(36)     not null,
    creation_time         bigint       not null,
    last_access_time      bigint       not null,
    max_inactive_interval int          not null,
    expiry_time           bigint       not null,
    principal_name        varchar(100),
    constraint spring_session_pk primary key (primary_id)
);
create unique index spring_session_ix1 on spring_session (session_id);
create index spring_session_ix2 on spring_session (expiry_time);
create index spring_session_ix3 on spring_session (principal_name);

create table spring_session_attributes (
    session_primary_id char(36)     not null,
    attribute_name     varchar(200) not null,
    attribute_bytes    bytea        not null,
    constraint spring_session_attributes_pk primary key (session_primary_id, attribute_name),
    constraint spring_session_attributes_fk foreign key (session_primary_id) references spring_session (primary_id) on delete cascade
);

-- Failed password attempts, so the 15-minute lockout holds across servers and restarts.
create table login_failure (
    id        bigserial primary key,
    email     varchar(200) not null,
    failed_at timestamptz  not null
);
create index login_failure_email on login_failure (email, failed_at);

-- One row per run of the backup job (deploy/backup), written by the job itself.
create table backup_run (
    id          bigserial primary key,
    started_at  timestamptz  not null default now(),
    finished_at timestamptz,
    file_name   varchar(300),
    size_bytes  bigint,
    status      varchar(16)  not null default 'RUNNING' check (status in ('RUNNING', 'OK', 'FAILED')),
    offsite     boolean      not null default false,
    message     varchar(1000)
);
create index backup_run_started on backup_run (started_at desc);

-- Alerts sent to administrators (errors, missing backups), kept to avoid sending the same one twice.
create table system_alert (
    id         bigserial primary key,
    kind       varchar(40)   not null,
    fingerprint varchar(200) not null,
    summary    varchar(500)  not null,
    detail     text,
    first_at   timestamptz   not null default now(),
    last_at    timestamptz   not null default now(),
    occurrences int          not null default 1,
    notified_at timestamptz
);
create unique index system_alert_fingerprint on system_alert (kind, fingerprint);

-- Scheduled jobs run on one server at a time: a server takes the lock by moving locked_until forward.
create table job_lock (
    name         varchar(60) primary key,
    locked_until timestamptz not null default now(),
    locked_at    timestamptz,
    locked_by    varchar(120),
    last_ok_at   timestamptz,
    last_error   varchar(1000),
    last_error_at timestamptz
);

insert into permission (code, category, description) values
    ('system.monitor', 'audit', 'See the system status, backups and alerts, and receive alert emails');
insert into role_permission (role_id, permission_code)
select r.id, 'system.monitor' from role r where r.code = 'ADMIN'
on conflict do nothing;
