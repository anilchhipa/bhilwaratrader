-- Script to delete all data from all tables
-- This will clear all candle data and sync status

-- Delete all data from candles table
TRUNCATE TABLE candles;

-- Delete all data from data_sync_status table
TRUNCATE TABLE data_sync_status;

-- Verify deletion
SELECT 'candles' as table_name, COUNT(*) as row_count FROM candles
UNION ALL
SELECT 'data_sync_status' as table_name, COUNT(*) as row_count FROM data_sync_status;
