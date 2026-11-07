-- Leave management. Grade categories start here with their annual leave days; phase 2 adds grades and pay.
create table grade_category (
    id                bigserial primary key,
    code              varchar(32) not null unique,
    name              varchar(150) not null,
    annual_leave_days numeric(5, 2) not null check (annual_leave_days >= 0),
    active            boolean not null default true
);
-- Sample values for the pilot; HR replaces them with the collective agreement's.
insert into grade_category (code, name, annual_leave_days) values
    ('CAT1', 'Category 1: execution staff (sample)', 20),
    ('CAT2', 'Category 2: supervisors (sample)', 24),
    ('CAT3', 'Category 3: managers (sample)', 26);

alter table app_user add column grade_category_id bigint references grade_category (id);
alter table app_user add column hire_date date;

-- Rules from memory of the DRC Labour Code; HR must confirm them and can change them.
create table leave_type (
    id                bigserial primary key,
    code              varchar(32) not null unique,
    name              varchar(150) not null,
    paid              boolean not null,
    uses_balance      boolean not null default false,
    requires_document boolean not null default false,
    max_days          integer check (max_days > 0),
    active            boolean not null default true
);
insert into leave_type (code, name, paid, uses_balance, requires_document, max_days) values
    ('ANNUAL',       'Annual leave',                 true,  true,  false, null),
    ('SICK',         'Sick leave',                   true,  false, true,  null),
    ('MATERNITY',    'Maternity leave',              true,  false, true,  98),
    ('CIRCUMSTANCE', 'Family event (marriage, birth, death)', true, false, true, 5),
    ('UNPAID',       'Unpaid leave',                 false, false, false, null);

create table leave_request (
    id            bigserial primary key,
    user_id       bigint not null references app_user (id),
    leave_type_id bigint not null references leave_type (id),
    start_date    date not null,
    end_date      date not null check (end_date >= start_date),
    working_days  numeric(6, 2) not null check (working_days > 0),
    reason        varchar(500),
    status        varchar(10) not null default 'PENDING' check (status in ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    requested_by  varchar(320) not null,
    requested_at  timestamptz not null default now(),
    decided_by    varchar(320),
    decided_at    timestamptz,
    decision_note varchar(300)
);
create index leave_request_user on leave_request (user_id, start_date);

-- Every change to a balance is a row: yearly grants, approved leave taken, HR adjustments, payouts.
create table leave_ledger (
    id               bigserial primary key,
    user_id          bigint not null references app_user (id),
    leave_type_id    bigint not null references leave_type (id),
    entry_date       date not null,
    days             numeric(6, 2) not null,
    kind             varchar(12) not null check (kind in ('GRANT', 'TAKEN', 'RETURNED', 'ADJUSTMENT', 'PAYOUT')),
    year             integer,
    leave_request_id bigint references leave_request (id),
    note             varchar(300),
    created_by       varchar(320) not null,
    created_at       timestamptz not null default now()
);
create unique index leave_ledger_one_grant on leave_ledger (user_id, leave_type_id, year) where kind = 'GRANT';

insert into permission (code, category, description) values
    ('leave.request', 'attendance', 'Request leave');
insert into role_permission (role_id, permission_code)
select r.id, 'leave.request' from role r where r.code in ('EMPLOYEE', 'MANAGER', 'HR', 'FINANCE');
