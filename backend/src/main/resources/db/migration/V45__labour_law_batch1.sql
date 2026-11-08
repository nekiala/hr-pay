-- Labour law and minimum wage, first batch of corrections (gap analysis of 8 November 2026). Only the rules the official
-- texts state clearly; the rest waits for the payroll rules certifier.
--
-- DR Congo
--   Décret n° 25/22 du 30 mai 2025 (JO 28 octobre 2025): SMIG 14 500 FC a day from the May 2025 payroll and 21 500 FC from
--   January 2026 (art. 3); the minimum of each category is the SMIG times its coefficient (art. 4, annex 2: 100 for the
--   ordinary labourer up to 1 000 for the cadre de collaboration IV); a month counts 26 days (art. 7); family allowances
--   at least 1/27 of the daily SMIG per child and per day (art. 5).
--   Code du travail: annual leave is paid (art. 142); leave for family events is given per event, at most 15 paid working
--   days a year (art. 146); nursing breaks of two half hours a day count as working time (art. 132).
--   DGI: the IPR/IRPP withheld is at least 2 500 FC a month.
-- Republic of the Congo
--   Loi n° 42-2025 du 31 décembre 2025 (loi de finances 2026), CGI art. 116 G: ITS per part and per year, flat 1 200 FCFA up
--   to 615 000, then 10 % to 1 500 000, 15 % to 3 500 000, 20 % to 5 000 000 and 30 % above; at least 1 200 FCFA a year when
--   the pay is below the SMIG; art. 116 A the net taxable pay is rounded down to the thousand; art. 115 allowances are
--   taxable, housing included.
--   Code du travail: 26 working days of annual leave a year (2.1667 a month).
--
-- Existing grade base salaries are not changed: a higher base would raise the pay of people whose agreed net is reached
-- with a complement. The payslip now warns when a basic is below the legal minimum of its grade.

set local app.bypass = 'on';

alter table grade add column smig_coefficient numeric(6, 2) check (smig_coefficient > 0);
alter table leave_type add column circumstance boolean not null default false;
alter table employee_profile add column nursing_break_until date;

-- ---- DR Congo ----------------------------------------------------------------------------------------------------

insert into statutory_rate (company_id, code, effective_from, value, note)
select c.id, 'SMIG_DAILY', r.effective_from, r.value, 'Décret 25/22 art. 3'
from company c, (values (date '2025-05-01', 14500), (date '2026-01-01', 21500)) as r(effective_from, value)
where c.country_code = 'CD'
  and exists (select 1 from statutory_rate_type s where s.company_id = c.id and s.code = 'SMIG_DAILY')
  and not exists (select 1 from statutory_rate x where x.company_id = c.id and x.code = 'SMIG_DAILY'
                  and x.effective_from = r.effective_from);

update grade g set smig_coefficient = k.coefficient
from company c, (values ('E1A', 100), ('E1B', 116), ('E2', 133), ('E3A', 154), ('E3B', 178), ('E3C', 206), ('E4A', 237),
                        ('E4B', 274), ('E5', 317), ('M1', 366), ('M2', 422), ('M3', 488), ('M4', 564), ('C1', 651),
                        ('C2', 752), ('C3', 868), ('C4', 1000)) as k(code, coefficient)
where c.id = g.company_id and c.country_code = 'CD' and g.code = k.code and g.smig_coefficient is null;

insert into statutory_rate_type (company_id, code, name, unit, description, display_order)
select c.id, 'IPR_MIN_MONTHLY',
       case when c.language = 'fr' then 'IPR minimum par mois' else 'IPR minimum per month' end,
       'CDF',
       case when c.language = 'fr' then 'L''impôt retenu n''est jamais inférieur à ce montant quand il est dû.'
            else 'The tax withheld is never less than this amount when tax is due.' end, 55
from company c
where c.country_code = 'CD'
  and exists (select 1 from statutory_rate_type s where s.company_id = c.id and s.code = 'CNSS_EMPLOYEE')
  and not exists (select 1 from statutory_rate_type s where s.company_id = c.id and s.code = 'IPR_MIN_MONTHLY');

insert into statutory_rate (company_id, code, effective_from, value, note)
select c.id, 'IPR_MIN_MONTHLY', '2026-01-01', 2500, 'DGI, IRPP sur les revenus salariaux'
from company c
where c.country_code = 'CD'
  and exists (select 1 from statutory_rate_type s where s.company_id = c.id and s.code = 'IPR_MIN_MONTHLY')
  and not exists (select 1 from statutory_rate x where x.company_id = c.id and x.code = 'IPR_MIN_MONTHLY');

update payroll_setting p set value = 'true'
from company c where c.id = p.company_id and c.country_code = 'CD' and p.key = 'LEAVE_ANNUAL_PAID';

-- Family events: one leave type per event (art. 146), the single 5-day type is retired.
insert into leave_type (company_id, code, name, paid, uses_balance, requires_document, max_days, active, pay_percent, circumstance)
select c.id, e.code, case when c.language = 'fr' then e.name_fr else e.name_en end, true, false, true, e.days, true, 100, true
from company c, (values
    ('FAMILY_MARRIAGE',       'Family event: own marriage',                          'Circonstance : mariage du travailleur',               2),
    ('FAMILY_BIRTH',          'Family event: spouse gives birth',                    'Circonstance : accouchement de l''épouse',             2),
    ('FAMILY_DEATH_CLOSE',    'Family event: death of spouse, parent or child',      'Circonstance : décès du conjoint ou d''un parent au 1er degré', 4),
    ('FAMILY_CHILD_MARRIAGE', 'Family event: marriage of a child',                   'Circonstance : mariage d''un enfant',                  1),
    ('FAMILY_DEATH_OTHER',    'Family event: death of a brother, sister or grandparent', 'Circonstance : décès d''un parent au 2e degré',   2)
) as e(code, name_en, name_fr, days)
where c.country_code = 'CD'
  and exists (select 1 from leave_type t where t.company_id = c.id and t.code = 'ANNUAL')
  and not exists (select 1 from leave_type t where t.company_id = c.id and t.code = e.code);

update leave_type t set active = false
from company c where c.id = t.company_id and c.country_code = 'CD' and t.code = 'CIRCUMSTANCE';

insert into payroll_setting (company_id, key, value)
select c.id, 'LEAVE_CIRCUMSTANCE_YEAR_MAX', '15'
from company c
where c.country_code = 'CD' and exists (select 1 from leave_type t where t.company_id = c.id and t.code = 'ANNUAL')
on conflict do nothing;

-- ---- Republic of the Congo ---------------------------------------------------------------------------------------

insert into ipr_bracket (company_id, effective_from, lower_bound, upper_bound, rate_percent, created_by)
select c.id, '2026-01-01', b.lower_bound, b.upper_bound, b.rate, 'system'
from company c, (values (0, 615000, 0), (615000, 1500000, 10), (1500000, 3500000, 15), (3500000, 5000000, 20),
                        (5000000, null, 30)) as b(lower_bound, upper_bound, rate)
where c.country_code = 'CG'
  and exists (select 1 from ipr_bracket x where x.company_id = c.id)
  and not exists (select 1 from ipr_bracket x where x.company_id = c.id and x.effective_from = '2026-01-01');

insert into statutory_rate_type (company_id, code, name, unit, description, display_order)
select c.id, t.code, case when c.language = 'fr' then t.name_fr else t.name_en end, 'XAF',
       case when c.language = 'fr' then t.description_fr else t.description_en end, t.display_order
from company c, (values
    ('ITS_FIRST_BAND_FLAT', 'ITS, flat amount of the first band, per part and year', 'ITS, montant fixe de la première tranche, par part et par an',
     'Tax per part on the first band of the yearly pay, whatever the amount in it (CGI art. 116 G).',
     'Impôt par part sur la première tranche du revenu annuel, quel qu''en soit le montant (CGI art. 116 G).', 85),
    ('ITS_MINIMUM_YEARLY', 'ITS minimum per year when the pay is below the SMIG', 'ITS minimum par an quand le salaire est inférieur au SMIG',
     'Tax a year, at least, of a person paid below the minimum wage (CGI art. 116 G).',
     'Impôt annuel minimum d''une personne payée en dessous du SMIG (CGI art. 116 G).', 86)
) as t(code, name_en, name_fr, description_en, description_fr, display_order)
where c.country_code = 'CG'
  and exists (select 1 from statutory_rate_type s where s.company_id = c.id and s.code = 'CNSS_EMPLOYEE')
  and not exists (select 1 from statutory_rate_type s where s.company_id = c.id and s.code = t.code);

insert into statutory_rate (company_id, code, effective_from, value, note)
select c.id, t.code, '2026-01-01', 1200, 'Loi 42-2025 (loi de finances 2026), CGI art. 116 G'
from company c, (values ('ITS_FIRST_BAND_FLAT'), ('ITS_MINIMUM_YEARLY')) as t(code)
where c.country_code = 'CG'
  and exists (select 1 from statutory_rate_type s where s.company_id = c.id and s.code = t.code)
  and not exists (select 1 from statutory_rate x where x.company_id = c.id and x.code = t.code);

update pay_element p set taxable_ipr = true
from company c where c.id = p.company_id and c.country_code = 'CG' and p.code = 'HOUSING';

update payroll_setting p set value = '2.1667'
from company c
where c.id = p.company_id and c.country_code = 'CG' and p.key in ('LEAVE_MIN_DAYS_MONTH', 'LEAVE_MIN_DAYS_MONTH_MINOR')
  and p.value::numeric < 2.1667;

update grade_category g set annual_leave_days = 26
from company c where c.id = g.company_id and c.country_code = 'CG' and g.annual_leave_days < 26;
