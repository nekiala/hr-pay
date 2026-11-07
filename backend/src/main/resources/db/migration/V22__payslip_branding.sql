-- Payslips carry the company's logo, contact details and legal information, in one of three layouts.
alter table company add column phone             varchar(40);
alter table company add column email             varchar(150);
alter table company add column website           varchar(150);
alter table company add column legal_form        varchar(200);   -- e.g. "SARL au capital de 10 000 USD"
alter table company add column payslip_footer    varchar(300);
alter table company add column payslip_template  varchar(16) not null default 'CLASSIC'
    check (payslip_template in ('CLASSIC', 'COMPACT', 'LEDGER'));
alter table company add column logo              bytea;
alter table company add column logo_content_type varchar(40);
