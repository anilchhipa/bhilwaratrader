#!/bin/bash
# ─────────────────────────────────────────────
#  Algo Trading Bot - Start Script
#  Runs with Oracle GraalVM (high-performance runtime)
# ─────────────────────────────────────────────

export JAVA_HOME="/home/anil-chhipa/graalvm"
export PATH="$JAVA_HOME/bin:$PATH"
JAR="target/trading-algo-1.0-SNAPSHOT.jar"

# Configuration: Telegram Alerts
export TELEGRAM_BOT_TOKEN="8311779416:AAEbw6JNO-NJl3ZmY5mj-kRQGmK-FZ0qNbQ"
export TELEGRAM_CHAT_ID="@bhilwaratrader"

echo ">>> Building Bhilwara Engine with GraalVM JIT..."
mvn clean package -DskipTests -q

if [ ! -f "$JAR" ]; then
  echo "Build failed. JAR not found."
  exit 1
fi

echo ">>> Starting Algo Trading Backend with Oracle GraalVM JIT + ZGC..."
# UseZGC: Zero-latency GC pauses (<1ms)
# -Dspring.native.enabled=false as a fallback
java -XX:+UseZGC -XX:+UnlockExperimentalVMOptions \
     -XX:+EnableJVMCI -XX:+UseJVMCICompiler \
     -jar "$JAR"
