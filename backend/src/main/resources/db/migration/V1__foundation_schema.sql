-- Phase 0: identity, RBAC, sites and audit log.

create table permission (
    code        varchar(64) primary key,
    category    varchar(32)  not null,
    description varchar(255) not null
);

create table role (
    id          bigserial primary key,
    code        varchar(32)  not null unique,
    name        varchar(100) not null,
    description varchar(255),
    system_role boolean      not null default false,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now()
);

create table role_permission (
    role_id         bigint      not null references role (id) on delete cascade,
    permission_code varchar(64) not null references permission (code),
    primary key (role_id, permission_code)
);

create table site (
    id             bigserial primary key,
    code           varchar(32)  not null unique,
    name           varchar(150) not null,
    country_code   varchar(2)   not null default 'CD',
    time_zone      varchar(64)  not null,
    center_lat     numeric(9, 6),
    center_lng     numeric(9, 6),
    radius_m       integer,
    gps_required   boolean      not null default true,
    max_accuracy_m integer      not null default 100,
    active         boolean      not null default true,
    created_at     timestamptz  not null default now(),
    updated_at     timestamptz  not null default now(),
    constraint site_geofence_complete check (
        (center_lat is null and center_lng is null and radius_m is null)
        or (center_lat is not null and center_lng is not null and radius_m > 0)
    ),
    constraint site_gps_needs_geofence check (not gps_required or radius_m is not null)
);

create table site_ip_range (
    id      bigserial primary key,
    site_id bigint       not null references site (id) on delete cascade,
    cidr    varchar(64)  not null,
    label   varchar(100),
    unique (site_id, cidr)
);

create table app_user (
    id                   bigserial primary key,
    email                varchar(254) not null,
    registration_no      varchar(32)  not null,
    phone_e164           varchar(16)  not null,
    first_name           varchar(100) not null,
    last_name            varchar(100) not null,
    password_hash        varchar(100),
    status               varchar(16)  not null default 'ACTIVE' check (status in ('ACTIVE', 'DISABLED')),
    site_id              bigint references site (id),
    must_change_password boolean      not null default false,
    last_login_at        timestamptz,
    created_at           timestamptz  not null default now(),
    updated_at           timestamptz  not null default now(),
    constraint app_user_phone_e164 check (phone_e164 ~ '^\+[1-9][0-9]{7,14}$')
);
create unique index app_user_email_uk on app_user (lower(email));
create unique index app_user_registration_no_uk on app_user (upper(registration_no));
create unique index app_user_phone_uk on app_user (phone_e164);

create table user_role (
    user_id bigint not null references app_user (id) on delete cascade,
    role_id bigint not null references role (id),
    primary key (user_id, role_id)
);

create table audit_log (
    id          bigserial primary key,
    occurred_at timestamptz  not null default now(),
    actor       varchar(254),
    action      varchar(64)  not null,
    entity_type varchar(64),
    entity_id   varchar(64),
    details     jsonb,
    ip          varchar(64)
);
create index audit_log_occurred_at_idx on audit_log (occurred_at desc);
create index audit_log_entity_idx on audit_log (entity_type, entity_id);

-- The audit log is append-only: no row can be changed or removed.
create function audit_log_block_changes() returns trigger language plpgsql as $$
begin
    raise exception 'audit_log is append-only';
end;
$$;

create trigger audit_log_no_update before update or delete on audit_log
    for each row execute function audit_log_block_changes();
create trigger audit_log_no_truncate before truncate on audit_log
    for each statement execute function audit_log_block_changes();
