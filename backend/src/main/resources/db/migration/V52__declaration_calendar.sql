-- Declarations with a calendar: when each declaration is due, declared and paid recorded apart, the penalty for
-- lateness estimated, reminders before and after the due date.

-- The Republic of the Congo's TUS could not be recorded as filed.
alter table declaration drop constraint declaration_kind_check;
alter table declaration add constraint declaration_kind_check check (kind in ('CNSS', 'IPR', 'INPP', 'ONEM', 'IERE', 'TUS'));

-- Paid apart from declared: the date, the amount, the bank or receipt reference and how it was paid.
alter table declaration add column paid_on date;
alter table declaration add column paid_amount numeric(18, 2) check (paid_amount is null or paid_amount >= 0);
alter table declaration add column paid_reference varchar(100);
alter table declaration add column paid_mode varchar(8) check (paid_mode is null or paid_mode in ('CASH', 'OV', 'OP', 'CHEQUE'));
alter table declaration add column paid_by varchar(320);
alter table declaration add column paid_at timestamptz;

-- When a declaration is due, and what lateness costs, for each kind: data, so a new law is a new row and not code.
-- The due days are in the month after the month paid; a quarterly declaration is due after the quarter's last month.
create table declaration_rule (
    id                            bigserial primary key,
    kind                          varchar(8) not null check (kind in ('CNSS', 'IPR', 'INPP', 'ONEM', 'IERE', 'TUS')),
    authority                     varchar(40) not null,
    declare_day                   int not null check (declare_day between 1 and 28),
    pay_day                       int not null check (pay_day between 1 and 28),
    quarterly_up_to_staff         int check (quarterly_up_to_staff is null or quarterly_up_to_staff > 0),
    late_declaration_percent      numeric(7, 3) check (late_declaration_percent is null or late_declaration_percent >= 0),
    late_declaration_percent_later numeric(7, 3) check (late_declaration_percent_later is null or late_declaration_percent_later >= 0),
    later_after_months            int check (later_after_months is null or later_after_months > 0),
    late_payment_percent_flat     numeric(7, 3) check (late_payment_percent_flat is null or late_payment_percent_flat >= 0),
    late_payment_percent_flat_later numeric(7, 3) check (late_payment_percent_flat_later is null or late_payment_percent_flat_later >= 0),
    late_payment_percent_month    numeric(7, 3) check (late_payment_percent_month is null or late_payment_percent_month >= 0),
    late_payment_percent_day      numeric(7, 3) check (late_payment_percent_day is null or late_payment_percent_day >= 0),
    daily_from_day                int check (daily_from_day is null or daily_from_day between 1 and 31),
    legal_basis                   varchar(300),
    verified                      boolean not null default false
);
select hrpay_company_table('declaration_rule');
alter table declaration_rule add constraint declaration_rule_kind_key unique (company_id, kind);

insert into declaration_rule (company_id, kind, authority, declare_day, pay_day, quarterly_up_to_staff, late_declaration_percent,
                              late_declaration_percent_later, later_after_months, late_payment_percent_flat,
                              late_payment_percent_flat_later, late_payment_percent_month,
                              late_payment_percent_day, daily_from_day, legal_basis, verified)
select c.id, r.kind, r.authority, r.declare_day, r.pay_day, r.quarterly, r.late_decl, r.late_decl_later, r.later_months,
       r.flat, r.flat_later, r.late_month, r.late_day, r.daily_from, r.basis, r.verified
from company c, (values
    ('CD', 'CNSS', 'CNSS', 15, 15, null::int, null::numeric, null::numeric, null::int, null::numeric, null::numeric, null::numeric, 0.5, 21,
     'Guichet unique (arrêté interministériel du 12 mai 2015): déclaration dans les 15 jours; majoration de 0,5 % par jour à partir du 21 (CNSS, à confirmer)', false),
    ('CD', 'IPR', 'DGI', 15, 15, null, 25, null, null, null, null, 2, null, null,
     'Loi 004/2003 art. 17 (déclaration et versement dans les 15 jours, même néant), art. 89 (+25 %), art. 91 (2 % par mois commencé)', true),
    ('CD', 'INPP', 'INPP', 15, 15, null, null, null, null, null, null, null, null, null,
     'Guichet unique (arrêté interministériel du 12 mai 2015): déclaration dans les 15 jours; pénalités à confirmer', false),
    ('CD', 'ONEM', 'ONEM', 15, 15, null, null, null, null, null, null, null, 0.5, null,
     'Guichet unique (arrêté interministériel du 12 mai 2015): déclaration dans les 15 jours; 0,5 % par jour de retard (à confirmer)', false),
    ('CD', 'IERE', 'DGI', 15, 15, null, 25, null, null, null, null, 2, null, null,
     'Loi 004/2003 art. 17, 89 et 91', true),
    ('CG', 'CNSS', 'CNSS', 15, 15, 20, null, null, null, null, null, null, null, null,
     'Déclaration mensuelle au plus tard le 15; trimestrielle jusqu''à 20 travailleurs (à confirmer)', false),
    ('CG', 'IPR', 'Impôts', 15, 15, 5, null, null, 2, 50, 100, null, null, null,
     'CGI art. 173 (versement dans les 15 jours du mois suivant; trimestriel jusqu''à 5 salariés), art. 177 bis (déclaration mensuelle), art. 512 (50 %, 100 % au-delà de deux mois)', true),
    ('CG', 'TUS', 'Impôts', 15, 15, 5, null, null, 2, 50, 100, null, null, null,
     'Comme la taxe forfaitaire sur les salaires qu''elle remplace: CGI art. 173 et 512 (à confirmer pour la TUS)', false)
) as r(country, kind, authority, declare_day, pay_day, quarterly, late_decl, late_decl_later, later_months, flat, flat_later, late_month,
       late_day, daily_from, basis, verified)
where c.country_code = r.country
  and exists (select 1 from statutory_rate_type s where s.company_id = c.id and s.code = 'CNSS_EMPLOYEE');

-- ONEM: 0.5 % of the gross pay from the October 2025 payroll (arrêté 028/CAB/MIN.ET/FMM/RK/09/2025 of 24 Sep 2025).
insert into statutory_rate (company_id, code, effective_from, value, note)
select c.id, 'ONEM', '2025-10-01', 0.5, 'Arrêté 028/CAB/MIN.ET/FMM/RK/09/2025 du 24 septembre 2025'
from company c
where c.country_code = 'CD'
  and exists (select 1 from statutory_rate_type s where s.company_id = c.id and s.code = 'ONEM')
  and not exists (select 1 from statutory_rate x where x.company_id = c.id and x.code = 'ONEM' and x.effective_from = '2025-10-01');
