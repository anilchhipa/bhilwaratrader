#!/bin/bash

echo "Stopping any running backend server..."
pkill -f "trading-algo-1.0-SNAPSHOT.jar" || true
pkill -f "com.algo.TradingApplication" || true
pkill -f "spring-boot:run" || true
fuser -k 8080/tcp 2>/dev/null || echo "No backend server was running on port 8080."

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
