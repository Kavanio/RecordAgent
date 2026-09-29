-- 手记与资料条目使用同一张表、同一个 ID；日历只查询 journal 类型投影。
CREATE TABLE record_agent.record_tab (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES record_agent.app_user(id),
    name VARCHAR(80) NOT NULL CHECK (length(btrim(name)) > 0),
    system_key VARCHAR(32),
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_id, id),
    UNIQUE (owner_id, name),
    UNIQUE (owner_id, system_key)
);
CREATE INDEX idx_record_tab_owner_order ON record_agent.record_tab (owner_id, sort_order, created_at);

CREATE TABLE record_agent.record_entry (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES record_agent.app_user(id),
    tab_id UUID,
    record_date DATE NOT NULL,
    record_type VARCHAR(24) NOT NULL CHECK (record_type IN (
        'quick', 'learning', 'weekly_review', 'reading', 'journal')),
    title VARCHAR(240),
    content_html TEXT NOT NULL DEFAULT '',
    content_text TEXT NOT NULL DEFAULT '',
    week_year INTEGER,
    week_number SMALLINT,
    pinned BOOLEAN NOT NULL DEFAULT false,
    favorite BOOLEAN NOT NULL DEFAULT false,
    archived BOOLEAN NOT NULL DEFAULT false,
    trashed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_id, id),
    CONSTRAINT fk_record_entry_tab FOREIGN KEY (owner_id, tab_id)
        REFERENCES record_agent.record_tab(owner_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_record_week CHECK (
        (record_type = 'weekly_review' AND week_year IS NOT NULL AND week_number BETWEEN 1 AND 53)
        OR (record_type <> 'weekly_review' AND week_year IS NULL AND week_number IS NULL))
);
CREATE INDEX idx_record_entry_owner_date
    ON record_agent.record_entry (owner_id, record_date DESC, updated_at DESC)
    WHERE trashed_at IS NULL;
CREATE INDEX idx_record_entry_owner_tab
    ON record_agent.record_entry (owner_id, tab_id, record_date DESC)
    WHERE trashed_at IS NULL;
CREATE INDEX idx_record_entry_owner_type_state
    ON record_agent.record_entry (owner_id, record_type, archived, updated_at DESC)
    WHERE trashed_at IS NULL;
CREATE INDEX idx_record_entry_owner_week
    ON record_agent.record_entry (owner_id, week_year, week_number)
    WHERE record_type = 'weekly_review' AND trashed_at IS NULL;

CREATE TABLE record_agent.record_tag (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES record_agent.app_user(id),
    name VARCHAR(80) NOT NULL CHECK (length(btrim(name)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_id, id),
    UNIQUE (owner_id, name)
);

CREATE TABLE record_agent.record_entry_tag (
    owner_id UUID NOT NULL REFERENCES record_agent.app_user(id),
    record_id UUID NOT NULL,
    tag_id UUID NOT NULL,
    PRIMARY KEY (owner_id, record_id, tag_id),
    CONSTRAINT fk_record_entry_tag_entry FOREIGN KEY (owner_id, record_id)
        REFERENCES record_agent.record_entry(owner_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_record_entry_tag_tag FOREIGN KEY (owner_id, tag_id)
        REFERENCES record_agent.record_tag(owner_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_record_entry_tag_tag ON record_agent.record_entry_tag (owner_id, tag_id);
