-- The yearly tax summaries, beside the workforce filings of V68.
--
-- DRC: with the December declaration, the employer sends the year's summary of the IPR withheld, an individual sheet
-- per worker and the nominal list (Loi 004/2003 art. 18); the December declaration is due within 15 days, so by
-- 15 January. Republic of the Congo: the annual statement of salaries paid (DAS) is filed in January (CGI art. 176).

alter table filing_rule drop constraint filing_rule_kind_check,
    add constraint filing_rule_kind_check check (kind in ('HIRE', 'DEPARTURE', 'WORKFORCE', 'BILAN_SOCIAL', 'TAX_ANNUAL'));
alter table legal_filing drop constraint legal_filing_kind_check,
    add constraint legal_filing_kind_check check (kind in ('HIRE', 'DEPARTURE', 'WORKFORCE', 'BILAN_SOCIAL', 'TAX_ANNUAL'));
alter table legal_filing drop constraint legal_filing_check1,
    add constraint legal_filing_check1 check ((kind in ('WORKFORCE', 'BILAN_SOCIAL', 'TAX_ANNUAL')) = (year is not null and user_id is null));

insert into filing_rule (country_code, kind, authority, due_days, remind_after_days, due_month, due_day, year_offset, legal_basis, verified) values
    ('CD', 'TAX_ANNUAL', 'DGI', null, null, 1, 15, 1,
     'Loi 004/2003 art. 18: avec la déclaration de décembre, relevé récapitulatif annuel, fiches individuelles et liste nominative; déclaration dans les 15 jours (art. 17); pénalités art. 89 et 91', true),
    ('CG', 'TAX_ANNUAL', 'Direction générale des impôts', null, null, 1, 31, 1,
     'CGI art. 176: déclaration annuelle des salaires (DAS) en janvier; date exacte à confirmer', false);
