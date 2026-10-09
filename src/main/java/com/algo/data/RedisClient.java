package com.algo.data;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import org.json.JSONObject;

/**
 * Handles communication with Redis for live data caching.
 */
public class RedisClient {
    private final JedisPool jedisPool;

    public RedisClient(String host, int port) {
        JedisPool pool = null;
        try {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(50); // Increased from 5
            poolConfig.setMaxIdle(20);
            pool = new JedisPool(poolConfig, host, port);
        } catch (Exception e) {
            System.err.println("Redis Initialization Failed: " + e.getMessage());
        }
        this.jedisPool = pool;
    }

    private static final long DEFAULT_KEY_TTL_SECONDS = 259200L; // 3 days auto-eviction for options/intraday contracts

    /**
     * Pushes multiple timeframe updates to Redis in a single network pipeline.
     * Dramatically reduces round-trip latency.
     */
    public void pushStatePipelined(String symbol, java.util.Set<String> intervals,
            java.util.Map<String, com.algo.core.Candle> currentCandles,
            java.util.Map<String, java.util.Map<String, Double>> indicatorValues) {
        if (jedisPool == null)
            return;

        try (Jedis jedis = jedisPool.getResource()) {
            redis.clients.jedis.Pipeline p = jedis.pipelined();
            for (String interval : intervals) {
                com.algo.core.Candle candle = currentCandles.get(interval);
                if (candle == null)
                    continue;

                String ohlcKey = symbol + ":" + interval + ":OHLC";
                String indKey = symbol + ":" + interval + ":INDICATORS";

                // OHLC Update
                java.util.Map<String, String> ohlcMap = new java.util.HashMap<>();
                ohlcMap.put("o", String.valueOf(candle.getOpen()));
                ohlcMap.put("h", String.valueOf(candle.getHigh()));
                ohlcMap.put("l", String.valueOf(candle.getLow()));
                ohlcMap.put("c", String.valueOf(candle.getClose()));
                ohlcMap.put("a", String.valueOf(candle.getAtr()));
                ohlcMap.put("v", String.valueOf(candle.getVolume()));
                ohlcMap.put("t", String.valueOf(
                        candle.getTimestamp().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()));
                p.hset(ohlcKey, ohlcMap);
                p.expire(ohlcKey, DEFAULT_KEY_TTL_SECONDS);

                // Indicators Update
                java.util.Map<String, Double> indicators = indicatorValues.get(interval);
                if (indicators != null) {
                    java.util.Map<String, String> indMap = new java.util.HashMap<>();
                    for (java.util.Map.Entry<String, Double> entry : indicators.entrySet()) {
                        indMap.put(entry.getKey(), String.valueOf(entry.getValue()));
                    }
                    if (!indMap.isEmpty()) {
                        p.hset(indKey, indMap);
                        p.expire(indKey, DEFAULT_KEY_TTL_SECONDS);
                    }
                }
            }
            p.sync();
        } catch (Exception e) {
            System.err.println("Redis Pipelining Error: " + e.getMessage());
        }
    }

    /**
     * Pushes the latest state of a symbol to Redis.
     */
    public void pushState(String symbol, String interval, JSONObject ohlc, JSONObject indicators) {
        if (jedisPool == null)
            return;
        try (Jedis jedis = jedisPool.getResource()) {
            String ohlcKey = symbol + ":" + interval + ":OHLC";
            String indKey = symbol + ":" + interval + ":INDICATORS";

            // Use HSET for efficient batched O(1) storage
            java.util.Map<String, String> ohlcMap = new java.util.HashMap<>();
            for (String key : ohlc.keySet()) {
                ohlcMap.put(key, String.valueOf(ohlc.get(key)));
            }
            if (!ohlcMap.isEmpty()) {
                jedis.hset(ohlcKey, ohlcMap);
                jedis.expire(ohlcKey, DEFAULT_KEY_TTL_SECONDS);
            }

            java.util.Map<String, String> indMap = new java.util.HashMap<>();
            for (String key : indicators.keySet()) {
                indMap.put(key, String.valueOf(indicators.get(key)));
            }
            if (!indMap.isEmpty()) {
                jedis.hset(indKey, indMap);
                jedis.expire(indKey, DEFAULT_KEY_TTL_SECONDS);
            }
        } catch (Exception e) {
            System.err.println("Redis Error: " + e.getMessage());
        }
    }

    /**
     * Caches a list of recent candles for quick chart loading.
     */
    public void cacheCandle(String symbol, String interval, JSONObject candle) {
        if (jedisPool == null)
            return;
        try (Jedis jedis = jedisPool.getResource()) {
            String key = symbol + ":" + interval + ":HISTORY";
            jedis.lpush(key, candle.toString());
            jedis.ltrim(key, 0, 99); // Keep only last 100
            jedis.expire(key, DEFAULT_KEY_TTL_SECONDS);
        } catch (Exception e) {
            System.err.println("Redis Cache Error: " + e.getMessage());
        }
    }

    public void clearSymbolData(String symbol) {
        if (jedisPool == null)
            return;
        try (Jedis jedis = jedisPool.getResource()) {
            // Use non-blocking SCAN instead of dangerous blocking KEYS command
            String pattern = symbol + ":*";
            String cursor = "0";
            java.util.List<String> allKeys = new java.util.ArrayList<>();
            do {
                redis.clients.jedis.params.ScanParams scanParams = new redis.clients.jedis.params.ScanParams()
                        .match(pattern).count(100);
                redis.clients.jedis.resps.ScanResult<String> result = jedis.scan(cursor, scanParams);
                cursor = result.getCursor();
                allKeys.addAll(result.getResult());
            } while (!"0".equals(cursor));

            if (!allKeys.isEmpty()) {
                jedis.del(allKeys.toArray(new String[0]));
                System.out.println("RedisClient: Flushed " + allKeys.size() + " keys for " + symbol);
            }
        } catch (Exception e) {
            System.err.println("Redis Flush Error: " + e.getMessage());
        }
    }

    public void flushAll() {
        if (jedisPool == null)
            return;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.flushAll();
            System.out.println("RedisClient: FLUSHED ALL REDIS DATA");
        } catch (Exception e) {
            System.err.println("Redis Flush All Error: " + e.getMessage());
        }
    }

    public String get(String key) {
        if (jedisPool == null)
            return null;
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.get(key);
        } catch (Exception e) {
            return null;
        }
    }

    public void setEx(String key, int seconds, String value) {
        if (jedisPool == null)
            return;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setex(key, seconds, value);
        } catch (Exception e) {
        }
    }

    public void close() {
        if (jedisPool != null) {
            jedisPool.close();
        }
    }
}
