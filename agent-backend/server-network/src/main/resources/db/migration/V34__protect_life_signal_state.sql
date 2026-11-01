-- 历史授权只能保留范围与身份，不得保留旧版误存的邮箱密码。
UPDATE life_review_grant SET settings_json=json_remove(settings_json,'$.imapPassword');
-- 信号基线绑定来源身份，防止更换探针或邮箱后沿用旧状态。
ALTER TABLE life_signal_state ADD COLUMN source_fingerprint TEXT NOT NULL DEFAULT '';
