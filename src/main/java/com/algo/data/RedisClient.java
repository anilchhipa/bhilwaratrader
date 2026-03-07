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
            poolConfig.setMaxTotal(5);
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

            // Use HSET for efficient storage
            for (String key : ohlc.keySet()) {
                jedis.hset(ohlcKey, key, ohlc.get(key).toString());
            }

            for (String key : indicators.keySet()) {
                jedis.hset(indKey, key, indicators.get(key).toString());
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
        } catch (Exception e) {
            System.err.println("Redis Cache Error: " + e.getMessage());
        }
    }

    public void clearSymbolData(String symbol) {
        if (jedisPool == null)
            return;
        try (Jedis jedis = jedisPool.getResource()) {
            // Find all keys for this symbol (OHLC, INDICATORS, HISTORY)
            String pattern = symbol + ":*";
            java.util.Set<String> keys = jedis.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                jedis.del(keys.toArray(new String[0]));
                System.out.println("RedisClient: Flushed " + keys.size() + " keys for " + symbol);
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
