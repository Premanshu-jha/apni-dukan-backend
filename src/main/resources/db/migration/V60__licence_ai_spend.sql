-- On a live database: wait at most 3 s for each table's lock (see V17 and "Database changes" in the README).
SET LOCAL lock_timeout = '3s';

-- What the licence-reading AI has cost, per credit period (the Max plan's API credits arrive on the same day each
-- month, LICENCE_AI_BUDGET_DAY), added up from the tokens each answer reports (AiCreditWatch): the super admins are
-- emailed at 80% and 100% of the period's budget (budget_notice: 0 none, 1 the 80% one sent, 2 the 100% one sent).
CREATE TABLE licence_ai_spend (
    period_start  DATE PRIMARY KEY,
    reads         INT    NOT NULL DEFAULT 0 CHECK (reads >= 0),
    input_tokens  BIGINT NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens BIGINT NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    cost_micros   BIGINT NOT NULL DEFAULT 0 CHECK (cost_micros >= 0),
    budget_notice SMALLINT NOT NULL DEFAULT 0 CHECK (budget_notice BETWEEN 0 AND 2)
);

-- Whether the AI is refusing our account now (out of credit, a wrong key, a wrong model), since when, when its email
-- was last claimed (again later while it lasts), and whether any email about it reached someone (then "working
-- again" is sent when it answers). One row.
CREATE TABLE licence_ai_alert (
    id            SMALLINT PRIMARY KEY CHECK (id = 1),
    problem       TEXT CHECK (problem IN ('CREDIT', 'KEY', 'MODEL')),
    problem_since TIMESTAMPTZ,
    emailed_at    TIMESTAMPTZ,
    announced     BOOLEAN NOT NULL DEFAULT false,
    -- A "working again" email owed (it reached nobody when the AI answered again): the problem it ends, since when
    owed_problem  TEXT,
    owed_since    TIMESTAMPTZ,
    CHECK ((problem IS NULL) = (problem_since IS NULL)),
    CHECK ((owed_problem IS NULL) = (owed_since IS NULL))
);
INSERT INTO licence_ai_alert (id) VALUES (1);
