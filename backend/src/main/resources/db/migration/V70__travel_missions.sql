-- Travel missions and the ordre de mission. A mission was a few hours away on one day (the employee still checks in).
-- A travel mission covers whole days, in the country or abroad: every scheduled day of it is worked and paid in full
-- without a check-in, and the employee carries a numbered ordre de mission that anyone can check with its QR code.
--   The employee on mission works under the employer's orders, only away from the site: the salary stays due.
--   Travel costs are the employer's. DRC Arrêté 146/CAB/MINETAT/MTEPS/01/2018 art. 17-1°: "les frais de voyage" and the
--   transport allowance are not remuneration for CNSS. IPR: O.-L. 69-009 art. 48-3° exempts allowances "dans la mesure
--   où ils ne revêtent pas un caractère exagéré"; a reimbursement of costs spent for the employer is not income.
--   Congo Loi 45-75 art. 82 (indemnité de déplacement) and art. 86 ("remboursement de frais").
--   So the per diem within the company's scale and the costs reimbursed on receipts stay off the payslip; a daily rate
--   above the scale puts the excess on the payslip, subject to CNSS and income tax. To confirm with the payroll rules
--   certifier.

set local app.bypass = 'on';

alter table mission drop constraint mission_status_check;
alter table mission add constraint mission_status_check
    check (status in ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'CLOSED'));

alter table mission
    add column kind              varchar(8)  not null default 'SHORT' check (kind in ('SHORT', 'TRAVEL')),
    add column scope             varchar(14) check (scope in ('DOMESTIC', 'INTERNATIONAL')),
    add column country_code      varchar(2)  references country (code),
    add column city              varchar(120),
    add column end_date          date,
    add column transport         varchar(12) check (transport in ('PLANE', 'ROAD', 'BOAT', 'TRAIN', 'COMPANY_CAR', 'OTHER')),
    add column costs_by          varchar(10) check (costs_by in ('COMPANY', 'HOST', 'SPONSOR', 'SHARED')),
    add column costs_note        varchar(300),
    add column lodging_by_company boolean not null default true,
    add column number            varchar(30),
    add column verify_token      uuid,
    add column first_approved_by varchar(320),
    add column first_approved_at timestamptz,
    add column zone              varchar(8) check (zone in ('DOMESTIC', 'AFRICA', 'WORLD')),
    add column per_diem_scale    numeric(12, 2),   -- the company's daily rate for the grade category and zone
    add column per_diem_rate     numeric(12, 2),   -- the rate of this mission (above the scale: the excess is pay)
    add column lodging_rate      numeric(12, 2),   -- per night, when the company does not book the hotel
    add column per_diem_currency char(3),
    add column amendment         int not null default 0,
    add column amendment_reason  varchar(300),
    add column report            text,
    add column report_at         timestamptz,
    add column closed_by         varchar(320),
    add column closed_at         timestamptz,
    add column excess_month      date,             -- the payslip month that carries the taxable excess
    add column created_by        varchar(320),
    add constraint mission_travel_fields check (kind = 'SHORT' or (scope is not null and country_code is not null
        and city is not null and end_date is not null and end_date >= work_date));

create unique index mission_number on mission (company_id, number) where number is not null;
create unique index mission_verify_token on mission (verify_token) where verify_token is not null;
create index mission_travel_dates on mission (user_id, work_date, end_date) where kind = 'TRAVEL';

-- The company's per diem: a daily amount for meals and small costs, by grade category (null: every category) and
-- zone, and a nightly amount for the hotel when the company does not book it.
create table mission_per_diem (
    id             bigserial primary key,
    category_id    bigint references grade_category (id),
    zone           varchar(8) not null check (zone in ('DOMESTIC', 'AFRICA', 'WORLD')),
    amount         numeric(12, 2) not null check (amount >= 0),
    lodging_amount numeric(12, 2) not null default 0 check (lodging_amount >= 0),
    currency       char(3) not null check (currency in ('USD', 'CDF', 'XAF')),
    updated_by     varchar(320),
    updated_at     timestamptz not null default now()
);
select hrpay_company_table('mission_per_diem');
create unique index mission_per_diem_uk on mission_per_diem (company_id, coalesce(category_id, 0), zone);

-- Costs the employee paid during the mission, with the receipt, accepted or refused by HR at settlement.
create table mission_expense (
    id            bigserial primary key,
    mission_id    bigint not null references mission (id),
    spent_on      date not null,
    category      varchar(10) not null check (category in ('TRANSPORT', 'LODGING', 'MEALS', 'VISA', 'OTHER')),
    description   varchar(200) not null,
    amount        numeric(12, 2) not null check (amount > 0),
    currency      char(3) not null,
    receipt       bytea,
    receipt_name  varchar(200),
    receipt_type  varchar(100),
    status        varchar(10) not null default 'SUBMITTED' check (status in ('SUBMITTED', 'ACCEPTED', 'REFUSED')),
    decided_by    varchar(320),
    decision_note varchar(300),
    created_by    varchar(320) not null,
    created_at    timestamptz not null default now()
);
select hrpay_company_table('mission_expense');
create index mission_expense_mission on mission_expense (mission_id);

-- Money for missions, outside the payroll: the advance before departure and the balance after the return are paid
-- through payment files (one run per month, like salary advances); what the employee gives back is recorded.
create table mission_pay_run (
    id    bigserial primary key,
    month date not null
);
select hrpay_company_table('mission_pay_run');
create unique index mission_pay_run_month on mission_pay_run (company_id, month);

create table mission_payment (
    id          bigserial primary key,
    run_id      bigint references mission_pay_run (id),
    mission_id  bigint not null references mission (id),
    user_id     bigint not null references app_user (id),
    kind        varchar(10) not null check (kind in ('ADVANCE', 'BALANCE', 'REFUND')),
    number      varchar(30) not null,
    amount      numeric(12, 2) not null check (amount > 0),
    currency    char(3) not null,
    status      varchar(10) not null check (status in ('APPROVED', 'PAID', 'RECEIVED', 'CANCELLED')),
    note        varchar(300),
    unit_id     bigint,
    created_by  varchar(320) not null,
    created_at  timestamptz not null default now(),
    check ((kind = 'REFUND') = (run_id is null))
);
select hrpay_company_table('mission_payment');
create unique index mission_payment_number on mission_payment (company_id, number);
create index mission_payment_mission on mission_payment (mission_id);

alter table payment_batch add column mission_run_id bigint references mission_pay_run (id);
alter table payment_batch drop constraint payment_batch_source;
alter table payment_batch add constraint payment_batch_source
    check (num_nonnulls(run_id, quinzaine_run_id, advance_run_id, mission_run_id) = 1);
alter table payment_line add column mission_payment_id bigint unique references mission_payment (id);

insert into payroll_setting (company_id, key, value)
select c.id, s.key, s.value
from company c cross join (values
    ('MISSION_TRANSPORT_ON_TRAVEL', 'STOP'),  -- STOP: no transport allowance on travel mission days; PAY: paid as usual
    ('MISSION_SECOND_APPROVAL', 'INTERNATIONAL'), -- INTERNATIONAL, ALL or NONE: HR approves after the unit head
    ('MISSION_REPORT_DAYS', '10')             -- days after the return to file the report and expenses
) as s(key, value)
on conflict do nothing;

-- The per diem above the company's scale is pay: on the payslip, with CNSS and income tax.
insert into pay_element (company_id, code, name, kind, method, subject_to_cnss, taxable_ipr, prorated, default_for_all,
                         system, display_order)
select c.id, 'MISSION_EXCESS',
       case when c.language = 'fr' then 'Indemnité de mission (excédent imposable)' else 'Mission allowance (taxable excess)' end,
       'EARNING', 'COMPUTED', true, true, false, false, true, 75
from company c
where exists (select 1 from pay_element p where p.company_id = c.id and p.code = 'BASIC')
  and not exists (select 1 from pay_element p where p.company_id = c.id and p.code = 'MISSION_EXCESS');

insert into permission (code, category, description) values
    ('mission.manage', 'attendance', 'Create travel missions, set the per diem, pay advances and settle missions')
on conflict do nothing;
insert into role_permission (company_id, role_id, permission_code)
select r.company_id, r.id, 'mission.manage' from role r where r.code in ('ADMIN', 'HR')
on conflict do nothing;
