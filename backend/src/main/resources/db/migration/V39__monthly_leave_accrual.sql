-- Annual leave is earned month by month, credited by each closed monthly payroll.
--   DRC, Code du travail art. 141: at least one working day per full month of service (one and a half under 18),
--   plus one day per five years of seniority; days of work, weekly rest, paid leave, public holidays, maternity and
--   incapacity (up to six months a year) count as service, so unpaid leave and unjustified absence earn nothing.
--   Art. 140: the right to take the leave opens after a year of service.
--   Republic of the Congo: the official text online stops at art. 100 and secondary sources disagree (15 or 26 days
--   a year), so no legal floor is set; the grade category's days apply. To verify with the payroll rules certifier.

set local app.bypass = 'on';

insert into payroll_setting (company_id, key, value)
select c.id, s.key, case when c.country_code = 'CG' then s.cg else s.cd end
from company c cross join (values
    ('LEAVE_MIN_DAYS_MONTH', '1', '0'),          -- legal floor per full month of service, adults
    ('LEAVE_MIN_DAYS_MONTH_MINOR', '1.5', '0')   -- legal floor per full month of service, under 18
) as s(key, cd, cg)
on conflict do nothing;

alter table leave_ledger drop constraint leave_ledger_kind_check;
alter table leave_ledger add constraint leave_ledger_kind_check
    check (kind in ('GRANT', 'EARNED', 'TAKEN', 'RETURNED', 'ADJUSTMENT', 'PAYOUT', 'EXPIRED'));
-- One credit per person and month.
create unique index leave_ledger_one_earned on leave_ledger (user_id, leave_type_id, entry_date) where kind = 'EARNED';
