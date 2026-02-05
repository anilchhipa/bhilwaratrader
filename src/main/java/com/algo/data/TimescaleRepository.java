package com.algo.data;

import com.algo.core.Candle;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Repository for CRUD operations on candle data in TimescaleDB
 * Inspired by CandleRepository but designed for TimescaleDB hypertables
 */
public class TimescaleRepository {
    private final TimescaleClient timescaleClient;

    public TimescaleRepository(TimescaleClient timescaleClient) {
        this.timescaleClient = timescaleClient;
    }

    /**
     * Save a list of candles to TimescaleDB (bulk insert with duplicate handling)
     */
    public int saveCandles(List<Candle> candles, String symbol, String interval) {
        if (candles == null || candles.isEmpty()) {
            return 0;
        }

        String sql = "INSERT INTO candles (time, symbol, interval, open, high, low, close, volume, " +
                "ema15, ema15_5, ema15_15, ema15_15_15, ema15_30, ema50, ema100, " +
                "st_ma, st_up, st_dn, st_trend, macd_line, signal_line, macd_5, macd_5_5) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (time, symbol, interval) DO UPDATE SET " +
                "open = EXCLUDED.open, high = EXCLUDED.high, low = EXCLUDED.low, " +
                "close = EXCLUDED.close, volume = EXCLUDED.volume, " +
                "ema15 = EXCLUDED.ema15, ema15_5 = EXCLUDED.ema15_5, ema15_15 = EXCLUDED.ema15_15, " +
                "ema15_15_15 = EXCLUDED.ema15_15_15, ema15_30 = EXCLUDED.ema15_30, " +
                "ema50 = EXCLUDED.ema50, ema100 = EXCLUDED.ema100, " +
                "st_ma = EXCLUDED.st_ma, st_up = EXCLUDED.st_up, st_dn = EXCLUDED.st_dn, st_trend = EXCLUDED.st_trend, "
                +
                "macd_line = EXCLUDED.macd_line, signal_line = EXCLUDED.signal_line, " +
                "macd_5 = EXCLUDED.macd_5, macd_5_5 = EXCLUDED.macd_5_5";

        int savedCount = 0;
        Connection conn = null;

        try {
            conn = timescaleClient.getConnection();
            if (conn == null) {
                System.err.println("TimescaleDB connection is null. Cannot save candles.");
                return 0;
            }

            conn.setAutoCommit(false);

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                for (Candle candle : candles) {
                    pstmt.setTimestamp(1, Timestamp.valueOf(candle.getTimestamp()));
                    pstmt.setString(2, symbol);
                    pstmt.setString(3, interval);
                    pstmt.setDouble(4, candle.getOpen());
                    pstmt.setDouble(5, candle.getHigh());
                    pstmt.setDouble(6, candle.getLow());
                    pstmt.setDouble(7, candle.getClose());
                    pstmt.setLong(8, candle.getVolume());

                    // Indicators (nullable)
                    setDoubleOrNull(pstmt, 9, candle.getEma15());
                    setDoubleOrNull(pstmt, 10, candle.getEma15_5());
                    setDoubleOrNull(pstmt, 11, candle.getEma15_15());
                    setDoubleOrNull(pstmt, 12, candle.getEma15_15_15());
                    setDoubleOrNull(pstmt, 13, candle.getEma15_30());
                    setDoubleOrNull(pstmt, 14, candle.getEma50());
                    setDoubleOrNull(pstmt, 15, candle.getEma100());
                    setDoubleOrNull(pstmt, 16, candle.getStMa());
                    setDoubleOrNull(pstmt, 17, candle.getStUp());
                    setDoubleOrNull(pstmt, 18, candle.getStDn());
                    setIntegerOrNull(pstmt, 19, candle.getStTrend());
                    setDoubleOrNull(pstmt, 20, candle.getMacdLine());
                    setDoubleOrNull(pstmt, 21, candle.getSignalLine());
                    setDoubleOrNull(pstmt, 22, candle.getMacd5());
                    setDoubleOrNull(pstmt, 23, candle.getMacd55());

                    pstmt.addBatch();
                }

                int[] results = pstmt.executeBatch();
                for (int result : results) {
                    if (result > 0)
                        savedCount++;
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }

            // Update sync status
            updateSyncStatus(symbol, interval);

        } catch (SQLException e) {
            System.err.println("Error saving candles to TimescaleDB: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }

        return savedCount;
    }

    /**
     * Get the latest N candles for a symbol and interval
     */
    public List<Candle> getLatestCandles(String symbol, String interval, int count) {
        List<Candle> candles = new ArrayList<>();
        String sql = "SELECT time, open, high, low, close, volume, " +
                "ema15, ema15_5, ema15_15, ema15_15_15, ema15_30, ema50, ema100, " +
                "st_ma, st_up, st_dn, st_trend, macd_line, signal_line, macd_5, macd_5_5 " +
                "FROM candles " +
                "WHERE symbol = ? AND interval = ? " +
                "ORDER BY time DESC " +
                "LIMIT ?";

        Connection conn = null;
        try {
            conn = timescaleClient.getConnection();
            if (conn == null)
                return candles;

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, symbol);
                pstmt.setString(2, interval);
                pstmt.setInt(3, count);

                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    Candle candle = new Candle(
                            rs.getDouble("open"),
                            rs.getDouble("high"),
                            rs.getDouble("low"),
                            rs.getDouble("close"),
                            rs.getLong("volume"),
                            rs.getTimestamp("time").toLocalDateTime());

                    // Load indicators
                    candle.setEma15(getDoubleOrNull(rs, "ema15"));
                    candle.setEma15_5(getDoubleOrNull(rs, "ema15_5"));
                    candle.setEma15_15(getDoubleOrNull(rs, "ema15_15"));
                    candle.setEma15_15_15(getDoubleOrNull(rs, "ema15_15_15"));
                    candle.setEma15_30(getDoubleOrNull(rs, "ema15_30"));
                    candle.setEma50(getDoubleOrNull(rs, "ema50"));
                    candle.setEma100(getDoubleOrNull(rs, "ema100"));
                    candle.setStMa(getDoubleOrNull(rs, "st_ma"));
                    candle.setStUp(getDoubleOrNull(rs, "st_up"));
                    candle.setStDn(getDoubleOrNull(rs, "st_dn"));
                    candle.setStTrend(getIntegerOrNull(rs, "st_trend"));
                    candle.setMacdLine(getDoubleOrNull(rs, "macd_line"));
                    candle.setSignalLine(getDoubleOrNull(rs, "signal_line"));
                    candle.setMacd5(getDoubleOrNull(rs, "macd_5"));
                    candle.setMacd55(getDoubleOrNull(rs, "macd_5_5"));

                    candles.add(candle);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error fetching candles from TimescaleDB: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }

        // Reverse to get chronological order
        List<Candle> reversed = new ArrayList<>();
        for (int i = candles.size() - 1; i >= 0; i--) {
            reversed.add(candles.get(i));
        }

        return reversed;
    }

    /**
     * Get the timestamp of the last candle for a symbol/interval
     */
    public LocalDateTime getLastCandleTime(String symbol, String interval) {
        String sql = "SELECT MAX(time) as last_time FROM candles WHERE symbol = ? AND interval = ?";
        Connection conn = null;

        try {
            conn = timescaleClient.getConnection();
            if (conn == null)
                return null;

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, symbol);
                pstmt.setString(2, interval);

                ResultSet rs = pstmt.executeQuery();
                if (rs.next() && rs.getTimestamp("last_time") != null) {
                    return rs.getTimestamp("last_time").toLocalDateTime();
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting last candle time: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }

        return null;
    }

    /**
     * Get the count of candles for a symbol/interval
     */
    public int getCandleCount(String symbol, String interval) {
        String sql = "SELECT COUNT(*) as count FROM candles WHERE symbol = ? AND interval = ?";
        Connection conn = null;

        try {
            conn = timescaleClient.getConnection();
            if (conn == null)
                return 0;

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, symbol);
                pstmt.setString(2, interval);

                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    return rs.getInt("count");
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting candle count: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }

        return 0;
    }

    /**
     * Update sync status metadata
     */
    private void updateSyncStatus(String symbol, String interval) {
        String sql = "INSERT INTO data_sync_status (symbol, interval, earliest_candle, latest_candle, total_candles, last_sync_attempt) "
                +
                "SELECT ?, ?, MIN(time), MAX(time), COUNT(*), NOW() " +
                "FROM candles WHERE symbol = ? AND interval = ? " +
                "ON CONFLICT (symbol, interval) DO UPDATE SET " +
                "earliest_candle = EXCLUDED.earliest_candle, " +
                "latest_candle = EXCLUDED.latest_candle, " +
                "total_candles = EXCLUDED.total_candles, " +
                "last_sync_attempt = EXCLUDED.last_sync_attempt";

        Connection conn = null;
        try {
            conn = timescaleClient.getConnection();
            if (conn == null)
                return;

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, symbol);
                pstmt.setString(2, interval);
                pstmt.setString(3, symbol);
                pstmt.setString(4, interval);
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            System.err.println("Error updating sync status: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    // Helper methods for nullable values
    private void setDoubleOrNull(PreparedStatement pstmt, int index, Double value) throws SQLException {
        if (value != null) {
            pstmt.setDouble(index, value);
        } else {
            pstmt.setNull(index, Types.DOUBLE);
        }
    }

    private void setIntegerOrNull(PreparedStatement pstmt, int index, Integer value) throws SQLException {
        if (value != null) {
            pstmt.setInt(index, value);
        } else {
            pstmt.setNull(index, Types.INTEGER);
        }
    }

    private Double getDoubleOrNull(ResultSet rs, String columnName) throws SQLException {
        double value = rs.getDouble(columnName);
        return rs.wasNull() ? null : value;
    }

    private Integer getIntegerOrNull(ResultSet rs, String columnName) throws SQLException {
        int value = rs.getInt(columnName);
        return rs.wasNull() ? null : value;
    }
}
