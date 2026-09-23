-- 模板配置与邮件正文快照独立；重试不重新生成内容。
ALTER TABLE automation_task ADD COLUMN mail_template TEXT NOT NULL DEFAULT '{}';
ALTER TABLE automation_run ADD COLUMN email_html TEXT NOT NULL DEFAULT '';
ALTER TABLE automation_run ADD COLUMN email_queued_at INTEGER NOT NULL DEFAULT 0;
UPDATE automation_run SET email_queued_at=created_at;
