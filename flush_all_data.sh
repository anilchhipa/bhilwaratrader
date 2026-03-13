#!/bin/bash

echo "Flushing data from PostgreSQL..."
export PGPASSWORD="password"

# Explicitly truncate all relevant tables
psql -U postgres -h localhost -d postgres -c "
    DROP TABLE IF EXISTS candles;
    DROP TABLE IF EXISTS data_sync_status;
    DROP TABLE IF EXISTS tracked_symbols;
"

echo "Postgres tables flush completed."

echo "Flushing data from Redis cache..."
redis-cli flushall
echo "Redis flush completed."

echo "All table data and Redis cache have been successfully flushed!"
