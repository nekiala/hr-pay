-- Who counts in the worker's family (DRC Code du travail art. 7 point 11, as amended by Loi 16/010): the spouse and
-- the unmarried children until majority, until 25 if they study full time, and without age limit if a disability
-- stops them earning a living. HR ticks "student" and "disabled"; payroll checks the ages itself.
alter table dependant add column student boolean not null default false,
                      add column disabled boolean not null default false;

insert into statutory_rate_type (company_id, code, name, unit, description, display_order)
select c.id, r.code,
       case when c.language = 'fr' then r.name_fr else r.name_en end, 'COUNT',
       case when c.language = 'fr' then r.desc_fr else r.desc_en end, r.display_order
from company c, (values
    ('CHILD_AGE_LIMIT', 'Child in charge, age limit', 'Enfant à charge, âge limite',
     'A child stops counting for family allowances and the tax reduction at this age, unless studying full time or disabled. Empty: no age check.',
     'Un enfant cesse de compter pour les allocations familiales et la réduction d''impôt à cet âge, sauf études à plein temps ou handicap. Vide : pas de contrôle d''âge.', 320),
    ('CHILD_STUDENT_AGE_LIMIT', 'Child studying full time, age limit', 'Enfant aux études à plein temps, âge limite',
     'A child who studies full time counts until this age. A disabled child counts without age limit.',
     'Un enfant aux études à plein temps compte jusqu''à cet âge. Un enfant handicapé compte sans limite d''âge.', 321)
) as r(code, name_en, name_fr, desc_en, desc_fr, display_order)
where exists (select 1 from statutory_rate_type s where s.company_id = c.id and s.code = 'CNSS_EMPLOYEE')
  and not exists (select 1 from statutory_rate_type s where s.company_id = c.id and s.code = r.code);

-- The DRC values; other countries set theirs once a local accountant has confirmed them.
insert into statutory_rate (company_id, code, effective_from, value, note)
select c.id, r.code, '2002-10-16', r.value, 'Code du travail art. 7 point 11'
from company c, (values ('CHILD_AGE_LIMIT', 18), ('CHILD_STUDENT_AGE_LIMIT', 25)) as r(code, value)
where c.country_code = 'CD'
  and exists (select 1 from statutory_rate_type s where s.company_id = c.id and s.code = 'CNSS_EMPLOYEE');
