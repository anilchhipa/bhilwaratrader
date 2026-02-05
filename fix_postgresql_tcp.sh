#!/bin/bash
# Fix PostgreSQL to listen on TCP port 5432

echo "=== Configuring PostgreSQL to accept TCP connections ==="

# Find PostgreSQL config file
PG_CONF=$(sudo -u postgres psql -t -P format=unaligned -c 'SHOW config_file;')
PG_HBA=$(sudo -u postgres psql -t -P format=unaligned -c 'SHOW hba_file;')

echo "PostgreSQL config file: $PG_CONF"
echo "PostgreSQL HBA file: $PG_HBA"

# Backup original files
echo "Creating backups..."
sudo cp "$PG_CONF" "${PG_CONF}.backup"
sudo cp "$PG_HBA" "${PG_HBA}.backup"

# Enable TCP listening on localhost
echo "Enabling TCP connections..."
sudo sed -i "s/#listen_addresses = 'localhost'/listen_addresses = 'localhost'/" "$PG_CONF"
sudo sed -i "s/listen_addresses = ''/listen_addresses = 'localhost'/" "$PG_CONF"

# Add host-based authentication for localhost
echo "Configuring authentication..."
if ! sudo grep -q "host.*all.*all.*127.0.0.1/32.*trust" "$PG_HBA"; then
    echo "host    all             all             127.0.0.1/32            trust" | sudo tee -a "$PG_HBA"
fi

# Restart PostgreSQL
echo "Restarting PostgreSQL..."
sudo systemctl restart postgresql
sleep 3

# Verify
echo ""
echo "=== Verification ==="
if ss -tuln | grep -q 5432; then
    echo "✓ PostgreSQL is now listening on TCP port 5432"
    ss -tuln | grep 5432
else
    echo "✗ PostgreSQL is still not listening on port 5432"
    echo "Checking PostgreSQL status..."
    sudo systemctl status postgresql
fi

echo ""
echo "Testing connection..."
psql -h localhost -U postgres -d postgres -c "SELECT version();" 2>&1 | head -3

echo ""
echo "=== Configuration Complete ==="
echo "You can now restart your Spring Boot application"
