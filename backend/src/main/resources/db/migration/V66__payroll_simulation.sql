-- Simulating one person's pay (any pay type, with "what if" changes) and projecting the year, without saving anything.
-- HR gets it; the Roles page can give it to others.
insert into permission (code, category, description) values
    ('payroll.simulate', 'payroll', 'Simulate and project an employee''s pay (nothing is saved)');
insert into role_permission (company_id, role_id, permission_code)
select r.company_id, r.id, 'payroll.simulate' from role r where r.code = 'HR';
