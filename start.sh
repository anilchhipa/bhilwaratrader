#!/bin/bash
# ─────────────────────────────────────────────
#  Algo Trading Bot - Start Script
#  Runs with ZGC (low-latency GC) by default
# ─────────────────────────────────────────────

JAR="target/trading-algo-1.0-SNAPSHOT.jar"

echo "Building latest changes..."
mvn clean package -DskipTests -q

if [ ! -f "$JAR" ]; then
  echo "Build failed. JAR not found."
  exit 1
fi

echo "Starting Algo Trading Backend with ZGC..."
java -XX:+UseZGC -jar "$JAR"
