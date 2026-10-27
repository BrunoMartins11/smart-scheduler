-- Email uniqueness moves from global to per company, so an invite never reveals accounts in other companies.
ALTER TABLE user_account DROP CONSTRAINT user_account_email_key;
ALTER TABLE user_account ADD CONSTRAINT user_account_company_email_key UNIQUE (company_id, email);
