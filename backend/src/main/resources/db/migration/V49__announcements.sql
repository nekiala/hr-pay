-- HR announcements: a notice for everyone, a unit (with the units below it) or a site. Everyone addressed sees it in
-- HR Pay (the bell and the Announcements page). People who agreed to WhatsApp get a WhatsApp notice when HR asks for
-- it (a fixed-words template with a link: the text itself is read in HR Pay); the others get it by email.

set local app.bypass = 'on';

create table announcement (
    id              bigserial primary key,
    title           varchar(120) not null,
    body            text not null,
    audience        varchar(8) not null check (audience in ('ALL', 'UNIT', 'SITE')),
    unit_id         bigint references org_unit (id),
    site_id         bigint references site (id),
    whatsapp        boolean not null,              -- HR chose to send the WhatsApp notice
    recipients      int not null,
    by_whatsapp     int not null,
    by_email        int not null,
    created_by      varchar(320) not null,
    created_at      timestamptz not null default now(),
    check (audience <> 'UNIT' or unit_id is not null),
    check (audience <> 'SITE' or site_id is not null)
);
select hrpay_company_table('announcement');

create table announcement_recipient (
    announcement_id bigint not null references announcement (id) on delete cascade,
    user_id         bigint not null references app_user (id),
    channel         varchar(8) not null check (channel in ('WHATSAPP', 'EMAIL', 'APP')),   -- APP: no email address
    emailed_at      timestamptz,                   -- EMAIL: sent by the scheduled sender
    primary key (announcement_id, user_id)
);
select hrpay_company_table('announcement_recipient');
create index announcement_recipient_user on announcement_recipient (user_id);

insert into permission (code, category, description) values
    ('announcement.send', 'users', 'Send announcements to employees (in HR Pay, by WhatsApp and by email)');
insert into role_permission (company_id, role_id, permission_code)
select r.company_id, r.id, 'announcement.send' from role r where r.code in ('ADMIN', 'HR');
