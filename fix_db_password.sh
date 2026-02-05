#!/bin/bash
# Fix PostgreSQL Password

echo "=== Setting PostgreSQL Password ==="
echo "Setting password for user 'postgres' to 'password'..."

sudo -u postgres psql -c "ALTER USER postgres PASSWORD 'password';"

if [ $? -eq 0 ]; then
    echo "✓ Password set successfully!"
else
    echo "✗ Failed to set password."
    exit 1
fi

echo ""
echo "=== Verifying Connection ==="
export PGPASSWORD=password
psql -h localhost -U postgres -d postgres -c "SELECT version();"

if [ $? -eq 0 ]; then
    echo "✓ Connection verified!"
    echo "You can now restart your application."
else
    echo "✗ Connection failed. Please check pg_hba.conf or firewall."
fi
