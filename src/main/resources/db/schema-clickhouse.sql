CREATE TABLE IF NOT EXISTS processing_events
(
    event_id UUID,
    attempt_id UUID,
    note_id UUID,
    occurred_at DateTime64(3, 'UTC'),
    action LowCardinality(String),
    stage LowCardinality(String),
    provider LowCardinality(String),
    model LowCardinality(String),
    success UInt8,
    duration_ms UInt64,
    input_tokens Nullable(UInt64),
    output_tokens Nullable(UInt64),
    error_type LowCardinality(String)
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(occurred_at)
ORDER BY (stage, toDate(occurred_at), provider, model, occurred_at, event_id)
TTL toDateTime(occurred_at) + INTERVAL 90 DAY DELETE
