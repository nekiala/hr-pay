-- Phase 16: the employee's life in the company (hiring and departure checklists, equipment, a document vault,
-- letters and contracts signed with a one-time code, certificates on request) and the phone app (notifications,
-- push, check-ins made offline).

-- ---- documents ----------------------------------------------------------------------------------------------------

create table document_type (
    id                  bigserial primary key,
    code                varchar(40) not null,
    name                varchar(100) not null,
    has_expiry          boolean not null default false,
    remind_days         integer not null default 30 check (remind_days between 1 and 365),
    employee_can_upload boolean not null default false,
    employee_can_see    boolean not null default true,
    position            integer not null default 0,
    active              boolean not null default true
);
select hrpay_company_table('document_type');
create unique index document_type_code_uk on document_type (company_id, code);

create table employee_document (
    id            bigserial primary key,
    user_id       bigint not null references app_user (id),
    type_id       bigint not null references document_type (id),
    file_name     varchar(200) not null,
    content_type  varchar(100) not null,
    size_bytes    integer not null,
    data          bytea not null,
    sha256        char(64) not null,
    issued_on     date,
    expires_on    date,
    note          varchar(300),
    source        varchar(12) not null default 'HR' check (source in ('HR', 'EMPLOYEE', 'SIGNED', 'CERTIFICATE')),
    uploaded_by   varchar(320) not null,
    uploaded_at   timestamptz not null default now(),
    reminded_for  date,                       -- the expiry date a reminder was last sent for
    deleted_at    timestamptz,
    deleted_by    varchar(320)
);
select hrpay_company_table('employee_document');
create index employee_document_user on employee_document (user_id) where deleted_at is null;
create index employee_document_expiry on employee_document (expires_on) where deleted_at is null and expires_on is not null;

-- ---- hiring and departure checklists ------------------------------------------------------------------------------

create table checklist_template_item (
    id                bigserial primary key,
    kind              varchar(10) not null check (kind in ('HIRING', 'DEPARTURE')),
    position          integer not null default 0,
    label             varchar(200) not null,
    -- Ticked by itself when it happens: a document of this type is filed, all equipment is back, ...
    document_type_id  bigint references document_type (id),
    action            varchar(20) check (action in ('DOCUMENT', 'EQUIPMENT', 'CONTRACT_SIGNED', 'SETTLEMENT', 'ACCOUNT_CLOSED')),
    active            boolean not null default true
);
select hrpay_company_table('checklist_template_item');

create table employee_checklist (
    id            bigserial primary key,
    user_id       bigint not null references app_user (id),
    kind          varchar(10) not null check (kind in ('HIRING', 'DEPARTURE')),
    status        varchar(10) not null default 'OPEN' check (status in ('OPEN', 'DONE', 'CANCELLED')),
    due_on        date,
    started_by    varchar(320) not null,
    started_at    timestamptz not null default now(),
    closed_at     timestamptz,
    closed_by     varchar(320)
);
select hrpay_company_table('employee_checklist');
create unique index employee_checklist_open_uk on employee_checklist (user_id, kind) where status = 'OPEN';

create table employee_checklist_item (
    id                bigserial primary key,
    checklist_id      bigint not null references employee_checklist (id) on delete cascade,
    position          integer not null default 0,
    label             varchar(200) not null,
    document_type_id  bigint references document_type (id),
    action            varchar(20),
    done_at           timestamptz,
    done_by           varchar(320),
    note              varchar(300)
);
select hrpay_company_table('employee_checklist_item');

create table equipment (
    id           bigserial primary key,
    user_id      bigint not null references app_user (id),
    kind         varchar(60) not null,
    description  varchar(200),
    serial_no    varchar(80),
    issued_on    date not null,
    returned_on  date,
    returned_to  varchar(320),
    note         varchar(300),
    created_by   varchar(320) not null,
    created_at   timestamptz not null default now()
);
select hrpay_company_table('equipment');
create index equipment_user on equipment (user_id);

-- ---- letters and contracts, signed with a one-time code -----------------------------------------------------------

create table letter_template (
    id                  bigserial primary key,
    code                varchar(40) not null,
    name                varchar(100) not null,
    kind                varchar(10) not null check (kind in ('CONTRACT', 'LETTER')),
    body                text not null,
    requires_signature  boolean not null default true,
    document_type_id    bigint references document_type (id),
    active              boolean not null default true,
    updated_by          varchar(320),
    updated_at          timestamptz not null default now()
);
select hrpay_company_table('letter_template');
create unique index letter_template_code_uk on letter_template (company_id, code);

create table employee_letter (
    id                bigserial primary key,
    user_id           bigint not null references app_user (id),
    template_id       bigint references letter_template (id) on delete set null,
    kind              varchar(10) not null default 'LETTER' check (kind in ('CONTRACT', 'LETTER')),
    number            varchar(20),
    title             varchar(150) not null,
    body              text not null,
    requires_signature boolean not null default true,
    status            varchar(10) not null default 'DRAFT' check (status in ('DRAFT', 'SENT', 'SIGNED', 'DECLINED', 'CANCELLED')),
    pdf               bytea,
    sha256            char(64),
    sent_by           varchar(320),
    sent_by_name      varchar(200),
    sent_at           timestamptz,
    signed_pdf        bytea,
    signed_sha256     char(64),
    signed_at         timestamptz,
    signed_name       varchar(200),
    signed_ip         varchar(64),
    signed_agent      varchar(300),
    signed_channels   varchar(60),
    declined_reason   varchar(300),
    document_id       bigint references employee_document (id),
    created_by        varchar(320) not null,
    created_at        timestamptz not null default now()
);
select hrpay_company_table('employee_letter');
create unique index employee_letter_number_uk on employee_letter (company_id, number) where number is not null;
create index employee_letter_user on employee_letter (user_id);

-- ---- certificates on request ---------------------------------------------------------------------------------------

create table certificate_request (
    id            bigserial primary key,
    user_id       bigint not null references app_user (id),
    kind          varchar(6) not null check (kind in ('WORK', 'SALARY')),
    purpose       varchar(200),
    status        varchar(10) not null default 'PENDING' check (status in ('PENDING', 'ISSUED', 'REJECTED', 'CANCELLED')),
    number        varchar(20),
    document_id   bigint references employee_document (id),
    requested_by  varchar(320) not null,
    requested_at  timestamptz not null default now(),
    decided_by    varchar(320),
    decided_at    timestamptz,
    reason        varchar(300)
);
select hrpay_company_table('certificate_request');
create unique index certificate_request_number_uk on certificate_request (company_id, number) where number is not null;

-- ---- notifications and push -----------------------------------------------------------------------------------------

create table notification (
    id          bigserial primary key,
    user_id     bigint not null references app_user (id) on delete cascade,
    kind        varchar(40) not null,
    title       varchar(200) not null,
    body        varchar(500),
    link        varchar(200),
    created_at  timestamptz not null default now(),
    read_at     timestamptz,
    pushed_at   timestamptz                  -- sent to the person's phones, or nothing to send
);
select hrpay_company_table('notification');
create index notification_user on notification (user_id, created_at desc);
create index notification_unpushed on notification (created_at) where pushed_at is null;

create table push_subscription (
    id           bigserial primary key,
    user_id      bigint not null references app_user (id) on delete cascade,
    endpoint     varchar(1000) not null,
    p256dh       varchar(200),
    auth         varchar(100),
    user_agent   varchar(300),
    created_at   timestamptz not null default now(),
    last_ok_at   timestamptz,
    failures     integer not null default 0
);
select hrpay_company_table('push_subscription');
create unique index push_subscription_endpoint_uk on push_subscription (endpoint);

-- Reminders sent once a day at most: the kind and the day.
create table reminder_sent (
    user_id   bigint not null references app_user (id) on delete cascade,
    kind      varchar(30) not null,
    on_day    date not null,
    primary key (user_id, kind, on_day)
);
select hrpay_company_table('reminder_sent');

-- ---- check-ins made offline on the phone ------------------------------------------------------------------------

alter table site add column offline_checkin varchar(8) not null default 'REVIEW'
    check (offline_checkin in ('OFF', 'REVIEW', 'IN_AREA'));

alter table clock_event drop constraint clock_event_source_check;
alter table clock_event add constraint clock_event_source_check
    check (source in ('SELF', 'HR_MANUAL', 'AUTO_CLOSE', 'MISSION', 'BIOMETRIC', 'API', 'OFFLINE'));

create table offline_punch (
    id                     bigserial primary key,
    user_id                bigint not null references app_user (id),
    client_id              varchar(64) not null,
    type                   varchar(3) not null check (type in ('IN', 'OUT')),
    occurred_at            timestamptz not null,
    lat                    numeric(9, 6),
    lng                    numeric(9, 6),
    accuracy_m             integer,
    received_at            timestamptz not null default now(),
    outcome                varchar(8) not null check (outcome in ('RECORDED', 'REVIEW', 'REFUSED')),
    detail                 varchar(300),
    clock_event_id         bigint references clock_event (id),
    correction_request_id  bigint,
    unique (user_id, client_id)
);
select hrpay_company_table('offline_punch');

-- Each company gets its starting document types, checklists and letters once (see LifecycleDefaults).
alter table company add column lifecycle_seeded boolean not null default false;

insert into permission (code, category, description) values
    ('lifecycle.manage', 'user', 'Run hiring and departure checklists, keep employee documents and equipment, send letters and contracts for signature, issue certificates');
insert into role_permission (company_id, role_id, permission_code)
select r.company_id, r.id, 'lifecycle.manage' from role r where r.code in ('ADMIN', 'HR');
