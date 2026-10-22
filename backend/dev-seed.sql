-- dev-only invite so Auth0 login can claim it (auth_subject set on first login)
INSERT INTO company (name)
SELECT 'Dev Company'
WHERE NOT EXISTS (SELECT 1 FROM company WHERE name = 'Dev Company');

INSERT INTO user_account (company_id, email, display_name, is_company_admin, auth_subject)
SELECT id, 'brunocmartins@live.com.pt', 'Bruno Martins', true, NULL
FROM company WHERE name = 'Dev Company' ORDER BY created_at LIMIT 1
ON CONFLICT (email) DO NOTHING;
