-- 手机提醒的配置是服务端事实，通知执行由每台 iPhone 的系统通知中心负责。
CREATE TABLE record.mobile_task (
 id UUID PRIMARY KEY, owner_id UUID NOT NULL REFERENCES record.app_user(id),
 title VARCHAR(120) NOT NULL CHECK(length(btrim(title))>0), body VARCHAR(500) NOT NULL DEFAULT '',
 recurrence VARCHAR(16) NOT NULL CHECK(recurrence IN ('once','daily','weekly','monthly')),
 hour SMALLINT NOT NULL CHECK(hour BETWEEN 0 AND 23), minute SMALLINT NOT NULL CHECK(minute BETWEEN 0 AND 59),
 weekday SMALLINT, day_of_month SMALLINT, once_date DATE, time_zone VARCHAR(64) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT true, version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),
 deleted_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(owner_id,id),
 CHECK((recurrence='weekly' AND weekday BETWEEN 1 AND 7 AND weekday IS NOT NULL) OR (recurrence<>'weekly' AND weekday IS NULL)),
 CHECK((recurrence='monthly' AND day_of_month BETWEEN 1 AND 28 AND day_of_month IS NOT NULL) OR (recurrence<>'monthly' AND day_of_month IS NULL)),
 CHECK((recurrence='once')=(once_date IS NOT NULL))
);
CREATE INDEX idx_mobile_task_owner ON record.mobile_task(owner_id,created_at DESC,id DESC) WHERE deleted_at IS NULL;
CREATE TABLE record.mobile_task_delivery (
 owner_id UUID NOT NULL, task_id UUID NOT NULL, event_id VARCHAR(160) NOT NULL, delivered_at TIMESTAMPTZ NOT NULL,
 observed_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(owner_id,task_id,event_id),
 FOREIGN KEY(owner_id,task_id) REFERENCES record.mobile_task(owner_id,id)
);
CREATE INDEX idx_mobile_task_delivery_history ON record.mobile_task_delivery(owner_id,task_id,delivered_at DESC);
