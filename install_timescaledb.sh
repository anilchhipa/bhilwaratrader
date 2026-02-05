#!/bin/bash
# TimescaleDB Installation Script for Ubuntu

echo "=== Installing PostgreSQL and TimescaleDB ==="

# Step 1: Update package list
echo "Step 1: Updating package list..."
sudo apt update

# Step 2: Install PostgreSQL
echo "Step 2: Installing PostgreSQL..."
sudo apt install -y postgresql postgresql-contrib

# Step 3: Add TimescaleDB repository
echo "Step 3: Adding TimescaleDB repository..."
sudo sh -c "echo 'deb [signed-by=/usr/share/keyrings/timescaledb.gpg] https://packagecloud.io/timescale/timescaledb/ubuntu/ $(lsb_release -c -s) main' > /etc/apt/sources.list.d/timescaledb.list"

# Download and add GPG key
wget --quiet -O - https://packagecloud.io/timescale/timescaledb/gpgkey | sudo gpg --dearmor -o /usr/share/keyrings/timescaledb.gpg

# Step 4: Update and install TimescaleDB
echo "Step 4: Installing TimescaleDB..."
sudo apt update
sudo apt install -y timescaledb-2-postgresql-14

# Step 5: Configure TimescaleDB
echo "Step 5: Configuring TimescaleDB..."
sudo timescaledb-tune --quiet --yes

# Step 6: Restart PostgreSQL
echo "Step 6: Restarting PostgreSQL..."
sudo systemctl restart postgresql
sudo systemctl enable postgresql

# Step 7: Create TimescaleDB extension
echo "Step 7: Creating TimescaleDB extension..."
sudo -u postgres psql -d postgres -c "CREATE EXTENSION IF NOT EXISTS timescaledb;"

# Step 8: Run schema script
echo "Step 8: Creating database schema..."
sudo -u postgres psql -d postgres -f /home/anil-chhipa/Desktop/Algo/src/main/resources/db/timescale_schema.sql

# Step 9: Verify installation
echo "Step 9: Verifying installation..."
sudo -u postgres psql -d postgres -c "SELECT default_version, installed_version FROM pg_available_extensions WHERE name = 'timescaledb';"

echo ""
echo "=== Installation Complete! ==="
echo "PostgreSQL is running on port 5432"
echo "You can now restart your Spring Boot application"
