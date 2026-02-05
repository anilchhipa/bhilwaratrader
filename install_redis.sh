#!/bin/bash
echo "Installing Redis Server..."
sudo apt-get update
sudo apt-get install -y redis-server
sudo systemctl start redis-server
echo "Redis installation complete. Status:"
sudo systemctl status redis-server --no-pager
