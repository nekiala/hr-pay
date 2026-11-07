-- Languages: each company has a default (French for the DRC and the Republic of the Congo); each person may pick
-- their own. Documents (payslips, declarations, letters) follow the company's language.
alter table company add column language varchar(2) not null default 'fr' check (language in ('fr', 'en'));
alter table app_user add column language varchar(2) check (language in ('fr', 'en'));

-- The currency the company declares and pays tax in (CDF in the DRC, XAF in the Republic of the Congo). Exchange
-- rates and the "local" amounts of payroll are in this currency.
alter table company add column local_currency varchar(3) not null default 'CDF';

-- Rows of every company are renamed: this migration reads past the company separation.
set local app.bypass = 'on';

-- The starting names (grades, leave, pay elements, rates, roles...) in French for French-speaking companies.

-- Only names still as shipped change: one a company renamed stays as it is.

update evaluation_criterion x set description = f.fr from (values
    ('Accuracy, care and few corrections needed.', 'Exactitude, soin et peu de corrections nécessaires.'),
    ('From attendance: 100 less 5 points per late day and 10 per absent day.', 'D''après les présences : 100 moins 5 points par jour de retard et 10 par jour d''absence.'),
    ('Helps colleagues, shares information, respects others.', 'Aide ses collègues, partage l''information, respecte les autres.'),
    ('Volume of work done against what was expected.', 'Volume de travail fait par rapport à ce qui était attendu.')) as f(en, fr), company c
where x.company_id = c.id and c.language = 'fr' and x.description = f.en;

update evaluation_criterion x set name = f.fr from (values
    ('Productivity', 'Productivité'),
    ('Punctuality', 'Ponctualité'),
    ('Quality of work', 'Qualité du travail'),
    ('Teamwork', 'Travail en équipe')) as f(en, fr), company c
where x.company_id = c.id and c.language = 'fr' and x.name = f.en;

update evaluation_template x set name = f.fr from (values
    ('Quarterly review (sample)', 'Revue trimestrielle (exemple)')) as f(en, fr), company c
where x.company_id = c.id and c.language = 'fr' and x.name = f.en;

update grade x set name = f.fr from (values
    ('Grade 1A (sample)', 'Grade 1A (exemple)'),
    ('Grade 1B (sample)', 'Grade 1B (exemple)'),
    ('Grade 2A (sample)', 'Grade 2A (exemple)'),
    ('Grade 2B (sample)', 'Grade 2B (exemple)'),
    ('Grade 3A (sample)', 'Grade 3A (exemple)'),
    ('Grade 3B (sample)', 'Grade 3B (exemple)')) as f(en, fr), company c
where x.company_id = c.id and c.language = 'fr' and x.name = f.en;

update grade_category x set name = f.fr from (values
    ('Category 1: execution staff (sample)', 'Catégorie 1 : personnel d''exécution (exemple)'),
    ('Category 2: supervisors (sample)', 'Catégorie 2 : agents de maîtrise (exemple)'),
    ('Category 3: managers (sample)', 'Catégorie 3 : cadres (exemple)')) as f(en, fr), company c
where x.company_id = c.id and c.language = 'fr' and x.name = f.en;

update holiday_definition x set name = f.fr from (values
    ('Christmas Day', 'Noël'),
    ('Congolese Genocide Remembrance Day', 'Journée de commémoration du génocide congolais'),
    ('Independence Day', 'Fête de l''indépendance'),
    ('Labour Day', 'Fête du travail'),
    ('Laurent-Désiré Kabila Day', 'Journée de Laurent-Désiré Kabila'),
    ('Liberation Day', 'Journée de la libération'),
    ('Martyrs of Independence Day', 'Journée des martyrs de l''indépendance'),
    ('New Year''s Day', 'Jour de l''an'),
    ('Parents'' Day', 'Fête des parents'),
    ('Patrice Lumumba Day', 'Journée de Patrice Lumumba'),
    ('Simon Kimbangu Day', 'Journée de Simon Kimbangu')) as f(en, fr), company c
where x.company_id = c.id and c.language = 'fr' and x.name = f.en;

update leave_type x set name = f.fr from (values
    ('Annual leave', 'Congé annuel'),
    ('Family event (marriage, birth, death)', 'Événement familial (mariage, naissance, décès)'),
    ('Maternity leave', 'Congé de maternité'),
    ('Sick leave', 'Congé de maladie'),
    ('Unpaid leave', 'Congé sans solde')) as f(en, fr), company c
where x.company_id = c.id and c.language = 'fr' and x.name = f.en;

update occupation x set name = f.fr from (values
    ('Accountant', 'Comptable'),
    ('Driver', 'Chauffeur'),
    ('Engineer', 'Ingénieur'),
    ('HR officer', 'Chargé des RH'),
    ('Manager', 'Responsable'),
    ('Receptionist', 'Réceptionniste')) as f(en, fr), company c
where x.company_id = c.id and c.language = 'fr' and x.name = f.en;

update pay_element x set name = f.fr from (values
    ('13th month', '13e mois'),
    ('Basic salary', 'Salaire de base'),
    ('CNSS (employee share)', 'CNSS (part salariale)'),
    ('CNSS (employer share)', 'CNSS (part patronale)'),
    ('Family allowances (sample)', 'Allocations familiales (exemple)'),
    ('Holiday change, earlier month', 'Changement de jour férié, mois antérieur'),
    ('Holiday change, earlier month (less)', 'Changement de jour férié, mois antérieur (en moins)'),
    ('Housing allowance (sample)', 'Indemnité de logement (exemple)'),
    ('IERE (expatriate pay tax)', 'IERE (impôt sur la rémunération des expatriés)'),
    ('INPP (training)', 'INPP (formation)'),
    ('IPR (income tax)', 'IPR (impôt sur le revenu)'),
    ('Loan repayment', 'Remboursement de prêt'),
    ('ONEM (employment office)', 'ONEM (office de l''emploi)'),
    ('Other bonus', 'Autre prime'),
    ('Other deduction', 'Autre retenue'),
    ('Overtime', 'Heures supplémentaires'),
    ('Performance bonus', 'Prime de rendement'),
    ('Salary advance', 'Avance sur salaire'),
    ('Transport allowance (sample)', 'Indemnité de transport (exemple)'),
    ('Unused leave paid out', 'Congés non pris payés')) as f(en, fr), company c
where x.company_id = c.id and c.language = 'fr' and x.name = f.en;

update role x set description = f.fr from (values
    ('Employees, schedules, payroll', 'Employés, horaires, paie'),
    ('Own attendance, leave and payslips', 'Ses propres présences, congés et bulletins'),
    ('Payroll approval and finance reports', 'Approbation de la paie et rapports financiers'),
    ('Platform configuration, users and roles', 'Configuration de la plateforme, utilisateurs et rôles'),
    ('Team attendance, approvals and evaluations', 'Présences de l''équipe, approbations et évaluations')) as f(en, fr), company c
where x.company_id = c.id and c.language = 'fr' and x.description = f.en;

update role x set name = f.fr from (values
    ('Administrator', 'Administrateur'),
    ('Employee', 'Employé'),
    ('Finance', 'Finances'),
    ('Human resources', 'Ressources humaines'),
    ('Manager', 'Responsable')) as f(en, fr), company c
where x.company_id = c.id and c.language = 'fr' and x.name = f.en;

update statutory_rate_type x set description = f.fr from (values
    ('Basic pay may not fall below this daily amount; checked, not paid.', 'Le salaire de base ne peut pas descendre sous ce montant journalier ; vérifié, pas versé.'),
    ('Dependants beyond this number give no further reduction.', 'Au-delà de ce nombre, les personnes à charge ne donnent plus de réduction.'),
    ('Employer contribution to the employment office.', 'Cotisation patronale à l''office de l''emploi.'),
    ('Employer tax on the pay of expatriate employees.', 'Impôt patronal sur la rémunération des salariés expatriés.'),
    ('Employer training levy above 300 employees.', 'Cotisation patronale de formation au-delà de 300 salariés.'),
    ('Employer training levy for 1 to 50 employees.', 'Cotisation patronale de formation pour 1 à 50 salariés.'),
    ('Employer training levy for 51 to 300 employees.', 'Cotisation patronale de formation pour 51 à 300 salariés.'),
    ('IPR may not exceed this share of the taxable income.', 'L''IPR ne peut pas dépasser cette part du revenu imposable.'),
    ('Loan and advance installments together may take at most this share of the net pay; the rest waits.', 'Les mensualités de prêts et d''avances prennent ensemble au plus cette part du net ; le reste attend.'),
    ('Pensions, family benefits and work accidents, on elements subject to CNSS.', 'Pensions, prestations familiales et accidents du travail, sur les éléments soumis à la CNSS.'),
    ('Reduction of the IPR for each dependant in charge.', 'Réduction de l''IPR pour chaque personne à charge.'),
    ('Withheld from elements subject to CNSS.', 'Retenue sur les éléments soumis à la CNSS.')) as f(en, fr), company c
where x.company_id = c.id and c.language = 'fr' and x.description = f.en;

update statutory_rate_type x set name = f.fr from (values
    ('CNSS, employee share', 'CNSS, part salariale'),
    ('CNSS, employer share', 'CNSS, part patronale'),
    ('INPP, 51 to 300 staff', 'INPP, de 51 à 300 salariés'),
    ('INPP, above 300 staff', 'INPP, plus de 300 salariés'),
    ('INPP, up to 50 staff', 'INPP, jusqu''à 50 salariés'),
    ('IPR cap', 'Plafond de l''IPR'),
    ('IPR dependants counted, at most', 'Personnes à charge comptées pour l''IPR, au plus'),
    ('IPR reduction per dependant', 'Réduction d''IPR par personne à charge'),
    ('Loan deductions, at most', 'Retenues pour prêts, au plus'),
    ('Minimum wage (SMIG), per day', 'Salaire minimum (SMIG), par jour')) as f(en, fr), company c
where x.company_id = c.id and c.language = 'fr' and x.name = f.en;

update work_schedule x set name = f.fr from (values
    ('Mon-Fri 08:00-17:00', 'Lun-ven 08:00-17:00')) as f(en, fr), company c
where x.company_id = c.id and c.language = 'fr' and x.name = f.en;
