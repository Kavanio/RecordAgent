-- 分类颜色按用户与收支类型保存；历史分类为空时由界面使用稳定的默认配色。
ALTER TABLE finance_transaction_category ADD COLUMN color TEXT
    CHECK (color IS NULL OR (
        length(color) = 7 AND substr(color, 1, 1) = '#'
        AND substr(color, 2) NOT GLOB '*[^0-9A-Fa-f]*'
    ));
