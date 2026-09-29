-- 资产账户与流水同属一个用户；余额调整与流水必须由服务层在同一事务提交。
CREATE TABLE record_agent.finance_account (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES record_agent.app_user(id),
    name VARCHAR(120) NOT NULL CHECK (length(btrim(name)) > 0),
    account_type VARCHAR(32) NOT NULL,
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    balance_minor BIGINT NOT NULL DEFAULT 0 CHECK (balance_minor >= 0),
    interest_enabled BOOLEAN NOT NULL DEFAULT false,
    annual_rate_percent NUMERIC(9, 6) NOT NULL DEFAULT 0 CHECK (annual_rate_percent >= 0),
    last_accrual_date DATE,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_id, id),
    UNIQUE (owner_id, name)
);
CREATE INDEX idx_finance_account_owner ON record_agent.finance_account (owner_id, created_at);

CREATE TABLE record_agent.finance_category (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES record_agent.app_user(id),
    transaction_type VARCHAR(16) NOT NULL CHECK (transaction_type IN ('expense', 'income')),
    name VARCHAR(80) NOT NULL CHECK (length(btrim(name)) > 0),
    color VARCHAR(7) CHECK (color ~ '^#[0-9A-Fa-f]{6}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_id, id),
    UNIQUE (owner_id, id, transaction_type),
    UNIQUE (owner_id, transaction_type, name)
);

-- 一笔流水对应一个账户的余额变化；划账用同一 group_id 的出入两笔表达。
CREATE TABLE record_agent.finance_transaction (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES record_agent.app_user(id),
    account_id UUID NOT NULL,
    group_id UUID,
    transaction_date DATE NOT NULL,
    transaction_time TIME NOT NULL,
    transaction_type VARCHAR(24) NOT NULL CHECK (transaction_type IN (
        'expense', 'income', 'transfer_out', 'transfer_in',
        'adjustment_decrease', 'adjustment_increase', 'yield')),
    category_id UUID,
    note TEXT NOT NULL DEFAULT '',
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    source VARCHAR(24) NOT NULL DEFAULT 'manual',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_finance_transaction_account FOREIGN KEY (owner_id, account_id)
        REFERENCES record_agent.finance_account(owner_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_finance_transaction_category FOREIGN KEY (owner_id, category_id, transaction_type)
        REFERENCES record_agent.finance_category(owner_id, id, transaction_type) ON DELETE RESTRICT,
    CONSTRAINT ck_finance_transaction_group CHECK (
        (transaction_type IN ('transfer_out', 'transfer_in')) = (group_id IS NOT NULL)),
    CONSTRAINT ck_finance_transaction_category CHECK (
        category_id IS NULL OR transaction_type IN ('expense', 'income'))
);
CREATE INDEX idx_finance_transaction_owner_date
    ON record_agent.finance_transaction (owner_id, transaction_date DESC, transaction_time DESC, id DESC);
CREATE INDEX idx_finance_transaction_account_date
    ON record_agent.finance_transaction (owner_id, account_id, transaction_date DESC);
CREATE UNIQUE INDEX uq_finance_transfer_leg
    ON record_agent.finance_transaction (group_id, transaction_type)
    WHERE group_id IS NOT NULL;
CREATE UNIQUE INDEX uq_finance_daily_yield
    ON record_agent.finance_transaction (account_id, transaction_date)
    WHERE transaction_type = 'yield';
