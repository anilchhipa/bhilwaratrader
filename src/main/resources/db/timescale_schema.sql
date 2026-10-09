-- TimescaleDB Schema for Candle Data with Indicators
-- This creates a hypertable optimized for time-series data

-- Create main candles table
CREATE TABLE IF NOT EXISTS candles (
    time TIMESTAMPTZ NOT NULL,
    symbol TEXT NOT NULL,
    interval TEXT NOT NULL,
    open DOUBLE PRECISION NOT NULL,
    high DOUBLE PRECISION NOT NULL,
    low DOUBLE PRECISION NOT NULL,
    close DOUBLE PRECISION NOT NULL,
    volume BIGINT NOT NULL,
    -- EMA Indicators
    ema15 DOUBLE PRECISION,
    ema15_5 DOUBLE PRECISION,
    ema15_15 DOUBLE PRECISION,
    ema15_15_15 DOUBLE PRECISION,
    ema50 DOUBLE PRECISION,
    ema100 DOUBLE PRECISION,
    -- SuperTrend Indicators
    st_ma DOUBLE PRECISION,
    st_up DOUBLE PRECISION,
    st_dn DOUBLE PRECISION,
    st_trend INTEGER,
    -- MACD Indicators
    macd_line DOUBLE PRECISION,
    signal_line DOUBLE PRECISION,
    macd_5 DOUBLE PRECISION,
    st_trend2 INTEGER,
    atr DOUBLE PRECISION,
    signal INTEGER,
    trade_score DOUBLE PRECISION,
    er DOUBLE PRECISION,
    PRIMARY KEY (time, symbol, interval)
);

-- Convert to hypertable (TimescaleDB-specific feature)
-- This enables automatic partitioning and time-series optimizations
SELECT create_hypertable('candles', 'time', if_not_exists => TRUE);

-- Create indexes for fast queries
CREATE INDEX IF NOT EXISTS idx_candles_symbol_interval_time 
    ON candles (symbol, interval, time DESC);

CREATE INDEX IF NOT EXISTS idx_candles_time 
    ON candles (time DESC);

-- Optional: Enable compression for older data (saves storage)
-- ALTER TABLE candles SET (
--     timescaledb.compress,
--     timescaledb.compress_segmentby = 'symbol,interval'
-- );

-- Optional: Add compression policy (compress data older than 7 days)
-- SELECT add_compression_policy('candles', INTERVAL '7 days');

-- Create metadata table for tracking sync status
CREATE TABLE IF NOT EXISTS data_sync_status (
    symbol TEXT NOT NULL,
    interval TEXT NOT NULL,
    earliest_candle TIMESTAMPTZ,
    latest_candle TIMESTAMPTZ,
    total_candles INTEGER,
    last_sync_attempt TIMESTAMPTZ,
    PRIMARY KEY (symbol, interval)
);
