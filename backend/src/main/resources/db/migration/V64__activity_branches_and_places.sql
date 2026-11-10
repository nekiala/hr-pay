-- Picked from lists instead of typed: the company's branch of activity, the town where documents are signed, and the
-- site and town of each contract (DRC Code du travail art. 212 points 10 and 14: where the contract is carried out and
-- where it is signed).
--
-- Branches of activity: HR Pay's shared list, kept by HR Pay only (companies pick, they do not add). Labour law does not
-- fix a list (the livre de paie of arrêté 042/2008 and the DRC interprofessional agreement only ask for the employer's
-- "branche d'activité"), so the list follows the United Nations' ISIC Rev. 4 (CITI rév. 4 in French): its 21 sections
-- and the divisions most employers here belong to. AFRISTAT's NAEMA, used by the statistics offices of the CEMAC
-- countries, is built on the same sections. Seeds are marked verified = false.

create table activity_branch (
    id             bigserial    primary key,
    code           varchar(4)   not null unique check (code ~ '^([A-U]|[0-9]{2})$'),  -- ISIC section letter or division
    parent_id      bigint       references activity_branch (id),
    name_fr        varchar(150) not null,
    name_en        varchar(150) not null,
    active         boolean      not null default true,
    verified       boolean      not null default false,
    created_by     varchar(320),
    created_at     timestamptz  not null default now(),
    check ((code ~ '^[A-U]$') = (parent_id is null))
);
create index activity_branch_parent on activity_branch (parent_id);

create or replace function hrpay_shared_tables() returns text[] language sql immutable as $$
    select array['company', 'plan', 'installation', 'permission', 'exchange_rate', 'spring_session',
                 'spring_session_attributes', 'job_lock', 'backup_run', 'system_alert', 'login_failure',
                 'flyway_schema_history', 'billing_setting', 'invoice_counter', 'audit_chain_head', 'legal_document',
                 'status_incident', 'status_update', 'trial_request', 'platform_mail_server', 'calculator_lead',
                 'platform_whatsapp', 'firm_client', 'firm_client_staff', 'firm_visit', 'firm_commission',
                 'law_change', 'law_change_rate', 'sector_pack', 'sector_pack_rule', 'country', 'place',
                 'financial_institution', 'activity_branch']
$$;

insert into activity_branch (code, name_fr, name_en) values
('A', 'Agriculture, sylviculture et pêche', 'Agriculture, forestry and fishing'),
('B', 'Activités extractives (mines, pétrole et carrières)', 'Mining and quarrying'),
('C', 'Activités de fabrication', 'Manufacturing'),
('D', 'Production et distribution d''électricité, de gaz et de vapeur', 'Electricity, gas, steam and air conditioning supply'),
('E', 'Distribution d''eau, assainissement et gestion des déchets', 'Water supply, sewerage and waste management'),
('F', 'Construction (bâtiment et travaux publics)', 'Construction'),
('G', 'Commerce ; réparation d''automobiles et de motocycles', 'Wholesale and retail trade; repair of motor vehicles'),
('H', 'Transport et entreposage', 'Transportation and storage'),
('I', 'Hébergement et restauration', 'Accommodation and food service activities'),
('J', 'Information et communication', 'Information and communication'),
('K', 'Activités financières et d''assurance', 'Financial and insurance activities'),
('L', 'Activités immobilières', 'Real estate activities'),
('M', 'Activités spécialisées, scientifiques et techniques', 'Professional, scientific and technical activities'),
('N', 'Activités de services administratifs et de soutien', 'Administrative and support service activities'),
('O', 'Administration publique et défense ; sécurité sociale obligatoire', 'Public administration and defence; compulsory social security'),
('P', 'Enseignement', 'Education'),
('Q', 'Santé humaine et action sociale', 'Human health and social work activities'),
('R', 'Arts, spectacles et activités récréatives', 'Arts, entertainment and recreation'),
('S', 'Autres activités de services (associations, ONG, églises)', 'Other service activities (associations, NGOs, churches)'),
('T', 'Ménages employeurs de personnel domestique', 'Households as employers of domestic personnel'),
('U', 'Organisations et organismes extraterritoriaux', 'Extraterritorial organizations and bodies');

insert into activity_branch (code, parent_id, name_fr, name_en)
select d.code, s.id, d.name_fr, d.name_en from (values
('01', 'A', 'Culture et production animale', 'Crop and animal production'),
('02', 'A', 'Sylviculture et exploitation forestière', 'Forestry and logging'),
('03', 'A', 'Pêche et aquaculture', 'Fishing and aquaculture'),
('06', 'B', 'Extraction de pétrole brut et de gaz naturel', 'Extraction of crude petroleum and natural gas'),
('07', 'B', 'Extraction de minerais métalliques', 'Mining of metal ores'),
('08', 'B', 'Autres activités extractives (carrières)', 'Other mining and quarrying'),
('09', 'B', 'Services de soutien aux industries extractives', 'Mining support service activities'),
('10', 'C', 'Industries alimentaires', 'Manufacture of food products'),
('11', 'C', 'Fabrication de boissons', 'Manufacture of beverages'),
('16', 'C', 'Travail du bois', 'Manufacture of wood products'),
('19', 'C', 'Raffinage du pétrole', 'Manufacture of refined petroleum products'),
('23', 'C', 'Ciment et autres produits minéraux non métalliques', 'Cement and other non-metallic mineral products'),
('41', 'F', 'Construction de bâtiments', 'Construction of buildings'),
('42', 'F', 'Génie civil (routes, ouvrages)', 'Civil engineering'),
('45', 'G', 'Commerce et réparation d''automobiles et de motocycles', 'Trade and repair of motor vehicles and motorcycles'),
('46', 'G', 'Commerce de gros', 'Wholesale trade'),
('47', 'G', 'Commerce de détail', 'Retail trade'),
('49', 'H', 'Transports terrestres', 'Land transport'),
('50', 'H', 'Transports par eau', 'Water transport'),
('51', 'H', 'Transports aériens', 'Air transport'),
('52', 'H', 'Entreposage et services auxiliaires des transports', 'Warehousing and support activities for transportation'),
('53', 'H', 'Activités de poste et de courrier', 'Postal and courier activities'),
('55', 'I', 'Hébergement (hôtels)', 'Accommodation'),
('56', 'I', 'Restauration', 'Food and beverage service activities'),
('58', 'J', 'Édition', 'Publishing activities'),
('60', 'J', 'Radio et télévision', 'Programming and broadcasting activities'),
('61', 'J', 'Télécommunications', 'Telecommunications'),
('62', 'J', 'Programmation et conseil informatiques', 'Computer programming and consultancy'),
('63', 'J', 'Services d''information (hébergement web, portails)', 'Information service activities'),
('64', 'K', 'Banques et services financiers', 'Banking and financial service activities'),
('65', 'K', 'Assurance', 'Insurance'),
('66', 'K', 'Activités auxiliaires de services financiers (microfinance, change, transfert)', 'Activities auxiliary to financial services'),
('69', 'M', 'Activités juridiques et comptables', 'Legal and accounting activities'),
('70', 'M', 'Conseil de gestion', 'Management consultancy activities'),
('71', 'M', 'Architecture et ingénierie', 'Architecture and engineering'),
('73', 'M', 'Publicité et études de marché', 'Advertising and market research'),
('78', 'N', 'Activités liées à l''emploi (intérim, placement)', 'Employment activities (temporary work, placement)'),
('80', 'N', 'Sécurité et gardiennage', 'Security and investigation activities'),
('81', 'N', 'Nettoyage et entretien des bâtiments', 'Services to buildings and cleaning'),
('86', 'Q', 'Activités pour la santé humaine (hôpitaux, cliniques)', 'Human health activities'),
('88', 'Q', 'Action sociale', 'Social work activities'),
('94', 'S', 'Associations, ONG, syndicats et organisations religieuses', 'Activities of membership organizations (NGOs, unions, churches)')
) as d(code, section, name_fr, name_en) join activity_branch s on s.code = d.section;

-- A sector pack belongs to a branch: the Sector page proposes it to the companies of that branch.
alter table sector_pack add column activity_code varchar(4) references activity_branch (code) on update cascade;
update sector_pack set activity_code = case code when 'CD_MINING' then 'B' when 'CD_BANKS' then '64' when 'CG_OIL' then '06'
                                                  when 'CG_BANKS' then '64' when 'CG_COMMERCE' then 'G' end
where code in ('CD_MINING', 'CD_BANKS', 'CG_OIL', 'CG_BANKS', 'CG_COMMERCE');

-- The company's branch and signing town, picked; the text columns keep the name printed (and what was typed before,
-- until a value is picked).
alter table company add column activity_branch_id bigint references activity_branch (id),
                    add column signing_place_id   bigint references place (id);

update company c set activity_branch_id = b.id, activity = case c.language when 'en' then b.name_en else b.name_fr end
from activity_branch b
where c.activity is not null and b.id = (
    select min(x.id) from activity_branch x
    where ref_norm(c.activity) in (ref_norm(x.name_fr), ref_norm(x.name_en))
    having count(*) = 1);

update company c set signing_place_id = p.id, signing_place = p.name
from place p
where c.signing_place is not null and p.id = (
    select min(x.id) from place x
    where x.kind = 'CITY' and x.active and x.country_code = c.country_code
      and (x.company_id is null or x.company_id = c.id) and ref_norm(x.name) = ref_norm(c.signing_place)
    having count(*) = 1);

-- Each contract: the site it is carried out at (one of the company's sites) and the town it is signed in.
alter table contract_terms add column work_site_id    bigint references site (id),
                           add column signed_place_id bigint references place (id);

update contract_terms t set work_site_id = s.id, work_place = s.name
from site s
where t.work_place is not null and s.company_id = t.company_id and s.id = (
    select min(x.id) from site x where x.company_id = t.company_id and ref_norm(x.name) = ref_norm(t.work_place)
    having count(*) = 1);

update contract_terms t set signed_place_id = p.id, signed_place = p.name
from place p
where t.signed_place is not null and p.id = (
    select min(x.id) from place x
    where x.kind = 'CITY' and x.active and (x.company_id is null or x.company_id = t.company_id)
      and ref_norm(x.name) = ref_norm(t.signed_place)
    having count(*) = 1);
