-- DR Congo: the sample grade categories and grades become the legal job classification.
--
-- Ordonnance 67-442bis du 1er octobre 1967, annexe II (classification générale des emplois): category 1 manœuvre
-- (échelon 1 ordinaire, 2 lourd), 2 manœuvre spécialisé, 3 travailleur semi-qualifié (3 échelons), 4 travailleur
-- qualifié (2 échelons), 5 travailleur hautement qualifié. The interprofessional wage grid adds agents de maîtrise and
-- cadres de collaboration (4 échelons each); cadres de direction are outside the grid, their pay is negotiated.
-- Notice: arrêté 12/CAB.MIN/TPS/117/2005 (see V33).
-- Base salaries: the daily minimum of each step from January 2026 (Décret 25/22 du 30 mai 2025, SMIG 21 500 FC, as a
-- secondary source quotes it) times 26 days, at 2 800 FC to the dollar, rounded. To check with the payroll expert.
--
-- Only companies in DR Congo whose categories still carry the sample names change; the grades keep their rows, so
-- contracts on them stay where they are.

set local app.bypass = 'on';

create temporary table legal_category (old_code varchar(32), code varchar(32), name_en varchar(150), name_fr varchar(150),
    leave_days numeric, raise numeric, base numeric, unit varchar(6), per_year numeric, ord int) on commit drop;
insert into legal_category values
    ('CAT1', 'EXEC', 'Execution staff (categories 1 to 5)', 'Personnel d''exécution (catégories 1 à 5)', 20, 2, 14, 'DAYS', 7, 1),
    ('CAT2', 'MAITRISE', 'Supervisors (agents de maîtrise)', 'Agents de maîtrise', 24, 2.5, 1, 'MONTHS', 9, 2),
    ('CAT3', 'CADRE_COLLAB', 'Junior managers (cadres de collaboration)', 'Cadres de collaboration', 26, 3, 3, 'MONTHS', 16, 3),
    (null, 'CADRE_DIR', 'Senior managers (cadres de direction)', 'Cadres de direction', 26, 3, 3, 'MONTHS', 16, 4);

create temporary table legal_grade (old_code varchar(32), category varchar(32), code varchar(32), name_en varchar(150),
    name_fr varchar(150), base numeric) on commit drop;
insert into legal_grade values
    (null, 'EXEC', 'E1A', 'Cat. 1, step 1 · Ordinary labourer', 'Cat. 1, échelon 1 · Manœuvre ordinaire', 200),
    (null, 'EXEC', 'E1B', 'Cat. 1, step 2 · Heavy labourer', 'Cat. 1, échelon 2 · Manœuvre lourd', 232),
    (null, 'EXEC', 'E2', 'Cat. 2 · Specialised labourer', 'Cat. 2 · Manœuvre spécialisé', 266),
    ('G1A', 'EXEC', 'E3A', 'Cat. 3, step 1 · Semi-skilled worker', 'Cat. 3, échelon 1 · Travailleur semi-qualifié', 307),
    (null, 'EXEC', 'E3B', 'Cat. 3, step 2 · Semi-skilled worker', 'Cat. 3, échelon 2 · Travailleur semi-qualifié', 355),
    (null, 'EXEC', 'E3C', 'Cat. 3, step 3 · Semi-skilled worker', 'Cat. 3, échelon 3 · Travailleur semi-qualifié', 411),
    ('G1B', 'EXEC', 'E4A', 'Cat. 4, step 1 · Skilled worker', 'Cat. 4, échelon 1 · Travailleur qualifié', 473),
    (null, 'EXEC', 'E4B', 'Cat. 4, step 2 · Skilled worker', 'Cat. 4, échelon 2 · Travailleur qualifié', 547),
    (null, 'EXEC', 'E5', 'Cat. 5 · Highly skilled worker', 'Cat. 5 · Travailleur hautement qualifié', 633),
    ('G2A', 'MAITRISE', 'M1', 'Supervisor, step 1', 'Agent de maîtrise, échelon 1', 731),
    ('G2B', 'MAITRISE', 'M2', 'Supervisor, step 2', 'Agent de maîtrise, échelon 2', 842),
    (null, 'MAITRISE', 'M3', 'Supervisor, step 3', 'Agent de maîtrise, échelon 3', 974),
    (null, 'MAITRISE', 'M4', 'Supervisor, step 4', 'Agent de maîtrise, échelon 4', 1126),
    ('G3A', 'CADRE_COLLAB', 'C1', 'Junior manager, step 1', 'Cadre de collaboration, échelon 1', 1300),
    ('G3B', 'CADRE_COLLAB', 'C2', 'Junior manager, step 2', 'Cadre de collaboration, échelon 2', 1501),
    (null, 'CADRE_COLLAB', 'C3', 'Junior manager, step 3', 'Cadre de collaboration, échelon 3', 1761),
    (null, 'CADRE_COLLAB', 'C4', 'Junior manager, step 4', 'Cadre de collaboration, échelon 4', 1996),
    (null, 'CADRE_DIR', 'D1', 'Senior manager (pay negotiated)', 'Cadre de direction (salaire négocié)', 2500);

-- The companies to change: in DR Congo, with the three sample categories untouched by name.
create temporary table legal_company on commit drop as
select c.id, c.language from company c
where c.country_code = 'CD'
  and (select count(*) from grade_category g where g.company_id = c.id and g.code in ('CAT1', 'CAT2', 'CAT3')
         and (g.name like '%(sample)' or g.name like '%(exemple)')) = 3;

update grade_category g set code = l.code, name = case when c.language = 'fr' then l.name_fr else l.name_en end
from legal_company c, legal_category l
where g.company_id = c.id and g.code = l.old_code;

insert into grade_category (company_id, code, name, annual_leave_days, active, anniversary_raise_percent, raise_timing,
                            notice_base, notice_base_unit, notice_days_per_year, notice_day_kind, notice_resignation_share)
select c.id, l.code, case when c.language = 'fr' then l.name_fr else l.name_en end, l.leave_days, true, l.raise, 'NEXT_MONTH',
       l.base, l.unit, l.per_year, 'WORKING', 0.5
from legal_company c, legal_category l
where l.old_code is null
  and not exists (select 1 from grade_category g where g.company_id = c.id and g.code = l.code);

update grade g set code = l.code, name = case when c.language = 'fr' then l.name_fr else l.name_en end, base_salary = l.base,
                   currency = 'USD'
from legal_company c, legal_grade l
where g.company_id = c.id and g.code = l.old_code and (g.name like '%(sample)' or g.name like '%(exemple)');

insert into grade (company_id, category_id, code, name, base_salary, currency, active)
select c.id, k.id, l.code, case when c.language = 'fr' then l.name_fr else l.name_en end, l.base, 'USD', true
from legal_company c
join legal_grade l on true
join grade_category k on k.company_id = c.id and k.code = l.category
where not exists (select 1 from grade g where g.company_id = c.id and g.code = l.code);
