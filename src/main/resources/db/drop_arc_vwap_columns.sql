-- Migration script to remove deprecated Arc VWAP columns from TimescaleDB
-- Run this if you wish to reclaim storage in PostgreSQL / TimescaleDB:

ALTER TABLE candles 
    DROP COLUMN IF EXISTS arc_value,
    DROP COLUMN IF EXISTS arc_trend,
    DROP COLUMN IF EXISTS arc_flip_confirmed,
    DROP COLUMN IF EXISTS vwap_session,
    DROP COLUMN IF EXISTS vwap_week,
    DROP COLUMN IF EXISTS vwap_month;
