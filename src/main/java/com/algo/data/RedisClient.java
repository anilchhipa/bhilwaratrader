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

    /**
     * Pushes the latest state of a symbol to Redis.
     */
    public void pushState(String symbol, String interval, JSONObject ohlc, JSONObject indicators) {
        if (jedisPool == null)
            return;
        try (Jedis jedis = jedisPool.getResource()) {
            String ohlcKey = symbol + ":" + interval + ":OHLC";
            String indKey = symbol + ":" + interval + ":INDICATORS";

            // Use HMSET for efficient batched O(1) storage
            java.util.Map<String, String> ohlcMap = new java.util.HashMap<>();
            for (String key : ohlc.keySet()) {
                ohlcMap.put(key, String.valueOf(ohlc.get(key)));
            }
            if (!ohlcMap.isEmpty())
                jedis.hmset(ohlcKey, ohlcMap);

            java.util.Map<String, String> indMap = new java.util.HashMap<>();
            for (String key : indicators.keySet()) {
                indMap.put(key, String.valueOf(indicators.get(key)));
            }
            if (!indMap.isEmpty())
                jedis.hmset(indKey, indMap);
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

    public void close() {
        if (jedisPool != null) {
            jedisPool.close();
        }
    }
}
