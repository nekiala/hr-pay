-- Notice periods by grade category, and who gave notice on a departure.
--
-- DR Congo: Labour Code (Loi 015/2002, unchanged on this point by Loi 16/010) art. 64: 14 working days from the day
-- after notification, plus 7 working days per full year of continuous service; the worker who resigns owes half the
-- employer's notice and never more. Arrêté 12/CAB.MIN/TPS/117/2005: agents de maîtrise 1 month plus 9 working days
-- per year (art. 7), cadres de collaboration and de direction 3 months plus 16 working days per year (art. 8).
-- Art. 65: one paid free day a week during notice to look for a job. Republic of the Congo: Code (Loi 45-75) art. 39
-- leaves the length to the collective agreement; art. 40 gives two paid free days a week. To check with the payroll
-- expert; the collective agreement can only give more.

alter table grade_category add column notice_base numeric(6, 2) not null default 14 check (notice_base >= 0);
alter table grade_category add column notice_base_unit varchar(6) not null default 'DAYS'
    check (notice_base_unit in ('DAYS', 'MONTHS'));
alter table grade_category add column notice_days_per_year numeric(6, 2) not null default 7 check (notice_days_per_year >= 0);
alter table grade_category add column notice_day_kind varchar(8) not null default 'WORKING'
    check (notice_day_kind in ('WORKING', 'CALENDAR'));
alter table grade_category add column notice_resignation_share numeric(4, 3) not null default 0.5
    check (notice_resignation_share > 0 and notice_resignation_share <= 1);

alter table departure add column notice_given_by varchar(8) check (notice_given_by in ('EMPLOYER', 'EMPLOYEE'));
alter table departure add column notice_date date;
alter table departure add column notice_end date;
alter table departure add column notice_owed numeric(14, 2) not null default 0;
alter table departure drop constraint departure_notice_check;
alter table departure add constraint departure_notice_check check (notice in ('WORKED', 'PAID', 'OWED', 'NONE'));

-- Every company: this migration reads past the company separation.
set local app.bypass = 'on';

-- The sample categories get their country's rule: execution staff, supervisors (agents de maîtrise), managers.
update grade_category g set notice_base = r.base, notice_base_unit = r.unit, notice_days_per_year = r.per_year,
                            notice_day_kind = r.kind, notice_resignation_share = r.share
from company c, (values
    ('CD', 'CAT1', 14, 'DAYS', 7, 'WORKING', 0.5),
    ('CD', 'CAT2', 1, 'MONTHS', 9, 'WORKING', 0.5),
    ('CD', 'CAT3', 3, 'MONTHS', 16, 'WORKING', 0.5),
    ('CG', 'CAT1', 1, 'MONTHS', 0, 'CALENDAR', 1),
    ('CG', 'CAT2', 2, 'MONTHS', 0, 'CALENDAR', 1),
    ('CG', 'CAT3', 3, 'MONTHS', 0, 'CALENDAR', 1)
) as r(country, code, base, unit, per_year, kind, share)
where c.id = g.company_id and c.country_code = r.country and g.code = r.code;
update grade_category g set notice_base = 1, notice_base_unit = 'MONTHS', notice_days_per_year = 0,
                            notice_day_kind = 'CALENDAR', notice_resignation_share = 1
from company c where c.id = g.company_id and c.country_code = 'CG' and g.code not in ('CAT1', 'CAT2', 'CAT3');

-- The notice now comes from the category; the old country-wide rates go.
delete from statutory_rate where code in ('NOTICE_BASE_DAYS', 'NOTICE_DAYS_PER_YEAR');
delete from statutory_rate_type where code in ('NOTICE_BASE_DAYS', 'NOTICE_DAYS_PER_YEAR');

insert into statutory_rate_type (company_id, code, name, unit, description, display_order)
select c.id, 'JOB_SEARCH_DAYS_PER_WEEK',
       case when c.language = 'fr' then 'Préavis, jours libres par semaine pour chercher un emploi' else 'Notice, free days a week to look for a job' end,
       'COUNT',
       case when c.language = 'fr' then 'Jours payés que la personne peut prendre chaque semaine du préavis pour chercher un nouvel emploi.'
            else 'Paid days the person may take each week of the notice to look for a new job.' end, 310
from company c where exists (select 1 from statutory_rate_type s where s.company_id = c.id and s.code = 'CNSS_EMPLOYEE');

insert into statutory_rate (company_id, code, effective_from, value, note)
select c.id, 'JOB_SEARCH_DAYS_PER_WEEK', '2020-01-01', case when c.country_code = 'CG' then 2 else 1 end,
       case when c.country_code = 'CG' then 'Code du travail art. 40' else 'Code du travail art. 65' end
from company c where exists (select 1 from statutory_rate_type s where s.company_id = c.id and s.code = 'CNSS_EMPLOYEE');

-- What a person who leaves without giving or working their notice owes: taken from the last payslip.
insert into pay_element (company_id, code, name, kind, method, subject_to_cnss, taxable_ipr, prorated, default_for_all,
                         system, display_order)
select c.id, 'NOTICE_OWED', case when c.language = 'fr' then 'Préavis non presté (dû par le travailleur)' else 'Notice not given (owed by the employee)' end,
       'DEDUCTION', 'COMPUTED', false, false, false, false, true, 135
from company c where exists (select 1 from pay_element p where p.company_id = c.id and p.code = 'BASIC');
