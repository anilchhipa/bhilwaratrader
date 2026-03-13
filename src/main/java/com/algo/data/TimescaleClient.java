package com.algo.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import com.algo.core.Candle;

/**
 * Handles communication with TimescaleDB for persistent storage.
 */
public class TimescaleClient {
    private final HikariDataSource dataSource;

    public TimescaleClient(String url, String user, String password) {
        HikariDataSource ds = null;
        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(url);
            config.setUsername(user);
            config.setPassword(password);
            config.setMaximumPoolSize(20);
            config.setInitializationFailTimeout(0); // Don't crash on start
            ds = new HikariDataSource(config);
        } catch (Exception e) {
            System.err.println("TimescaleDB Connection Failed: " + e.getMessage());
        }
        this.dataSource = ds;
    }

    /**
     * Efficiently bulk saves 1-minute candles.
     */
    public void save1MinCandles(String symbol, List<Candle> candles) {
        if (dataSource == null)
            return;
        String sql = "INSERT INTO ohlc_data (time, symbol, open, high, low, close, volume) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (time, symbol) DO NOTHING";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)) {

            for (Candle c : candles) {
                pstmt.setTimestamp(1, Timestamp.valueOf(c.getTimestamp()));
                pstmt.setString(2, symbol);
                pstmt.setDouble(3, c.getOpen());
                pstmt.setDouble(4, c.getHigh());
                pstmt.setDouble(5, c.getLow());
                pstmt.setDouble(6, c.getClose());
                pstmt.setLong(7, c.getVolume());
                pstmt.addBatch();
            }
            pstmt.executeBatch();
        } catch (SQLException e) {
            System.err.println("TimescaleDB Save Error: " + e.getMessage());
        }
    }

    /**
     * Saves calculated indicators for EOD analysis.
     */
    public void saveIndicators(String symbol, String interval, LocalDateTime time,
            java.util.Map<String, Double> values) {
        // Implementation for indicator storage would go here
    }

    /**
     * Get a database connection for repository use
     */
    public Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("TimescaleDB connection pool is not initialized");
        }
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
