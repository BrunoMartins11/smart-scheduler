-- dev-only invite so Auth0 login can claim it (auth_subject set on first login)
INSERT INTO company (name)
SELECT 'Dev Company'
WHERE NOT EXISTS (SELECT 1 FROM company WHERE name = 'Dev Company');

INSERT INTO user_account (company_id, email, display_name, is_company_admin, auth_subject)
SELECT c.id, u.email, u.display_name, u.is_admin, NULL
FROM (SELECT id FROM company WHERE name = 'Dev Company' ORDER BY created_at LIMIT 1) c,
     (VALUES ('brunocmartins@live.com.pt', 'Bruno Martins', true),
             ('ana.dev@example.com', 'Ana Silva', false),
             ('rui.dev@example.com', 'Rui Costa', false)) AS u (email, display_name, is_admin)
ON CONFLICT (company_id, email) DO NOTHING;

-- dev team so the Team page has something to show
INSERT INTO team (company_id, name)
SELECT id, 'Urgência'
FROM company WHERE name = 'Dev Company'
  AND NOT EXISTS (SELECT 1 FROM team t JOIN company c ON c.id = t.company_id WHERE c.name = 'Dev Company');

INSERT INTO team_member (team_id, user_id, role, works_shifts, fte_percent)
SELECT t.id, u.id, m.role, m.works_shifts, m.fte
FROM team t
JOIN company c ON c.id = t.company_id AND c.name = 'Dev Company'
JOIN (VALUES ('brunocmartins@live.com.pt', 'MANAGER', false, 100),
             ('ana.dev@example.com', 'MEMBER', true, 100),
             ('rui.dev@example.com', 'MEMBER', true, 50)) AS m (email, role, works_shifts, fte) ON true
JOIN user_account u ON u.company_id = c.id AND u.email = m.email
ON CONFLICT (team_id, user_id) DO NOTHING;
