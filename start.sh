#!/bin/bash
# ─────────────────────────────────────────────
#  Algo Trading Bot - Start Script
#  Runs with ZGC (low-latency GC) by default
# ─────────────────────────────────────────────

JAR="target/trading-algo-1.0-SNAPSHOT.jar"

if [ ! -f "$JAR" ]; then
  echo "JAR not found. Building first..."
  mvn clean package -DskipTests
fi

echo "Starting Algo Trading Backend with ZGC..."
java -XX:+UseZGC -jar "$JAR"
