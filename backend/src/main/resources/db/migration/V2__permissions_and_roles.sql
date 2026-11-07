-- Every permission the product will use. Code checks permissions, never role names.
insert into permission (code, category, description) values
    ('user.read',                 'users',      'View users'),
    ('user.create',               'users',      'Create users'),
    ('user.update',               'users',      'Edit users and reset passwords'),
    ('user.disable',              'users',      'Disable and re-enable users'),
    ('role.read',                 'roles',      'View roles and permissions'),
    ('role.manage',               'roles',      'Create, edit and delete roles'),
    ('role.assign',               'roles',      'Assign any role to a user (without it, only the Employee role)'),
    ('site.read',                 'sites',      'View sites'),
    ('site.manage',               'sites',      'Manage sites, allowed IP ranges and GPS areas'),
    ('audit.read',                'audit',      'View the audit log'),
    ('schedule.manage',           'attendance', 'Manage work schedules'),
    ('holiday.manage',            'attendance', 'Manage public holidays and observed dates'),
    ('overtime_policy.manage',    'attendance', 'Manage overtime rules and rates'),
    ('attendance.read.all',       'attendance', 'View attendance of all employees'),
    ('attendance.read.team',      'attendance', 'View attendance of own team'),
    ('attendance.read.own',       'attendance', 'View own attendance'),
    ('attendance.correct',        'attendance', 'Correct attendance with a reason'),
    ('attendance.correct.propose','attendance', 'Propose an attendance correction'),
    ('overtime.approve.all',      'attendance', 'Approve overtime for all employees'),
    ('overtime.approve.team',     'attendance', 'Approve overtime for own team'),
    ('mission.approve.team',      'attendance', 'Approve missions for own team'),
    ('leave.approve.team',        'attendance', 'Approve leave for own team'),
    ('leave.manage',              'attendance', 'Manage leave types, policies and balances'),
    ('report.attendance.export',  'reports',    'Export attendance reports'),
    ('report.finance.export',     'reports',    'Export finance reports'),
    ('salary.read',               'payroll',    'View salaries'),
    ('salary.write',              'payroll',    'Edit salaries'),
    ('salary.read.own',           'payroll',    'View own salary'),
    ('masterdata.manage',         'payroll',    'Manage grades, grade categories and occupations'),
    ('payroll.run',               'payroll',    'Calculate a payroll run'),
    ('payroll.approve',           'payroll',    'Approve a payroll run'),
    ('payroll.close',             'payroll',    'Close a payroll month'),
    ('loan.manage',               'payroll',    'Manage loans and advances'),
    ('payslip.read.all',          'payroll',    'View all payslips'),
    ('payslip.read.own',          'payroll',    'View own payslips'),
    ('evaluation.manage',         'evaluation', 'Manage evaluation criteria and templates'),
    ('evaluation.score.team',     'evaluation', 'Score evaluations for own team'),
    ('evaluation.approve',        'evaluation', 'Approve evaluations and decide contests');

insert into role (code, name, description, system_role) values
    ('ADMIN',    'Administrator', 'Platform configuration, users and roles', true),
    ('HR',       'Human resources', 'Employees, schedules, payroll', true),
    ('FINANCE',  'Finance', 'Payroll approval and finance reports', true),
    ('MANAGER',  'Manager', 'Team attendance, approvals and evaluations', true),
    ('EMPLOYEE', 'Employee', 'Own attendance, leave and payslips', true);

insert into role_permission (role_id, permission_code)
select r.id, p.code
from role r
join (values
    ('ADMIN', 'user.read'), ('ADMIN', 'user.create'), ('ADMIN', 'user.update'), ('ADMIN', 'user.disable'),
    ('ADMIN', 'role.read'), ('ADMIN', 'role.manage'), ('ADMIN', 'role.assign'),
    ('ADMIN', 'site.read'), ('ADMIN', 'site.manage'), ('ADMIN', 'audit.read'),
    ('ADMIN', 'schedule.manage'), ('ADMIN', 'holiday.manage'), ('ADMIN', 'overtime_policy.manage'),
    ('ADMIN', 'attendance.read.all'), ('ADMIN', 'report.attendance.export'),

    ('HR', 'user.read'), ('HR', 'user.create'), ('HR', 'user.update'), ('HR', 'user.disable'),
    ('HR', 'role.read'), ('HR', 'site.read'), ('HR', 'audit.read'),
    ('HR', 'schedule.manage'), ('HR', 'holiday.manage'),
    ('HR', 'attendance.read.all'), ('HR', 'attendance.correct'), ('HR', 'overtime.approve.all'),
    ('HR', 'leave.manage'), ('HR', 'report.attendance.export'), ('HR', 'report.finance.export'),
    ('HR', 'salary.read'), ('HR', 'salary.write'), ('HR', 'masterdata.manage'),
    ('HR', 'payroll.run'), ('HR', 'payroll.close'), ('HR', 'loan.manage'), ('HR', 'payslip.read.all'),
    ('HR', 'evaluation.manage'), ('HR', 'evaluation.approve'),

    ('FINANCE', 'user.read'), ('FINANCE', 'attendance.read.all'), ('FINANCE', 'salary.read'),
    ('FINANCE', 'report.finance.export'), ('FINANCE', 'payroll.approve'), ('FINANCE', 'payslip.read.all'),

    ('MANAGER', 'attendance.read.team'), ('MANAGER', 'attendance.correct.propose'),
    ('MANAGER', 'overtime.approve.team'), ('MANAGER', 'mission.approve.team'), ('MANAGER', 'leave.approve.team'),
    ('MANAGER', 'evaluation.score.team'), ('MANAGER', 'report.attendance.export'),

    ('EMPLOYEE', 'attendance.read.own'), ('EMPLOYEE', 'salary.read.own'), ('EMPLOYEE', 'payslip.read.own')
) as m(role_code, permission_code) on m.role_code = r.code
join permission p on p.code = m.permission_code;
