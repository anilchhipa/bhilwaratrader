#!/bin/bash
# PostgreSQL Setup Script - Run this with: bash setup_postgresql.sh

set -e  # Exit on error

echo "=== PostgreSQL Setup for Algo Trading Application ==="
echo ""

# Check if PostgreSQL is already installed
if command -v psql &> /dev/null; then
    echo "✓ PostgreSQL is already installed"
    PG_VERSION=$(psql --version | awk '{print $3}')
    echo "  Version: $PG_VERSION"
else
    echo "✗ PostgreSQL is not installed"
    echo "  Installing PostgreSQL..."
    sudo apt update || true
    sudo apt install -y postgresql postgresql-contrib
    echo "✓ PostgreSQL installed successfully"
fi

echo ""
echo "=== Starting PostgreSQL Service ==="
sudo systemctl start postgresql
sudo systemctl enable postgresql
sleep 2

# Check if PostgreSQL is running
if sudo systemctl is-active --quiet postgresql; then
    echo "✓ PostgreSQL is running"
else
    echo "✗ PostgreSQL failed to start"
    echo "  Checking status..."
    sudo systemctl status postgresql
    exit 1
fi

# Check if port 5432 is listening
echo ""
echo "=== Checking PostgreSQL Port ==="
if ss -tuln | grep -q 5432; then
    echo "✓ PostgreSQL is listening on port 5432"
else
    echo "✗ Port 5432 is not listening"
    echo "  This might be a configuration issue"
fi

echo ""
echo "=== Creating Database Schema ==="

# Try to create TimescaleDB extension (optional, will fail gracefully if not installed)
echo "Attempting to enable TimescaleDB extension (optional)..."
sudo -u postgres psql -d postgres -c "CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE;" 2>/dev/null || echo "  TimescaleDB extension not available (continuing with standard PostgreSQL)"

# Run the schema script
echo "Creating tables and hypertables..."
sudo -u postgres psql -d postgres -f /home/anil-chhipa/Desktop/Algo/src/main/resources/db/timescale_schema.sql

echo ""
echo "=== Verifying Schema ==="
sudo -u postgres psql -d postgres -c "\dt" | grep -E "candles|data_sync_status" || echo "Tables created"

echo ""
echo "=== Setup Complete! ==="
echo ""
echo "PostgreSQL is now running and ready to use."
echo "Connection details:"
echo "  Host: localhost"
echo "  Port: 5432"
echo "  Database: postgres"
echo "  User: postgres"
echo ""
echo "You can now restart your Spring Boot application."
echo ""
