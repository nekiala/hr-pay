-- Reference lists (master data): countries and nationalities, administrative places, banks and mobile money operators.
-- HR picks them on the employee file instead of typing them. HR Pay keeps the shared lists (company_id null); a
-- company can add a missing place or bank, seen only by itself (company_id set). The CNSS registration of a worker
-- (DRC Arrêté 146/CAB/MINETAT/MTEPS/01/2018 art. 9, form Mod.IMT) asks for the place and date of birth, the physical
-- address, the identity document and the nationality: the employee file now records them.
--
-- Seeds below are marked verified = false: they come from public lists (BCC and COBAC bank lists, the 26 DRC provinces
-- of the Constitution art. 2 and their 145 territoires, the 15 Congo departments of lois 25, 26 et 27-2024 du
-- 8 octobre 2024) and are to be checked.

-- Lower case, no accents, single spaces: how typed names are compared with the lists.
create or replace function ref_norm(t text) returns text language sql immutable as $$
    select nullif(trim(regexp_replace(lower(translate(coalesce(t, ''),
        'ÀÁÂÃÄÅàáâãäåÈÉÊËèéêëÌÍÎÏìíîïÒÓÔÕÖòóôõöÙÚÛÜùúûüÇçÑñŸÿ''’-_.,()',
        'AAAAAAaaaaaaEEEEeeeeIIIIiiiiOOOOOoooooUUUUuuuuCcNnYy        ')), '\s+', ' ', 'g')), '')
$$;

create table country (
    code            varchar(2)   primary key check (code ~ '^[A-Z]{2}$'),
    name_fr         varchar(100) not null,
    name_en         varchar(100) not null,
    nationality_fr  varchar(60),
    nationality_en  varchar(60),
    aliases         varchar(300),                 -- other spellings, separated by |
    active          boolean      not null default true
);

create table place (
    id            bigserial    primary key,
    company_id    bigint       references company (id),      -- null: HR Pay's shared list
    country_code  varchar(2)   not null references country (code),
    parent_id     bigint       references place (id),
    kind          varchar(16)  not null check (kind in ('PROVINCE', 'DEPARTMENT', 'CITY', 'TERRITORY', 'COMMUNE',
                                                         'SECTOR', 'CHIEFDOM', 'DISTRICT', 'ARRONDISSEMENT')),
    name          varchar(100) not null,
    active        boolean      not null default true,
    verified      boolean      not null default false,
    created_by    varchar(320),
    created_at    timestamptz  not null default now()
);
create index place_parent on place (country_code, parent_id);
create unique index place_name_uk on place (country_code, coalesce(parent_id, 0), kind, ref_norm(name), coalesce(company_id, 0));

create table financial_institution (
    id            bigserial    primary key,
    company_id    bigint       references company (id),      -- null: HR Pay's shared list
    country_code  varchar(2)   not null references country (code),
    kind          varchar(12)  not null check (kind in ('BANK', 'MOBILE_MONEY')),
    name          varchar(100) not null,
    short_name    varchar(30),
    swift_code    varchar(11)  check (swift_code ~ '^[A-Z0-9]{8}([A-Z0-9]{3})?$'),
    aliases       varchar(300),                 -- other spellings, separated by |
    active        boolean      not null default true,
    verified      boolean      not null default false,
    created_by    varchar(320),
    created_at    timestamptz  not null default now()
);
create unique index financial_institution_name_uk
    on financial_institution (country_code, kind, ref_norm(name), coalesce(company_id, 0));

create or replace function hrpay_shared_tables() returns text[] language sql immutable as $$
    select array['company', 'plan', 'installation', 'permission', 'exchange_rate', 'spring_session',
                 'spring_session_attributes', 'job_lock', 'backup_run', 'system_alert', 'login_failure',
                 'flyway_schema_history', 'billing_setting', 'invoice_counter', 'audit_chain_head', 'legal_document',
                 'status_incident', 'status_update', 'trial_request', 'platform_mail_server', 'calculator_lead',
                 'platform_whatsapp', 'firm_client', 'firm_client_staff', 'firm_visit', 'firm_commission',
                 'law_change', 'law_change_rate', 'sector_pack', 'sector_pack_rule', 'country', 'place',
                 'financial_institution']
$$;

insert into country (code, name_fr, name_en, nationality_fr, nationality_en, aliases) values
('AD', 'Andorre', 'Andorra', null, null, null),
('AE', 'Émirats arabes unis', 'United Arab Emirates', 'Émirienne', 'Emirati', null),
('AF', 'Afghanistan', 'Afghanistan', null, null, null),
('AG', 'Antigua-et-Barbuda', 'Antigua & Barbuda', null, null, null),
('AI', 'Anguilla', 'Anguilla', null, null, null),
('AL', 'Albanie', 'Albania', null, null, null),
('AM', 'Arménie', 'Armenia', null, null, null),
('AO', 'Angola', 'Angola', 'Angolaise', 'Angolan', null),
('AQ', 'Antarctique', 'Antarctica', null, null, null),
('AR', 'Argentine', 'Argentina', 'Argentine', 'Argentine', null),
('AS', 'Samoa américaines', 'American Samoa', null, null, null),
('AT', 'Autriche', 'Austria', 'Autrichienne', 'Austrian', null),
('AU', 'Australie', 'Australia', 'Australienne', 'Australian', null),
('AW', 'Aruba', 'Aruba', null, null, null),
('AX', 'Îles Åland', 'Åland Islands', null, null, null),
('AZ', 'Azerbaïdjan', 'Azerbaijan', null, null, null),
('BA', 'Bosnie-Herzégovine', 'Bosnia & Herzegovina', null, null, null),
('BB', 'Barbade', 'Barbados', null, null, null),
('BD', 'Bangladesh', 'Bangladesh', 'Bangladaise', 'Bangladeshi', null),
('BE', 'Belgique', 'Belgium', 'Belge', 'Belgian', null),
('BF', 'Burkina Faso', 'Burkina Faso', 'Burkinabè', 'Burkinabe', null),
('BG', 'Bulgarie', 'Bulgaria', 'Bulgare', 'Bulgarian', null),
('BH', 'Bahreïn', 'Bahrain', null, null, null),
('BI', 'Burundi', 'Burundi', 'Burundaise', 'Burundian', null),
('BJ', 'Bénin', 'Benin', 'Béninoise', 'Beninese', null),
('BL', 'Saint-Barthélemy', 'St. Barthélemy', null, null, null),
('BM', 'Bermudes', 'Bermuda', null, null, null),
('BN', 'Brunei', 'Brunei', null, null, null),
('BO', 'Bolivie', 'Bolivia', null, null, null),
('BQ', 'Pays-Bas caribéens', 'Caribbean Netherlands', null, null, null),
('BR', 'Brésil', 'Brazil', 'Brésilienne', 'Brazilian', null),
('BS', 'Bahamas', 'Bahamas', null, null, null),
('BT', 'Bhoutan', 'Bhutan', null, null, null),
('BV', 'Île Bouvet', 'Bouvet Island', null, null, null),
('BW', 'Botswana', 'Botswana', 'Botswanaise', 'Motswana', null),
('BY', 'Biélorussie', 'Belarus', null, null, null),
('BZ', 'Belize', 'Belize', null, null, null),
('CA', 'Canada', 'Canada', 'Canadienne', 'Canadian', null),
('CC', 'Îles Cocos', 'Cocos (Keeling) Islands', null, null, null),
('CD', 'République démocratique du Congo', 'Democratic Republic of the Congo', 'Congolaise (RDC)', 'Congolese (DRC)', 'RDC|RD Congo|DRC|Congo-Kinshasa|Congo Kinshasa|Zaire'),
('CF', 'République centrafricaine', 'Central African Republic', 'Centrafricaine', 'Central African', null),
('CG', 'République du Congo', 'Republic of the Congo', 'Congolaise (Congo)', 'Congolese (Congo)', 'Congo|Congo-Brazzaville|Congo Brazzaville|Congo Brazza'),
('CH', 'Suisse', 'Switzerland', 'Suisse', 'Swiss', null),
('CI', 'Côte d''Ivoire', 'Côte d''Ivoire', 'Ivoirienne', 'Ivorian', null),
('CK', 'Îles Cook', 'Cook Islands', null, null, null),
('CL', 'Chili', 'Chile', 'Chilienne', 'Chilean', null),
('CM', 'Cameroun', 'Cameroon', 'Camerounaise', 'Cameroonian', null),
('CN', 'Chine', 'China', 'Chinoise', 'Chinese', null),
('CO', 'Colombie', 'Colombia', 'Colombienne', 'Colombian', null),
('CR', 'Costa Rica', 'Costa Rica', null, null, null),
('CU', 'Cuba', 'Cuba', 'Cubaine', 'Cuban', null),
('CV', 'Cap-Vert', 'Cape Verde', 'Cap-Verdienne', 'Cape Verdean', null),
('CW', 'Curaçao', 'Curaçao', null, null, null),
('CX', 'Île Christmas', 'Christmas Island', null, null, null),
('CY', 'Chypre', 'Cyprus', 'Chypriote', 'Cypriot', null),
('CZ', 'Tchéquie', 'Czechia', 'Tchèque', 'Czech', null),
('DE', 'Allemagne', 'Germany', 'Allemande', 'German', null),
('DJ', 'Djibouti', 'Djibouti', 'Djiboutienne', 'Djiboutian', null),
('DK', 'Danemark', 'Denmark', 'Danoise', 'Danish', null),
('DM', 'Dominique', 'Dominica', null, null, null),
('DO', 'République dominicaine', 'Dominican Republic', null, null, null),
('DZ', 'Algérie', 'Algeria', 'Algérienne', 'Algerian', null),
('EC', 'Équateur', 'Ecuador', null, null, null),
('EE', 'Estonie', 'Estonia', null, null, null),
('EG', 'Égypte', 'Egypt', 'Égyptienne', 'Egyptian', null),
('EH', 'Sahara occidental', 'Western Sahara', null, null, null),
('ER', 'Érythrée', 'Eritrea', 'Érythréenne', 'Eritrean', null),
('ES', 'Espagne', 'Spain', 'Espagnole', 'Spanish', null),
('ET', 'Éthiopie', 'Ethiopia', 'Éthiopienne', 'Ethiopian', null),
('FI', 'Finlande', 'Finland', 'Finlandaise', 'Finnish', null),
('FJ', 'Fidji', 'Fiji', null, null, null),
('FK', 'Îles Malouines', 'Falkland Islands', null, null, null),
('FM', 'Micronésie', 'Micronesia', null, null, null),
('FO', 'Îles Féroé', 'Faroe Islands', null, null, null),
('FR', 'France', 'France', 'Française', 'French', null),
('GA', 'Gabon', 'Gabon', 'Gabonaise', 'Gabonese', null),
('GB', 'Royaume-Uni', 'United Kingdom', 'Britannique', 'British', null),
('GD', 'Grenade', 'Grenada', null, null, null),
('GE', 'Géorgie', 'Georgia', null, null, null),
('GF', 'Guyane française', 'French Guiana', null, null, null),
('GG', 'Guernesey', 'Guernsey', null, null, null),
('GH', 'Ghana', 'Ghana', 'Ghanéenne', 'Ghanaian', null),
('GI', 'Gibraltar', 'Gibraltar', null, null, null),
('GL', 'Groenland', 'Greenland', null, null, null),
('GM', 'Gambie', 'Gambia', 'Gambienne', 'Gambian', null),
('GN', 'Guinée', 'Guinea', 'Guinéenne', 'Guinean', null),
('GP', 'Guadeloupe', 'Guadeloupe', null, null, null),
('GQ', 'Guinée équatoriale', 'Equatorial Guinea', 'Équato-Guinéenne', 'Equatoguinean', null),
('GR', 'Grèce', 'Greece', 'Grecque', 'Greek', null),
('GS', 'Géorgie du Sud-et-les Îles Sandwich du Sud', 'South Georgia & South Sandwich Islands', null, null, null),
('GT', 'Guatemala', 'Guatemala', null, null, null),
('GU', 'Guam', 'Guam', null, null, null),
('GW', 'Guinée-Bissau', 'Guinea-Bissau', 'Bissau-Guinéenne', 'Bissau-Guinean', null),
('GY', 'Guyana', 'Guyana', null, null, null),
('HK', 'R.A.S. chinoise de Hong Kong', 'Hong Kong SAR China', null, null, null),
('HM', 'Îles Heard-et-MacDonald', 'Heard & McDonald Islands', null, null, null),
('HN', 'Honduras', 'Honduras', null, null, null),
('HR', 'Croatie', 'Croatia', null, null, null),
('HT', 'Haïti', 'Haiti', 'Haïtienne', 'Haitian', null),
('HU', 'Hongrie', 'Hungary', 'Hongroise', 'Hungarian', null),
('ID', 'Indonésie', 'Indonesia', 'Indonésienne', 'Indonesian', null),
('IE', 'Irlande', 'Ireland', 'Irlandaise', 'Irish', null),
('IL', 'Israël', 'Israel', 'Israélienne', 'Israeli', null),
('IM', 'Île de Man', 'Isle of Man', null, null, null),
('IN', 'Inde', 'India', 'Indienne', 'Indian', null),
('IO', 'Territoire britannique de l''océan Indien', 'British Indian Ocean Territory', null, null, null),
('IQ', 'Irak', 'Iraq', 'Irakienne', 'Iraqi', null),
('IR', 'Iran', 'Iran', 'Iranienne', 'Iranian', null),
('IS', 'Islande', 'Iceland', null, null, null),
('IT', 'Italie', 'Italy', 'Italienne', 'Italian', null),
('JE', 'Jersey', 'Jersey', null, null, null),
('JM', 'Jamaïque', 'Jamaica', null, null, null),
('JO', 'Jordanie', 'Jordan', 'Jordanienne', 'Jordanian', null),
('JP', 'Japon', 'Japan', 'Japonaise', 'Japanese', null),
('KE', 'Kenya', 'Kenya', 'Kényane', 'Kenyan', null),
('KG', 'Kirghizstan', 'Kyrgyzstan', null, null, null),
('KH', 'Cambodge', 'Cambodia', null, null, null),
('KI', 'Kiribati', 'Kiribati', null, null, null),
('KM', 'Comores', 'Comoros', 'Comorienne', 'Comorian', null),
('KN', 'Saint-Christophe-et-Niévès', 'St. Kitts & Nevis', null, null, null),
('KP', 'Corée du Nord', 'North Korea', null, null, null),
('KR', 'Corée du Sud', 'South Korea', 'Sud-Coréenne', 'South Korean', null),
('KW', 'Koweït', 'Kuwait', 'Koweïtienne', 'Kuwaiti', null),
('KY', 'Îles Caïmans', 'Cayman Islands', null, null, null),
('KZ', 'Kazakhstan', 'Kazakhstan', null, null, null),
('LA', 'Laos', 'Laos', null, null, null),
('LB', 'Liban', 'Lebanon', 'Libanaise', 'Lebanese', null),
('LC', 'Sainte-Lucie', 'St. Lucia', null, null, null),
('LI', 'Liechtenstein', 'Liechtenstein', null, null, null),
('LK', 'Sri Lanka', 'Sri Lanka', 'Sri-Lankaise', 'Sri Lankan', null),
('LR', 'Liberia', 'Liberia', 'Libérienne', 'Liberian', null),
('LS', 'Lesotho', 'Lesotho', 'Lesothane', 'Basotho', null),
('LT', 'Lituanie', 'Lithuania', null, null, null),
('LU', 'Luxembourg', 'Luxembourg', 'Luxembourgeoise', 'Luxembourgish', null),
('LV', 'Lettonie', 'Latvia', null, null, null),
('LY', 'Libye', 'Libya', 'Libyenne', 'Libyan', null),
('MA', 'Maroc', 'Morocco', 'Marocaine', 'Moroccan', null),
('MC', 'Monaco', 'Monaco', null, null, null),
('MD', 'Moldavie', 'Moldova', null, null, null),
('ME', 'Monténégro', 'Montenegro', null, null, null),
('MF', 'Saint-Martin', 'St. Martin', null, null, null),
('MG', 'Madagascar', 'Madagascar', 'Malgache', 'Malagasy', null),
('MH', 'Îles Marshall', 'Marshall Islands', null, null, null),
('MK', 'Macédoine du Nord', 'North Macedonia', null, null, null),
('ML', 'Mali', 'Mali', 'Malienne', 'Malian', null),
('MM', 'Myanmar (Birmanie)', 'Myanmar (Burma)', null, null, null),
('MN', 'Mongolie', 'Mongolia', null, null, null),
('MO', 'R.A.S. chinoise de Macao', 'Macao SAR China', null, null, null),
('MP', 'Îles Mariannes du Nord', 'Northern Mariana Islands', null, null, null),
('MQ', 'Martinique', 'Martinique', null, null, null),
('MR', 'Mauritanie', 'Mauritania', 'Mauritanienne', 'Mauritanian', null),
('MS', 'Montserrat', 'Montserrat', null, null, null),
('MT', 'Malte', 'Malta', null, null, null),
('MU', 'Maurice', 'Mauritius', 'Mauricienne', 'Mauritian', null),
('MV', 'Maldives', 'Maldives', null, null, null),
('MW', 'Malawi', 'Malawi', 'Malawite', 'Malawian', null),
('MX', 'Mexique', 'Mexico', 'Mexicaine', 'Mexican', null),
('MY', 'Malaisie', 'Malaysia', 'Malaisienne', 'Malaysian', null),
('MZ', 'Mozambique', 'Mozambique', 'Mozambicaine', 'Mozambican', null),
('NA', 'Namibie', 'Namibia', 'Namibienne', 'Namibian', null),
('NC', 'Nouvelle-Calédonie', 'New Caledonia', null, null, null),
('NE', 'Niger', 'Niger', 'Nigérienne', 'Nigerien', null),
('NF', 'Île Norfolk', 'Norfolk Island', null, null, null),
('NG', 'Nigeria', 'Nigeria', 'Nigériane', 'Nigerian', null),
('NI', 'Nicaragua', 'Nicaragua', null, null, null),
('NL', 'Pays-Bas', 'Netherlands', 'Néerlandaise', 'Dutch', null),
('NO', 'Norvège', 'Norway', 'Norvégienne', 'Norwegian', null),
('NP', 'Népal', 'Nepal', null, null, null),
('NR', 'Nauru', 'Nauru', null, null, null),
('NU', 'Niue', 'Niue', null, null, null),
('NZ', 'Nouvelle-Zélande', 'New Zealand', 'Néo-Zélandaise', 'New Zealander', null),
('OM', 'Oman', 'Oman', null, null, null),
('PA', 'Panama', 'Panama', null, null, null),
('PE', 'Pérou', 'Peru', 'Péruvienne', 'Peruvian', null),
('PF', 'Polynésie française', 'French Polynesia', null, null, null),
('PG', 'Papouasie-Nouvelle-Guinée', 'Papua New Guinea', null, null, null),
('PH', 'Philippines', 'Philippines', 'Philippine', 'Filipino', null),
('PK', 'Pakistan', 'Pakistan', 'Pakistanaise', 'Pakistani', null),
('PL', 'Pologne', 'Poland', 'Polonaise', 'Polish', null),
('PM', 'Saint-Pierre-et-Miquelon', 'St. Pierre & Miquelon', null, null, null),
('PN', 'Îles Pitcairn', 'Pitcairn Islands', null, null, null),
('PR', 'Porto Rico', 'Puerto Rico', null, null, null),
('PS', 'Territoires palestiniens', 'Palestinian Territories', null, null, null),
('PT', 'Portugal', 'Portugal', 'Portugaise', 'Portuguese', null),
('PW', 'Palaos', 'Palau', null, null, null),
('PY', 'Paraguay', 'Paraguay', null, null, null),
('QA', 'Qatar', 'Qatar', 'Qatarienne', 'Qatari', null),
('RE', 'La Réunion', 'Réunion', null, null, null),
('RO', 'Roumanie', 'Romania', 'Roumaine', 'Romanian', null),
('RS', 'Serbie', 'Serbia', null, null, null),
('RU', 'Russie', 'Russia', 'Russe', 'Russian', null),
('RW', 'Rwanda', 'Rwanda', 'Rwandaise', 'Rwandan', null),
('SA', 'Arabie saoudite', 'Saudi Arabia', 'Saoudienne', 'Saudi', null),
('SB', 'Îles Salomon', 'Solomon Islands', null, null, null),
('SC', 'Seychelles', 'Seychelles', 'Seychelloise', 'Seychellois', null),
('SD', 'Soudan', 'Sudan', 'Soudanaise', 'Sudanese', null),
('SE', 'Suède', 'Sweden', 'Suédoise', 'Swedish', null),
('SG', 'Singapour', 'Singapore', 'Singapourienne', 'Singaporean', null),
('SH', 'Sainte-Hélène', 'St. Helena', null, null, null),
('SI', 'Slovénie', 'Slovenia', null, null, null),
('SJ', 'Svalbard et Jan Mayen', 'Svalbard & Jan Mayen', null, null, null),
('SK', 'Slovaquie', 'Slovakia', null, null, null),
('SL', 'Sierra Leone', 'Sierra Leone', 'Sierra-Léonaise', 'Sierra Leonean', null),
('SM', 'Saint-Marin', 'San Marino', null, null, null),
('SN', 'Sénégal', 'Senegal', 'Sénégalaise', 'Senegalese', null),
('SO', 'Somalie', 'Somalia', 'Somalienne', 'Somali', null),
('SR', 'Suriname', 'Suriname', null, null, null),
('SS', 'Soudan du Sud', 'South Sudan', 'Sud-Soudanaise', 'South Sudanese', null),
('ST', 'Sao Tomé-et-Principe', 'São Tomé & Príncipe', 'Santoméenne', 'Santomean', null),
('SV', 'Salvador', 'El Salvador', null, null, null),
('SX', 'Saint-Martin (partie néerlandaise)', 'Sint Maarten', null, null, null),
('SY', 'Syrie', 'Syria', 'Syrienne', 'Syrian', null),
('SZ', 'Eswatini', 'Eswatini', 'Eswatinienne', 'Swazi', null),
('TC', 'Îles Turques-et-Caïques', 'Turks & Caicos Islands', null, null, null),
('TD', 'Tchad', 'Chad', 'Tchadienne', 'Chadian', null),
('TF', 'Terres australes françaises', 'French Southern Territories', null, null, null),
('TG', 'Togo', 'Togo', 'Togolaise', 'Togolese', null),
('TH', 'Thaïlande', 'Thailand', 'Thaïlandaise', 'Thai', null),
('TJ', 'Tadjikistan', 'Tajikistan', null, null, null),
('TK', 'Tokelau', 'Tokelau', null, null, null),
('TL', 'Timor oriental', 'Timor-Leste', null, null, null),
('TM', 'Turkménistan', 'Turkmenistan', null, null, null),
('TN', 'Tunisie', 'Tunisia', 'Tunisienne', 'Tunisian', null),
('TO', 'Tonga', 'Tonga', null, null, null),
('TR', 'Turquie', 'Türkiye', 'Turque', 'Turkish', null),
('TT', 'Trinité-et-Tobago', 'Trinidad & Tobago', null, null, null),
('TV', 'Tuvalu', 'Tuvalu', null, null, null),
('TW', 'Taïwan', 'Taiwan', null, null, null),
('TZ', 'Tanzanie', 'Tanzania', 'Tanzanienne', 'Tanzanian', null),
('UA', 'Ukraine', 'Ukraine', 'Ukrainienne', 'Ukrainian', null),
('UG', 'Ouganda', 'Uganda', 'Ougandaise', 'Ugandan', null),
('UM', 'Îles mineures éloignées des États-Unis', 'U.S. Outlying Islands', null, null, null),
('US', 'États-Unis', 'United States', 'Américaine', 'American', null),
('UY', 'Uruguay', 'Uruguay', null, null, null),
('UZ', 'Ouzbékistan', 'Uzbekistan', null, null, null),
('VA', 'État de la Cité du Vatican', 'Vatican City', null, null, null),
('VC', 'Saint-Vincent-et-les Grenadines', 'St. Vincent & Grenadines', null, null, null),
('VE', 'Venezuela', 'Venezuela', 'Vénézuélienne', 'Venezuelan', null),
('VG', 'Îles Vierges britanniques', 'British Virgin Islands', null, null, null),
('VI', 'Îles Vierges des États-Unis', 'U.S. Virgin Islands', null, null, null),
('VN', 'Viêt Nam', 'Vietnam', 'Vietnamienne', 'Vietnamese', null),
('VU', 'Vanuatu', 'Vanuatu', null, null, null),
('WF', 'Wallis-et-Futuna', 'Wallis & Futuna', null, null, null),
('WS', 'Samoa', 'Samoa', null, null, null),
('YE', 'Yémen', 'Yemen', null, null, null),
('YT', 'Mayotte', 'Mayotte', null, null, null),
('ZA', 'Afrique du Sud', 'South Africa', 'Sud-Africaine', 'South African', null),
('ZM', 'Zambie', 'Zambia', 'Zambienne', 'Zambian', null),
('ZW', 'Zimbabwe', 'Zimbabwe', 'Zimbabwéenne', 'Zimbabwean', null);

-- DR Congo: provinces, villes (with their communes where listed) and territoires.
do $$
declare
    p bigint;
    v bigint;
begin
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Kinshasa') returning id into p;
    insert into place (country_code, parent_id, kind, name) values ('CD', p, 'CITY', 'Kinshasa') returning id into v;
    insert into place (country_code, parent_id, kind, name) select 'CD', v, 'COMMUNE', n from unnest(array['Bandalungwa', 'Barumbu', 'Bumbu', 'Gombe', 'Kalamu', 'Kasa-Vubu', 'Kimbanseke', 'Kinshasa', 'Kintambo', 'Kisenso', 'Lemba', 'Limete', 'Lingwala', 'Makala', 'Maluku', 'Masina', 'Matete', 'Mont-Ngafula', 'Ndjili', 'Ngaba', 'Ngaliema', 'Ngiri-Ngiri', 'Nsele', 'Selembao']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Kongo-Central') returning id into p;
    insert into place (country_code, parent_id, kind, name) values ('CD', p, 'CITY', 'Matadi') returning id into v;
    insert into place (country_code, parent_id, kind, name) select 'CD', v, 'COMMUNE', n from unnest(array['Matadi', 'Mvuzi', 'Nzanza']) n;
    insert into place (country_code, parent_id, kind, name) values ('CD', p, 'CITY', 'Boma') returning id into v;
    insert into place (country_code, parent_id, kind, name) select 'CD', v, 'COMMUNE', n from unnest(array['Kabondo', 'Kalamu', 'Nzadi']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Kasangulu', 'Kimvula', 'Luozi', 'Lukula', 'Madimba', 'Mbanza-Ngungu', 'Moanda', 'Seke-Banza', 'Songololo', 'Tshela']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Kwango') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'CITY', n from unnest(array['Kenge']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Feshi', 'Kahemba', 'Kasongo-Lunda', 'Kenge', 'Popokabaka']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Kwilu') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'CITY', n from unnest(array['Bandundu', 'Kikwit']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Bagata', 'Bulungu', 'Gungu', 'Idiofa', 'Masi-Manimba']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Mai-Ndombe') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'CITY', n from unnest(array['Inongo']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Bolobo', 'Inongo', 'Kiri', 'Kutu', 'Kwamouth', 'Mushie', 'Oshwe', 'Yumbi']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Équateur') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'CITY', n from unnest(array['Mbandaka']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Basankusu', 'Bikoro', 'Bolomba', 'Bomongo', 'Ingende', 'Lukolela', 'Makanza']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Sud-Ubangi') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'CITY', n from unnest(array['Gemena', 'Zongo']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Budjala', 'Gemena', 'Kungu', 'Libenge']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Nord-Ubangi') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'CITY', n from unnest(array['Gbadolite']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Bosobolo', 'Businga', 'Mobayi-Mbongo', 'Yakoma']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Mongala') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'CITY', n from unnest(array['Lisala']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Bongandanga', 'Bumba', 'Lisala']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Tshuapa') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'CITY', n from unnest(array['Boende']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Befale', 'Boende', 'Bokungu', 'Djolu', 'Ikela', 'Monkoto']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Tshopo') returning id into p;
    insert into place (country_code, parent_id, kind, name) values ('CD', p, 'CITY', 'Kisangani') returning id into v;
    insert into place (country_code, parent_id, kind, name) select 'CD', v, 'COMMUNE', n from unnest(array['Kabondo', 'Kisangani', 'Lubunga', 'Makiso', 'Mangobo', 'Tshopo']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Bafwasende', 'Banalia', 'Basoko', 'Isangi', 'Opala', 'Ubundu', 'Yahuma']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Bas-Uele') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'CITY', n from unnest(array['Buta']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Aketi', 'Ango', 'Bambesa', 'Bondo', 'Buta', 'Poko']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Haut-Uele') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'CITY', n from unnest(array['Isiro']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Dungu', 'Faradje', 'Niangara', 'Rungu', 'Wamba', 'Watsa']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Ituri') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'CITY', n from unnest(array['Bunia']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Aru', 'Djugu', 'Irumu', 'Mahagi', 'Mambasa']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Nord-Kivu') returning id into p;
    insert into place (country_code, parent_id, kind, name) values ('CD', p, 'CITY', 'Goma') returning id into v;
    insert into place (country_code, parent_id, kind, name) select 'CD', v, 'COMMUNE', n from unnest(array['Goma', 'Karisimbi']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'CITY', n from unnest(array['Beni', 'Butembo']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Beni', 'Lubero', 'Masisi', 'Nyiragongo', 'Rutshuru', 'Walikale']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Sud-Kivu') returning id into p;
    insert into place (country_code, parent_id, kind, name) values ('CD', p, 'CITY', 'Bukavu') returning id into v;
    insert into place (country_code, parent_id, kind, name) select 'CD', v, 'COMMUNE', n from unnest(array['Bagira', 'Ibanda', 'Kadutu']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Fizi', 'Idjwi', 'Kabare', 'Kalehe', 'Mwenga', 'Shabunda', 'Uvira', 'Walungu']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Maniema') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'CITY', n from unnest(array['Kindu']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Kabambare', 'Kailo', 'Kasongo', 'Kibombo', 'Lubutu', 'Pangi', 'Punia']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Haut-Katanga') returning id into p;
    insert into place (country_code, parent_id, kind, name) values ('CD', p, 'CITY', 'Lubumbashi') returning id into v;
    insert into place (country_code, parent_id, kind, name) select 'CD', v, 'COMMUNE', n from unnest(array['Annexe', 'Kamalondo', 'Kampemba', 'Katuba', 'Kenya', 'Lubumbashi', 'Rwashi']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'CITY', n from unnest(array['Likasi']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Kambove', 'Kasenga', 'Kipushi', 'Mitwaba', 'Pweto', 'Sakania']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Lualaba') returning id into p;
    insert into place (country_code, parent_id, kind, name) values ('CD', p, 'CITY', 'Kolwezi') returning id into v;
    insert into place (country_code, parent_id, kind, name) select 'CD', v, 'COMMUNE', n from unnest(array['Dilala', 'Manika']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Dilolo', 'Kapanga', 'Lubudi', 'Mutshatsha', 'Sandoa']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Haut-Lomami') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'CITY', n from unnest(array['Kamina']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Bukama', 'Kabongo', 'Kamina', 'Kaniama', 'Malemba-Nkulu']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Tanganyika') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'CITY', n from unnest(array['Kalemie']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Kabalo', 'Kalemie', 'Kongolo', 'Manono', 'Moba', 'Nyunzu']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Lomami') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'CITY', n from unnest(array['Kabinda']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Kabinda', 'Kamiji', 'Lubao', 'Luilu', 'Ngandajika']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Kasaï-Oriental') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'CITY', n from unnest(array['Mbuji-Mayi']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Kabeya-Kamwanga', 'Katanda', 'Lupatapata', 'Miabi', 'Tshilenge']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Kasaï-Central') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'CITY', n from unnest(array['Kananga']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Demba', 'Dibaya', 'Dimbelenge', 'Kazumba', 'Luiza']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Kasaï') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'CITY', n from unnest(array['Tshikapa']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Dekese', 'Ilebo', 'Kamonia', 'Luebo', 'Mweka']) n;
    insert into place (country_code, kind, name) values ('CD', 'PROVINCE', 'Sankuru') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'CITY', n from unnest(array['Lusambo']) n;
    insert into place (country_code, parent_id, kind, name) select 'CD', p, 'TERRITORY', n from unnest(array['Katako-Kombe', 'Kole', 'Lodja', 'Lomela', 'Lubefu', 'Lusambo']) n;
end $$;

-- Republic of the Congo: departments, their main towns, and the arrondissements of Brazzaville and Pointe-Noire.
do $$
declare
    p bigint;
    v bigint;
begin
    insert into place (country_code, kind, name) values ('CG', 'DEPARTMENT', 'Brazzaville') returning id into p;
    insert into place (country_code, parent_id, kind, name) values ('CG', p, 'CITY', 'Brazzaville') returning id into v;
    insert into place (country_code, parent_id, kind, name) select 'CG', v, 'ARRONDISSEMENT', n from unnest(array['Makélékélé', 'Bacongo', 'Poto-Poto', 'Moungali', 'Ouenzé', 'Talangaï', 'Mfilou', 'Madibou', 'Djiri']) n;
    insert into place (country_code, kind, name) values ('CG', 'DEPARTMENT', 'Pointe-Noire') returning id into p;
    insert into place (country_code, parent_id, kind, name) values ('CG', p, 'CITY', 'Pointe-Noire') returning id into v;
    insert into place (country_code, parent_id, kind, name) select 'CG', v, 'ARRONDISSEMENT', n from unnest(array['Lumumba', 'Mvou-Mvou', 'Tié-Tié', 'Loandjili', 'Mongo-Mpoukou', 'Ngoyo']) n;
    insert into place (country_code, kind, name) values ('CG', 'DEPARTMENT', 'Kouilou') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CG', p, 'CITY', n from unnest(array['Loango']) n;
    insert into place (country_code, kind, name) values ('CG', 'DEPARTMENT', 'Niari') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CG', p, 'CITY', n from unnest(array['Dolisie', 'Mossendjo']) n;
    insert into place (country_code, kind, name) values ('CG', 'DEPARTMENT', 'Bouenza') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CG', p, 'CITY', n from unnest(array['Madingou', 'Nkayi']) n;
    insert into place (country_code, kind, name) values ('CG', 'DEPARTMENT', 'Lékoumou') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CG', p, 'CITY', n from unnest(array['Sibiti']) n;
    insert into place (country_code, kind, name) values ('CG', 'DEPARTMENT', 'Pool') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CG', p, 'CITY', n from unnest(array['Kinkala']) n;
    insert into place (country_code, kind, name) values ('CG', 'DEPARTMENT', 'Plateaux') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CG', p, 'CITY', n from unnest(array['Djambala']) n;
    insert into place (country_code, kind, name) values ('CG', 'DEPARTMENT', 'Cuvette') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CG', p, 'CITY', n from unnest(array['Owando', 'Oyo']) n;
    insert into place (country_code, kind, name) values ('CG', 'DEPARTMENT', 'Cuvette-Ouest') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CG', p, 'CITY', n from unnest(array['Ewo']) n;
    insert into place (country_code, kind, name) values ('CG', 'DEPARTMENT', 'Sangha') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CG', p, 'CITY', n from unnest(array['Ouesso']) n;
    insert into place (country_code, kind, name) values ('CG', 'DEPARTMENT', 'Likouala') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CG', p, 'CITY', n from unnest(array['Impfondo']) n;
    insert into place (country_code, kind, name) values ('CG', 'DEPARTMENT', 'Congo-Oubangui') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CG', p, 'CITY', n from unnest(array['Mossaka']) n;
    insert into place (country_code, kind, name) values ('CG', 'DEPARTMENT', 'Nkéni-Alima') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CG', p, 'CITY', n from unnest(array['Gamboma']) n;
    insert into place (country_code, kind, name) values ('CG', 'DEPARTMENT', 'Djoué-Léfini') returning id into p;
    insert into place (country_code, parent_id, kind, name) select 'CG', p, 'CITY', n from unnest(array['Odziba']) n;
end $$;

insert into financial_institution (country_code, kind, name, short_name, aliases) values
('CD', 'BANK', 'Access Bank RDC', 'Access', 'Access Bank'),
('CD', 'BANK', 'Advans Banque Congo', 'Advans', 'Advans'),
('CD', 'BANK', 'Afriland First Bank CD', 'Afriland', 'Afriland|Afriland First Bank'),
('CD', 'BANK', 'Bank of Africa RDC', 'BOA', 'Bank of Africa|BOA RDC'),
('CD', 'BANK', 'BGFIBank RDC', 'BGFI', 'BGFI|BGFI Bank'),
('CD', 'BANK', 'Caisse Générale d''Épargne du Congo', 'CADECO', 'CADECO'),
('CD', 'BANK', 'Citigroup Congo', 'Citi', 'Citibank|Citi Bank|Citigroup'),
('CD', 'BANK', 'CRDB Bank DR Congo', 'CRDB', 'CRDB'),
('CD', 'BANK', 'Ecobank RDC', 'Ecobank', 'Ecobank|Eco Bank'),
('CD', 'BANK', 'Equity Banque Commerciale du Congo', 'Equity BCDC', 'Equity|Equity Bank|Equity BCDC|BCDC|Banque Commerciale du Congo'),
('CD', 'BANK', 'FirstBank DRC', 'FBN', 'FirstBank|First Bank|FBN Bank|FBNBank'),
('CD', 'BANK', 'Rawbank', 'Rawbank', 'Raw Bank|Rawbank SA'),
('CD', 'BANK', 'Solidaire Banque', 'Solidaire', 'Solidaire|Solidaire Banque SA'),
('CD', 'BANK', 'Sofibanque', 'Sofibanque', 'Sofibank|Sofi Banque'),
('CD', 'BANK', 'Stanbic Bank Congo', 'Stanbic', 'Standard Bank|Standard Bank Congo|Stanbic|Stanbic Bank'),
('CD', 'BANK', 'Trust Merchant Bank', 'TMB', 'TMB|Trust Merchant'),
('CD', 'BANK', 'United Bank for Africa RDC', 'UBA', 'UBA|UBA RDC'),
('CD', 'MOBILE_MONEY', 'M-Pesa (Vodacom)', 'M-Pesa', 'M-Pesa|MPesa|M Pesa|Vodacom|Vodacom M-Pesa'),
('CD', 'MOBILE_MONEY', 'Orange Money', 'Orange', 'Orange|Orange Money RDC'),
('CD', 'MOBILE_MONEY', 'Airtel Money', 'Airtel', 'Airtel|Airtel Money RDC'),
('CD', 'MOBILE_MONEY', 'Afrimoney (Africell)', 'Afrimoney', 'Africell|Afrimoney|Afri Money'),
('CG', 'BANK', 'BGFIBank Congo', 'BGFI', 'BGFI|BGFI Bank'),
('CG', 'BANK', 'Banque Commerciale Internationale', 'BCI', 'BCI'),
('CG', 'BANK', 'Banque Congolaise de l''Habitat', 'BCH', 'BCH'),
('CG', 'BANK', 'Banque Postale du Congo', 'BPC', 'BPC'),
('CG', 'BANK', 'Banque Sino-Congolaise pour l''Afrique', 'BSCA', 'BSCA|BSCA Bank'),
('CG', 'BANK', 'Crédit du Congo', 'CDC', 'Credit du Congo|CDC'),
('CG', 'BANK', 'Ecobank Congo', 'Ecobank', 'Ecobank|Eco Bank'),
('CG', 'BANK', 'La Congolaise de Banque', 'LCB', 'LCB|LCB Bank|Congolaise de Banque'),
('CG', 'BANK', 'Société Générale Congo', 'SGC', 'Societe Generale|SG Congo|SGC'),
('CG', 'BANK', 'United Bank for Africa Congo', 'UBA', 'UBA|UBA Congo'),
('CG', 'BANK', 'Mutuelle Congolaise d''Épargne et de Crédit', 'MUCODEC', 'MUCODEC'),
('CG', 'MOBILE_MONEY', 'MTN Mobile Money', 'MTN MoMo', 'MTN|MoMo|MTN Money|Mobile Money'),
('CG', 'MOBILE_MONEY', 'Airtel Money', 'Airtel', 'Airtel|Airtel Money Congo');

-- ---- the employee file -------------------------------------------------------------------------------------------
-- The text columns stay: they hold the name of what was picked (payslips, payment files and reports read them), or a
-- value typed before the lists existed, until HR picks it from the list.
alter table employee_profile
    add column nationality_code        varchar(2)  references country (code),
    add column birth_country_code      varchar(2)  references country (code),
    add column birth_place_id          bigint      references place (id),
    add column birth_place_text        varchar(100),          -- a town abroad, not in the lists
    add column address_place_id        bigint      references place (id),
    add column address_quarter         varchar(100),          -- quartier
    add column address_street          varchar(200),          -- avenue and number
    add column bank_id                 bigint      references financial_institution (id),
    add column mobile_money_operator_id bigint     references financial_institution (id),
    add column id_document_type        varchar(16) check (id_document_type in ('NATIONAL_ID', 'ELECTOR_CARD', 'PASSPORT',
                                                          'RESIDENCE_PERMIT', 'DRIVING_LICENCE', 'OTHER')),
    add column id_document_number      varchar(300),          -- encrypted, like the other identifiers
    add column id_document_issued_on   date,
    add column id_document_issued_at   varchar(100);

alter table company add column bank_id bigint references financial_institution (id);

-- ---- values typed before: matched to the lists when there is exactly one candidate -----------------------------------

-- Nationality: a country's name, its nationality (feminine or masculine), or one of its other spellings. "Congolais(e)"
-- alone is the company's own Congo.
with typed as (
    select e.user_id, ref_norm(e.nationality) as n, c.country_code as home
    from employee_profile e join company c on c.id = e.company_id
    where e.nationality is not null and e.nationality_code is null
), names as (
    select code, ref_norm(name_fr) as n from country
    union select code, ref_norm(name_en) from country
    union select code, ref_norm(nationality_fr) from country where nationality_fr is not null
    union select code, regexp_replace(ref_norm(nationality_fr), 'e$', '') from country where nationality_fr is not null
    union select code, regexp_replace(ref_norm(nationality_fr), 'nne$', 'n') from country where nationality_fr is not null
    union select code, ref_norm(nationality_en) from country where nationality_en is not null
    union select code, ref_norm(a) from country, unnest(string_to_array(aliases, '|')) a
), found as (
    select t.user_id,
           case when t.n ~ '^congolaise? (rdc|rd congo|drc|kinshasa)' then 'CD'
                when t.n ~ '^congolaise? (brazza|congo brazza)' then 'CG'
                when t.n ~ '^congolaise?$' then case when t.home in ('CD', 'CG') then t.home else 'CD' end
                else (select min(code) from names where names.n = t.n having count(distinct code) = 1) end as code
    from typed t
)
update employee_profile e set nationality_code = f.code from found f where f.user_id = e.user_id and f.code is not null;

-- Bank and mobile money operator, in the company's country: the full name, the short name or another spelling.
with names as (
    select id, country_code, kind, ref_norm(name) as n from financial_institution
    union select id, country_code, kind, ref_norm(short_name) from financial_institution where short_name is not null
    union select id, country_code, kind, ref_norm(a) from financial_institution, unnest(string_to_array(aliases, '|')) a
), found as (
    select e.user_id,
           (select min(x.id) from names x where x.country_code = c.country_code and x.kind = 'BANK'
              and x.n = ref_norm(e.bank_name) having count(distinct x.id) = 1) as bank,
           (select min(x.id) from names x where x.country_code = c.country_code and x.kind = 'MOBILE_MONEY'
              and x.n = ref_norm(e.mobile_money_operator) having count(distinct x.id) = 1) as operator
    from employee_profile e join company c on c.id = e.company_id
    where e.bank_name is not null or e.mobile_money_operator is not null
)
update employee_profile e set bank_id = coalesce(e.bank_id, f.bank),
    mobile_money_operator_id = coalesce(e.mobile_money_operator_id, f.operator)
from found f where f.user_id = e.user_id and (f.bank is not null or f.operator is not null);

update employee_profile e set bank_name = b.name from financial_institution b where b.id = e.bank_id;
update employee_profile e set mobile_money_operator = o.name from financial_institution o where o.id = e.mobile_money_operator_id;

-- Commune or territory: the current address. A commune wins over a town, a town over a territory of the same name; two places
-- of the same kind and name (Kalamu in Kinshasa and in Boma) stay for HR to pick.
with found as (
    select e.user_id,
           coalesce(
               (select min(p.id) from place p where p.country_code = c.country_code and p.company_id is null
                  and p.kind in ('COMMUNE', 'ARRONDISSEMENT') and ref_norm(p.name) = ref_norm(e.commune) having count(*) = 1),
               (select min(p.id) from place p where p.country_code = c.country_code and p.company_id is null
                  and p.kind = 'CITY' and ref_norm(p.name) = ref_norm(e.commune) having count(*) = 1),
               (select min(p.id) from place p where p.country_code = c.country_code and p.company_id is null
                  and p.kind in ('TERRITORY', 'SECTOR', 'CHIEFDOM', 'DISTRICT')
                  and ref_norm(p.name) = ref_norm(e.commune) having count(*) = 1)) as place
    from employee_profile e join company c on c.id = e.company_id
    where e.commune is not null and e.address_place_id is null
)
update employee_profile e set address_place_id = f.place from found f where f.user_id = e.user_id and f.place is not null;

update company c set bank_id = (
    select min(x.id) from financial_institution x
    where x.country_code = c.country_code and x.kind = 'BANK'
      and ref_norm(c.bank_name) in (ref_norm(x.name), ref_norm(x.short_name))
    having count(*) = 1)
where c.bank_name is not null;

-- Who may add a place or a bank for the company: HR with the master data permission (masterdata.manage, already
-- there). HR Pay's administrators keep the shared lists with platform.manage.
