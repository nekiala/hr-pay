-- Republic of the Congo, existing companies: the SMIG of 70,400 FCFA a month (décret n° 2024-2762 of 20 November 2024,
-- from January 2025), the CAMU solidarity contribution (0.5 % of the taxable pay above 500,000 FCFA a month) and the
-- TOL (1,000 FCFA a month). Rates as published by CongoPaie (CNSS et CAMU 2026), to verify in the Journal officiel.
set local app.bypass = 'on';

insert into statutory_rate_type (company_id, code, name, unit, description, display_order)
select c.id, t.code, case when c.language = 'fr' then t.name_fr else t.name_en end, t.unit,
       case when c.language = 'fr' then t.description_fr else t.description_en end, t.display_order
from company c, (values
    ('CAMU_SOLIDARITY', 'CAMU solidarity contribution', 'CAMU, contribution de solidarité', 'PERCENT',
     'Withheld on the taxable pay (after the CNSS) above the monthly threshold.',
     'Retenue sur le salaire imposable (après CNSS) au-delà du seuil mensuel.', 87),
    ('CAMU_SOLIDARITY_THRESHOLD', 'CAMU solidarity contribution, monthly threshold', 'CAMU, contribution de solidarité : seuil mensuel', 'XAF',
     'Only the taxable pay above this monthly amount pays the contribution.',
     'Seule la part du salaire imposable au-delà de ce montant mensuel paie la contribution.', 88),
    ('TOL_MONTHLY', 'TOL (premises occupation tax), per month', 'TOL (taxe d''occupation des locaux), par mois', 'XAF',
     'Flat amount withheld once a month on the month-end payslip.',
     'Montant fixe retenu une fois par mois sur le bulletin de fin de mois.', 89)
) as t(code, name_en, name_fr, unit, description_en, description_fr, display_order)
where c.country_code = 'CG'
  and exists (select 1 from statutory_rate_type s where s.company_id = c.id and s.code = 'CNSS_EMPLOYEE')
  and not exists (select 1 from statutory_rate_type s where s.company_id = c.id and s.code = t.code);

insert into statutory_rate (company_id, code, effective_from, value, note)
select c.id, t.code, t.effective_from::date, t.value, t.note
from company c, (values
    ('SMIG_MONTHLY', '2025-01-01', 70400, 'Décret n° 2024-2762 du 20 novembre 2024'),
    ('CAMU_SOLIDARITY', '2026-01-01', 0.5, 'CongoPaie, CNSS et CAMU 2026 (to verify in the Journal officiel)'),
    ('CAMU_SOLIDARITY_THRESHOLD', '2026-01-01', 500000, 'CongoPaie, CNSS et CAMU 2026 (to verify in the Journal officiel)'),
    ('TOL_MONTHLY', '2026-01-01', 1000, 'CongoPaie, CNSS et CAMU 2026 (to verify in the Journal officiel)')
) as t(code, effective_from, value, note)
where c.country_code = 'CG'
  and exists (select 1 from statutory_rate_type s where s.company_id = c.id and s.code = t.code)
  and not exists (select 1 from statutory_rate x where x.company_id = c.id and x.code = t.code
                                                   and x.effective_from = t.effective_from::date);

insert into pay_element (company_id, code, name, kind, method, subject_to_cnss, taxable_ipr, prorated, default_for_all,
                         system, display_order)
select c.id, e.code, case when c.language = 'fr' then e.fr else e.en end, 'DEDUCTION', 'COMPUTED', false, false,
       false, false, true, e.ord
from company c cross join (values
    ('CAMU_SOLIDARITY', 'CAMU solidarity contribution', 'CAMU, contribution de solidarité', 125),
    ('TOL', 'TOL (premises occupation tax)', 'TOL (taxe d''occupation des locaux)', 126)
) as e(code, en, fr, ord)
where c.country_code = 'CG'
  and exists (select 1 from pay_element p where p.company_id = c.id and p.code = 'BASIC')
  and not exists (select 1 from pay_element p where p.company_id = c.id and p.code = e.code);

-- The draft law change on the SMIG: HR Pay now applies it.
update law_change set in_app = 'APPLIED', in_app_note_en = 'Monthly minimum wage check at 70,400 FCFA.',
                      in_app_note_fr = 'Contrôle du salaire minimum à 70 400 FCFA par mois.', updated_at = now()
where id = 10 and country_code = 'CG' and in_app_note_en = 'HR Pay uses 90,000 FCFA until this is confirmed.';
