-- MVP schema from DATA_MODEL.md §3, invariants from §6.
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE company (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamptz NOT NULL DEFAULT now(),
    name       text NOT NULL
);

CREATE TABLE user_account (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at       timestamptz NOT NULL DEFAULT now(),
    company_id       uuid NOT NULL REFERENCES company (id),
    email            citext NOT NULL UNIQUE,
    auth_subject     text UNIQUE,
    display_name     text NOT NULL,
    locale           text NOT NULL DEFAULT 'pt-PT',
    is_company_admin boolean NOT NULL DEFAULT false,
    deactivated_at   timestamptz
);

CREATE TABLE team (
    id                             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at                     timestamptz NOT NULL DEFAULT now(),
    company_id                     uuid NOT NULL REFERENCES company (id),
    name                           text NOT NULL,
    timezone                       text NOT NULL DEFAULT 'Europe/Lisbon',
    municipal_holiday              text CHECK (municipal_holiday ~ '^\d{2}-\d{2}$'),
    min_rest_between_shifts_min    integer NOT NULL DEFAULT 720,
    max_consecutive_work_min       integer NOT NULL DEFAULT 720,
    max_avg_weekly_work_min        integer NOT NULL DEFAULT 2880,
    avg_reference_weeks            smallint NOT NULL DEFAULT 26,
    min_weekly_rest_min            integer NOT NULL DEFAULT 1440,
    max_consecutive_nights         smallint,
    rest_after_night_run_min       integer
);

CREATE TABLE team_member (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at   timestamptz NOT NULL DEFAULT now(),
    team_id      uuid NOT NULL REFERENCES team (id),
    user_id      uuid NOT NULL REFERENCES user_account (id),
    role         text NOT NULL CHECK (role IN ('MANAGER', 'MEMBER')),
    works_shifts boolean NOT NULL DEFAULT false,
    fte_percent  smallint NOT NULL CHECK (fte_percent BETWEEN 1 AND 100),
    active       boolean NOT NULL DEFAULT true,
    UNIQUE (team_id, user_id)
);

CREATE TABLE shift_template (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at          timestamptz NOT NULL DEFAULT now(),
    team_id             uuid NOT NULL REFERENCES team (id),
    name                text NOT NULL,
    start_time          time NOT NULL,
    end_time            time NOT NULL,
    is_night            boolean NOT NULL DEFAULT false,
    default_min_staff   smallint NOT NULL,
    default_max_staff   smallint NOT NULL,
    active              boolean NOT NULL DEFAULT true,
    CHECK (0 <= default_min_staff AND default_min_staff <= default_max_staff)
);

CREATE TABLE schedule_period (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at     timestamptz NOT NULL DEFAULT now(),
    team_id        uuid NOT NULL REFERENCES team (id),
    start_date     date NOT NULL,
    end_date       date NOT NULL,
    status         text NOT NULL DEFAULT 'DRAFT'
                   CHECK (status IN ('DRAFT', 'OPEN', 'CLOSED', 'PUBLISHED')),
    input_deadline timestamptz,
    published_at   timestamptz,
    version        integer NOT NULL DEFAULT 1,
    CHECK (end_date >= start_date),
    EXCLUDE USING gist (team_id WITH =, daterange(start_date, end_date, '[]') WITH &&)
);

CREATE TABLE shift_slot (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at  timestamptz NOT NULL DEFAULT now(),
    period_id   uuid NOT NULL REFERENCES schedule_period (id),
    template_id uuid NOT NULL REFERENCES shift_template (id),
    date        date NOT NULL,
    min_staff   smallint NOT NULL,
    max_staff   smallint NOT NULL,
    UNIQUE (period_id, template_id, date),
    CHECK (0 <= min_staff AND min_staff <= max_staff)
);

CREATE TABLE availability (
    slot_id        uuid NOT NULL REFERENCES shift_slot (id),
    team_member_id uuid NOT NULL REFERENCES team_member (id),
    created_at     timestamptz NOT NULL DEFAULT now(),
    kind           text NOT NULL CHECK (kind IN ('AVAILABLE', 'IF_NEEDED')),
    entered_by     uuid NOT NULL REFERENCES user_account (id),
    PRIMARY KEY (slot_id, team_member_id)
);

CREATE TABLE assignment (
    slot_id          uuid NOT NULL REFERENCES shift_slot (id),
    team_member_id   uuid NOT NULL REFERENCES team_member (id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    source           text NOT NULL CHECK (source IN ('ENGINE', 'MANUAL')),
    override_comment text,
    confirmed_at     timestamptz,
    actual_start_at  timestamptz,
    actual_end_at    timestamptz,
    created_by       uuid NOT NULL REFERENCES user_account (id),
    PRIMARY KEY (slot_id, team_member_id)
);

-- Append-only: a trigger rather than REVOKE, because the app connects as the
-- table owner (the compose user), and owners keep their privileges after a REVOKE.
CREATE TABLE audit_event (
    id             bigserial PRIMARY KEY,
    company_id     uuid NOT NULL REFERENCES company (id),
    team_id        uuid REFERENCES team (id),
    actor_user_id  uuid REFERENCES user_account (id),
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    entity_type    text NOT NULL,
    entity_id      text NOT NULL,
    action         text NOT NULL,
    payload        jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE FUNCTION audit_event_reject_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_event_append_only
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_reject_change();

-- TRUNCATE skips row-level triggers, so it needs its own statement-level trigger.
CREATE TRIGGER audit_event_no_truncate
    BEFORE TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION audit_event_reject_change();
