-- Phase 2 master data: grades within grade categories, occupations, contracts and personal/payment details.

alter table grade_category add column anniversary_raise_percent numeric(5, 2) not null default 0
    check (anniversary_raise_percent >= 0 and anniversary_raise_percent <= 100);
update grade_category set anniversary_raise_percent = case code when 'CAT1' then 2 when 'CAT2' then 2.5 else 3 end;

create table grade (
    id           bigserial primary key,
    category_id  bigint not null references grade_category (id),
    code         varchar(32) not null unique,
    name         varchar(150) not null,
    base_salary  numeric(14, 2) not null check (base_salary >= 0),
    currency     char(3) not null check (currency in ('USD', 'CDF')),
    active       boolean not null default true
);
-- Sample grade table; HR replaces it with the collective agreement's.
insert into grade (category_id, code, name, base_salary, currency)
select c.id, g.code, g.name, g.base, 'USD'
from grade_category c join (values
    ('CAT1', 'G1A', 'Grade 1A (sample)', 350), ('CAT1', 'G1B', 'Grade 1B (sample)', 420),
    ('CAT2', 'G2A', 'Grade 2A (sample)', 700), ('CAT2', 'G2B', 'Grade 2B (sample)', 850),
    ('CAT3', 'G3A', 'Grade 3A (sample)', 1500), ('CAT3', 'G3B', 'Grade 3B (sample)', 2000)
) as g(category, code, name, base) on g.category = c.code;

create table occupation (
    id     bigserial primary key,
    code   varchar(32) not null unique,
    name   varchar(150) not null,
    active boolean not null default true
);
insert into occupation (code, name) values
    ('ACCOUNTANT', 'Accountant'), ('HR_OFFICER', 'HR officer'), ('DRIVER', 'Driver'),
    ('ENGINEER', 'Engineer'), ('RECEPTIONIST', 'Receptionist'), ('MANAGER', 'Manager');

-- Contract history: the contract in force on a date is the latest one started on or before it.
create table employee_contract (
    id             bigserial primary key,
    user_id        bigint not null references app_user (id),
    contract_type  varchar(12) not null check (contract_type in ('PERMANENT', 'FIXED_TERM', 'INTERN', 'CONSULTANT')),
    start_date     date not null,
    end_date       date,
    grade_id       bigint not null references grade (id),
    occupation_id  bigint references occupation (id),
    expatriate     boolean not null default false,
    note           varchar(300),
    created_by     varchar(320) not null,
    created_at     timestamptz not null default now(),
    unique (user_id, start_date),
    check (end_date is null or end_date >= start_date)
);

create table employee_profile (
    user_id                bigint primary key references app_user (id),
    birth_date             date,
    gender                 varchar(6) check (gender in ('FEMALE', 'MALE')),
    nationality            varchar(60),
    marital_status         varchar(10) not null default 'SINGLE'
                           check (marital_status in ('SINGLE', 'MARRIED', 'DIVORCED', 'WIDOWED')),
    cnss_number            varchar(40),
    tax_number             varchar(40),
    payment_method         varchar(14) not null default 'BANK' check (payment_method in ('BANK', 'MOBILE_MONEY', 'CASH')),
    bank_name              varchar(100),
    bank_account           varchar(60),
    mobile_money_operator  varchar(40),
    mobile_money_number    varchar(20),
    updated_by             varchar(320),
    updated_at             timestamptz not null default now()
);

-- Spouse and children: they drive family allowances and the tax reduction for dependants.
create table dependant (
    id            bigserial primary key,
    user_id       bigint not null references app_user (id),
    full_name     varchar(150) not null,
    relationship  varchar(8) not null check (relationship in ('SPOUSE', 'CHILD')),
    birth_date    date,
    in_charge     boolean not null default true,
    note          varchar(300)
);
create index dependant_user on dependant (user_id);
