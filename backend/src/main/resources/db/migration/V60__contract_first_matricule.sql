-- No work, hire date or salary without a contract (DRC Code du travail art. 36 and 44: the written contract sets the
-- start date and the pay). A few accounts are not staff at all (the provider's administrator, an outside accounting
-- firm, a consultant): an administrator marks them "not an employee", and they never work or get paid here.
alter table app_user add column not_employee boolean not null default false;

-- The employee ID (matricule) can be given by HR Pay from a format the administrator sets, for example
-- NTP-{YY}-{SEQ:4}. The counter goes on for ever, or starts again at 1 each year.
create table matricule_format (
    id           bigserial primary key,
    enabled      boolean      not null default false,
    pattern      varchar(60)  not null default '{SEQ:5}',
    reset_yearly boolean      not null default false,
    next_seq     integer      not null default 1 check (next_seq > 0),
    seq_year     integer,                 -- the year next_seq counts in, when it starts again each year
    updated_by   varchar(320),
    updated_at   timestamptz  not null default now()
);
select hrpay_company_table('matricule_format');
alter table matricule_format add constraint matricule_format_one unique (company_id);

insert into permission (code, category, description) values
    ('matricule.manage', 'users', 'Set how HR Pay numbers new employees (matricule format)');
insert into role_permission (company_id, role_id, permission_code)
select r.company_id, r.id, 'matricule.manage' from role r where r.code = 'ADMIN';

-- An account whose only role is Administrator and that has never had a contract is a system account, not staff.
update app_user u set not_employee = true
where not exists (select 1 from employee_contract c where c.user_id = u.id)
  and exists (select 1 from user_role ur join role r on r.id = ur.role_id where ur.user_id = u.id and r.code = 'ADMIN')
  and not exists (select 1 from user_role ur join role r on r.id = ur.role_id where ur.user_id = u.id and r.code <> 'ADMIN');
