-- 划账产生的转出与转入流水共用同一 pair_id，删除时据此成对回滚两侧账户余额。
ALTER TABLE finance_transaction ADD COLUMN pair_id TEXT;

CREATE INDEX idx_finance_transaction_pair
    ON finance_transaction (owner_id, pair_id);

-- 历史划账没有关联标识：同一次划账写出的两条流水共用相同的日期、时间与金额，
-- 以配对转出行的自身 id 作为该批次 pair_id 回填，配不上的保留 NULL 由服务层兜底匹配。
UPDATE finance_transaction SET pair_id = id WHERE transaction_type = 'transfer_out';

UPDATE finance_transaction AS incoming
SET pair_id = (
    SELECT outgoing.id
    FROM finance_transaction AS outgoing
    WHERE outgoing.owner_id = incoming.owner_id
      AND outgoing.transaction_type = 'transfer_out'
      AND outgoing.transaction_date = incoming.transaction_date
      AND outgoing.transaction_time = incoming.transaction_time
      AND outgoing.amount_minor = incoming.amount_minor
      AND outgoing.account_id <> incoming.account_id
      AND outgoing.id NOT IN (
          SELECT linked.pair_id
          FROM finance_transaction AS linked
          WHERE linked.transaction_type = 'transfer_in'
            AND linked.pair_id IS NOT NULL
            AND linked.id <> incoming.id
      )
    ORDER BY outgoing.created_at, outgoing.id
    LIMIT 1
)
WHERE transaction_type = 'transfer_in';
