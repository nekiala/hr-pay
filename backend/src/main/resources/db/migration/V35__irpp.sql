-- DR Congo: the IRPP replaces the IPR on salaries from 1 January 2026 (Loi n° 23/053 du 30 novembre 2023 instituant
-- l'impôt sur les sociétés et l'impôt sur le revenu des personnes physiques). Annual bands as the DGI publishes them:
-- 3 % up to 1 944 000 FC, 15 % up to 21 600 000 FC, 30 % up to 43 200 000 FC, 40 % above; the tax never exceeds 30 % of
-- the taxable base; 2 % less per dependant, nine at most (the existing IPR_CAP, IPR_DEPENDANT_REDUCTION and
-- IPR_MAX_DEPENDANTS rates). To check with the payroll expert.
--
-- The monthly declaration (IRPP DR 111) also asks for the postnom and the commune or territory of each employee.

alter table employee_profile add column postnom varchar(100);
alter table employee_profile add column commune varchar(100);

set local app.bypass = 'on';

insert into ipr_bracket (company_id, effective_from, lower_bound, upper_bound, rate_percent, created_by)
select c.id, '2026-01-01', b.lower_bound, b.upper_bound, b.rate, 'system'
from company c, (values (0, 1944000, 3), (1944000, 21600000, 15), (21600000, 43200000, 30), (43200000, null, 40))
    as b(lower_bound, upper_bound, rate)
where c.country_code = 'CD'
  and exists (select 1 from ipr_bracket x where x.company_id = c.id)
  and not exists (select 1 from ipr_bracket x where x.company_id = c.id and x.effective_from = '2026-01-01');
