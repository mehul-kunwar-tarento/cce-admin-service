-- Data every environment starts with: the console menus, the SUPER_ADMIN role and its
-- permission rows. The admin user is not here: it is linked from Keycloak at startup.

-- Menus: the 10 sidebar items of cce-insights-ui, in sidebar order
INSERT INTO menus (key, label, path, icon, type, sort_order) VALUES
    ('dashboard',     'Dashboard',     '/',                    'ChartBarIcon',               'NAV', 10),
    ('compliance',    'Compliance',    '/compliance',          'ClipboardDocumentCheckIcon', 'NAV', 20),
    ('facilities',    'Facilities',    '/facilities',          'BuildingOffice2Icon',        'NAV', 30),
    ('practitioners', 'Practitioners', '/practitioners',       'UserGroupIcon',              'NAV', 40),
    ('deviations',    'Deviations',    '/deviations',          'ExclamationTriangleIcon',    'NAV', 50),
    ('patients',      'Patients',      '/compliance/patients', 'ClipboardDocumentCheckIcon', 'NAV', 60),
    ('intelligence',  'Intelligence',  '/intelligence',        'BoltIcon',                   'NAV', 70),
    ('events',        'Events',        '/events',              'SignalIcon',                 'NAV', 80),
    ('ingestion',     'Ingestion',     '/ingestion',           'CogIcon',                    'NAV', 90),
    ('exports',       'Exports',       '/exports',             'ArrowDownTrayIcon',          'NAV', 100);

-- Menus: the 2 drill-down pages. No sidebar entry, so no icon; sort_order right after the parent.
-- The parent's id is random, so it is looked up by the parent's key.
INSERT INTO menus (key, label, path, type, parent_id, sort_order)
SELECT 'protocol-detail', 'Protocol analytics', '/compliance/protocols/:id', 'DETAIL', id, 21
FROM menus WHERE key = 'compliance';

INSERT INTO menus (key, label, path, type, parent_id, sort_order)
SELECT 'patient-detail', 'Patient detail', '/compliance/patients/:id', 'DETAIL', id, 61
FROM menus WHERE key = 'patients';

-- The only role at start. A system role: it can't be changed or deleted through the API.
-- No menus: it is an admin-console role, and the admin console's sidebar is fixed.
INSERT INTO roles (name, display_name, description, is_system)
VALUES ('SUPER_ADMIN', 'Super Admin', 'Full access to the admin API.', true);

-- SUPER_ADMIN may call every method under /admin/v1/. The table may already hold rows on a
-- server (gateway-service's), so each row is added only if the same row isn't there yet.
INSERT INTO api_permissions (permission_name, http_method, uri_pattern, description, resource_category)
SELECT 'SUPER_ADMIN', 'GET', '/admin/v1/**', 'Read admin resources', 'Admin'
WHERE NOT EXISTS (SELECT 1 FROM api_permissions
                  WHERE permission_name = 'SUPER_ADMIN' AND http_method = 'GET' AND uri_pattern = '/admin/v1/**');

INSERT INTO api_permissions (permission_name, http_method, uri_pattern, description, resource_category)
SELECT 'SUPER_ADMIN', 'POST', '/admin/v1/**', 'Create admin resources', 'Admin'
WHERE NOT EXISTS (SELECT 1 FROM api_permissions
                  WHERE permission_name = 'SUPER_ADMIN' AND http_method = 'POST' AND uri_pattern = '/admin/v1/**');

INSERT INTO api_permissions (permission_name, http_method, uri_pattern, description, resource_category)
SELECT 'SUPER_ADMIN', 'PUT', '/admin/v1/**', 'Replace admin resources', 'Admin'
WHERE NOT EXISTS (SELECT 1 FROM api_permissions
                  WHERE permission_name = 'SUPER_ADMIN' AND http_method = 'PUT' AND uri_pattern = '/admin/v1/**');

INSERT INTO api_permissions (permission_name, http_method, uri_pattern, description, resource_category)
SELECT 'SUPER_ADMIN', 'PATCH', '/admin/v1/**', 'Update admin resources', 'Admin'
WHERE NOT EXISTS (SELECT 1 FROM api_permissions
                  WHERE permission_name = 'SUPER_ADMIN' AND http_method = 'PATCH' AND uri_pattern = '/admin/v1/**');

INSERT INTO api_permissions (permission_name, http_method, uri_pattern, description, resource_category)
SELECT 'SUPER_ADMIN', 'DELETE', '/admin/v1/**', 'Delete admin resources', 'Admin'
WHERE NOT EXISTS (SELECT 1 FROM api_permissions
                  WHERE permission_name = 'SUPER_ADMIN' AND http_method = 'DELETE' AND uri_pattern = '/admin/v1/**');
