#!/bin/bash

# Define colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}=== Starting Data Reset ===${NC}"

# Confirm with user
read -p "Are you sure you want to DELETE ALL DATA? This cannot be undone. (y/N) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo -e "${RED}Operation cancelled.${NC}"
    exit 1
fi

# 1. Clear PostgreSQL Data
echo -e "\n${YELLOW}Step 1: Clearing PostgreSQL Data...${NC}"
sudo -u postgres psql -d postgres -f clear_all_data.sql

if [ $? -eq 0 ]; then
    echo -e "${GREEN}PostgreSQL data cleared successfully.${NC}"
else
    echo -e "${RED}Failed to clear PostgreSQL data.${NC}"
    exit 1
fi

# 2. Clear Redis Cache
echo -e "\n${YELLOW}Step 2: Clearing Redis Cache...${NC}"
redis-cli FLUSHALL

if [ $? -eq 0 ]; then
    echo -e "${GREEN}Redis cache cleared successfully.${NC}"
else
    echo -e "${RED}Failed to clear Redis cache.${NC}"
    exit 1
fi

echo -e "\n${GREEN}=== Data Reset Complete ===${NC}"
