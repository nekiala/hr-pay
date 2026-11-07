-- Phase 8: a holiday moved inside a closed month is settled in the next payroll, and privacy.

-- What a holiday change owes (or takes back) for a closed month, carried into a later payroll.
insert into pay_element (code, name, kind, method, subject_to_cnss, taxable_ipr, prorated, default_for_all, system, display_order) values
    ('HOLIDAY_ADJ',      'Holiday change, earlier month',        'EARNING', 'COMPUTED', true, true, false, false, true, 15),
    ('HOLIDAY_ADJ_LESS', 'Holiday change, earlier month (less)', 'EARNING', 'COMPUTED', true, true, false, false, true, 16);

create table holiday_adjustment (
    id            bigserial primary key,
    user_id       bigint not null references app_user (id),
    payslip_id    bigint not null references payslip (id),
    amount        numeric(14, 2) not null,
    currency      char(3) not null check (currency in ('USD', 'CDF')),
    target_month  date not null check (extract(day from target_month) = 1),
    note          varchar(300) not null,
    created_by    varchar(320) not null,
    created_at    timestamptz not null default now()
);
create index holiday_adjustment_user on holiday_adjustment (user_id, target_month);

-- The privacy notice each person has read at their first sign-in.
alter table app_user add column privacy_accepted_at timestamptz;

-- GPS positions of check-ins are kept this many days, then erased (0 keeps them).
insert into payroll_setting (key, value) values ('GPS_RETENTION_DAYS', '90');
