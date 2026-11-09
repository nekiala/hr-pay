-- The INPP band follows the number of people under contract unless HR picks one. "More than 300" was the default
-- every company started with, so it becomes the automatic choice (which still gives it to companies above 300).
update payroll_setting set value = 'AUTO' where key = 'INPP_BAND' and value = 'LARGE';

-- In the DRC the tax on salaries is the IRPP since 1 January 2026 (Loi 23/053); payslips name it so. A name a
-- company changed itself is kept.
update pay_element set name = 'IRPP (impôt sur le revenu)'
where code = 'IPR' and name = 'IPR (impôt sur le revenu)'
  and company_id in (select id from company where country_code = 'CD');
update pay_element set name = 'IRPP (income tax)'
where code = 'IPR' and name = 'IPR (income tax)'
  and company_id in (select id from company where country_code = 'CD');
