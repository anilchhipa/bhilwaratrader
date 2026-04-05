#!/bin/bash
# ─────────────────────────────────────────────
#  Algo Trading Bot - Start Script
#  Runs with Oracle GraalVM (high-performance runtime)
# ─────────────────────────────────────────────

export JAVA_HOME="/home/anil-chhipa/graalvm"
export PATH="$JAVA_HOME/bin:$PATH"
JAR="target/trading-algo-1.0-SNAPSHOT.jar"

echo "Building latest changes with GraalVM..."
mvn clean package -DskipTests -q

if [ ! -f "$JAR" ]; then
  echo "Build failed. JAR not found."
  exit 1
fi

# Configuration: Telegram Alerts
export TELEGRAM_BOT_TOKEN="8311779416:AAEbw6JNO-NJl3ZmY5mj-kRQGmK-FZ0qNbQ"
export TELEGRAM_CHAT_ID="787284320" 

echo "Starting Algo Trading Backend with ZGC..."
java -XX:+UseZGC -jar "$JAR"
