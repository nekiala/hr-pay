-- A negotiated salary complement: the part of a person's agreed pay above their grade's basic. It is salary, so CNSS
-- and income tax apply, and it is prorated like the basic for days not paid. The amount is set per person (Pay
-- elements on the employee file); the default is nothing.

set local app.bypass = 'on';

insert into pay_element (company_id, code, name, kind, method, default_amount, default_currency, subject_to_cnss,
                         taxable_ipr, prorated, default_for_all, system, display_order)
select c.id, 'COMPLEMENT',
       case when c.language = 'fr' then 'Complément de salaire (négocié)' else 'Salary complement (negotiated)' end,
       'EARNING', 'FIXED', 0, case when c.country_code = 'CG' then 'XAF' else 'USD' end, true, true, true, false, false, 15
from company c
where exists (select 1 from pay_element p where p.company_id = c.id and p.code = 'BASIC')
  and not exists (select 1 from pay_element p where p.company_id = c.id and p.code = 'COMPLEMENT');
