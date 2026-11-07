-- Phase 1: check-in and check-out.

alter table site add column qr_required boolean not null default false;
-- Secret for the rotating QR code shown on the site's kiosk screen.
alter table site add column kiosk_key varchar(64) not null default replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', '');

-- A browser registered by an administrator as a shared office device (for employees without a smartphone).
create table trusted_device (
    id          bigserial primary key,
    site_id     bigint       not null references site (id) on delete cascade,
    name        varchar(100) not null,
    token_hash  varchar(64)  not null unique,
    active      boolean      not null default true,
    created_by  varchar(254) not null,
    created_at  timestamptz  not null default now(),
    last_used_at timestamptz
);

-- One-time codes sent by email or WhatsApp.
create table otp_challenge (
    id           uuid primary key,
    user_id      bigint       not null references app_user (id) on delete cascade,
    purpose      varchar(16)  not null,
    code_hash    varchar(100) not null,
    expires_at   timestamptz  not null,
    attempts     integer      not null default 0,
    context      jsonb,
    created_at   timestamptz  not null default now(),
    consumed_at  timestamptz
);
create index otp_challenge_user_idx on otp_challenge (user_id, created_at desc);

-- Every message the app sends, kept for audit and for reading codes in development.
create table outbox_message (
    id          bigserial primary key,
    user_id     bigint references app_user (id) on delete set null,
    channel     varchar(16)  not null check (channel in ('EMAIL', 'WHATSAPP')),
    recipient   varchar(254) not null,
    subject     varchar(255),
    body        text         not null,
    status      varchar(16)  not null check (status in ('SENT', 'FAILED', 'LOGGED')),
    error       varchar(500),
    created_at  timestamptz  not null default now()
);
create index outbox_message_created_idx on outbox_message (created_at desc);

create table clock_event (
    id                bigserial primary key,
    user_id           bigint       not null references app_user (id),
    type              varchar(8)   not null check (type in ('IN', 'OUT')),
    occurred_at       timestamptz  not null,
    work_date         date         not null,
    site_id           bigint references site (id),
    source            varchar(16)  not null check (source in ('SELF', 'HR_MANUAL', 'AUTO_CLOSE', 'MISSION')),
    ip                varchar(64),
    lat               numeric(9, 6),
    lng               numeric(9, 6),
    accuracy_m        integer,
    qr_verified       boolean      not null default false,
    trusted_device_id bigint references trusted_device (id),
    mission_id        bigint,
    note              varchar(500),
    created_by        varchar(254) not null,
    created_at        timestamptz  not null default now(),
    voided_at         timestamptz,
    voided_by         varchar(254),
    void_reason       varchar(500)
);
create index clock_event_user_time_idx on clock_event (user_id, occurred_at);
create index clock_event_work_date_idx on clock_event (work_date);
