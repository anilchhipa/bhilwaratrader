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
     * Ensures all necessary tables and hypertables exist in TimescaleDB.
     */
    public void initializeSchema() {
        Connection conn = null;
        try {
            conn = timescaleClient.getConnection();
            if (conn == null)
                return;
            try (Statement stmt = conn.createStatement()) {
                // Table: tracked_symbols
                stmt.execute("CREATE TABLE IF NOT EXISTS tracked_symbols (" +
                        "symbol TEXT PRIMARY KEY, " +
                        "exchange TEXT NOT NULL" +
                        ")");

                // Table: candles
                stmt.execute("CREATE TABLE IF NOT EXISTS candles (" +
                        "time TIMESTAMPTZ NOT NULL, " +
                        "symbol TEXT NOT NULL, " +
                        "interval TEXT NOT NULL, " +
                        "open DOUBLE PRECISION NOT NULL, " +
                        "high DOUBLE PRECISION NOT NULL, " +
                        "low DOUBLE PRECISION NOT NULL, " +
                        "close DOUBLE PRECISION NOT NULL, " +
                        "ema15 DOUBLE PRECISION, " +
                        "ema15_5 DOUBLE PRECISION, " +
                        "ema15_15 DOUBLE PRECISION, " +
                        "ema15_15_15 DOUBLE PRECISION, " +
                        "ema15_15_15_15 DOUBLE PRECISION, " +
                        "ema15_15_15_15_30 DOUBLE PRECISION, " +
                        "st_ma DOUBLE PRECISION, " +
                        "st_up DOUBLE PRECISION, " +
                        "st_dn DOUBLE PRECISION, " +
                        "st_trend INTEGER, " +
                        "st_ma2 DOUBLE PRECISION, " +
                        "st_up2 DOUBLE PRECISION, " +
                        "st_dn2 DOUBLE PRECISION, " +
                        "st_trend2 INTEGER, " +
                        "PRIMARY KEY (time, symbol, interval)" +
                        ")");

                // Convert to hypertable (catch error if already hypertable)
                try {
                    stmt.execute("SELECT create_hypertable('candles', 'time', if_not_exists => TRUE)");
                } catch (SQLException e) {
                    // Ignore if already hypertable or if TimescaleDB extension is not fully active
                }

                // Indexes
                stmt.execute(
                        "CREATE INDEX IF NOT EXISTS idx_candles_symbol_interval_time ON candles (symbol, interval, time DESC)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_candles_time ON candles (time DESC)");

                // Table: data_sync_status
                stmt.execute("CREATE TABLE IF NOT EXISTS data_sync_status (" +
                        "symbol TEXT NOT NULL, " +
                        "interval TEXT NOT NULL, " +
                        "earliest_candle TIMESTAMPTZ, " +
                        "latest_candle TIMESTAMPTZ, " +
                        "total_candles INTEGER, " +
                        "last_sync_attempt TIMESTAMPTZ, " +
                        "PRIMARY KEY (symbol, interval)" +
                        ")");

                // Pre-populate indices and top 10 SENSEX heavyweight components
                String[][] defaultSymbols = {
                        { "SENSEX", "BSE" },
                        { "HDFCBANK", "BSE" },
                        { "RELIANCE", "BSE" },
                        { "ICICIBANK", "BSE" },
                        { "INFY", "BSE" },
                        { "LT", "BSE" },
                        { "ITC", "BSE" },
                        { "TCS", "BSE" },
                        { "BHARTIARTL", "BSE" },
                        { "AXISBANK", "BSE" },
                        { "SBIN", "BSE" }
                };

                for (String[] symInfo : defaultSymbols) {
                    stmt.execute("INSERT INTO tracked_symbols (symbol, exchange) VALUES ('" + symInfo[0]
                            + "', '" + symInfo[1] + "') ON CONFLICT DO NOTHING");
                }

                System.out.println(
                        "TimescaleRepository: Database schema verified/initialized. Default SENSEX heavyweights added.");
            }
        } catch (SQLException e) {
            System.err.println("Error initializing database schema: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                }
            }
        }
    }

    /**
     * Save a list of candles to TimescaleDB (bulk insert with duplicate handling)
     */
    public int saveCandles(List<Candle> candles, String symbol, String interval) {
        if (candles == null || candles.isEmpty()) {
            return 0;
        }

        String sql = "INSERT INTO candles (time, symbol, interval, open, high, low, close, " +
                "ema15, ema15_5, ema15_15, ema15_15_15, ema15_15_15_15, ema15_15_15_15_30, " +
                "st_ma, st_up, st_dn, st_trend, st_ma2, st_up2, st_dn2, st_trend2) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (time, symbol, interval) DO UPDATE SET " +
                "open = EXCLUDED.open, high = EXCLUDED.high, low = EXCLUDED.low, " +
                "close = EXCLUDED.close, " +
                "ema15 = EXCLUDED.ema15, ema15_5 = EXCLUDED.ema15_5, ema15_15 = EXCLUDED.ema15_15, " +
                "ema15_15_15 = EXCLUDED.ema15_15_15, ema15_15_15_15 = EXCLUDED.ema15_15_15_15, ema15_15_15_15_30 = EXCLUDED.ema15_15_15_15_30, "
                +
                "st_ma = EXCLUDED.st_ma, st_up = EXCLUDED.st_up, st_dn = EXCLUDED.st_dn, st_trend = EXCLUDED.st_trend, "
                +
                "st_ma2 = EXCLUDED.st_ma2, st_up2 = EXCLUDED.st_up2, st_dn2 = EXCLUDED.st_dn2, st_trend2 = EXCLUDED.st_trend2";

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

                    // Indicators (nullable)
                    setDoubleOrNull(pstmt, 8, candle.getEma15());
                    setDoubleOrNull(pstmt, 9, candle.getEma15_5());
                    setDoubleOrNull(pstmt, 10, candle.getEma15_15());
                    setDoubleOrNull(pstmt, 11, candle.getEma15_15_15());
                    setDoubleOrNull(pstmt, 12, candle.getEma15_15_15_15());
                    setDoubleOrNull(pstmt, 13, candle.getEma15_15_15_15_30());
                    setDoubleOrNull(pstmt, 14, candle.getStMa());
                    setDoubleOrNull(pstmt, 15, candle.getStUp());
                    setDoubleOrNull(pstmt, 16, candle.getStDn());
                    setIntegerOrNull(pstmt, 17, candle.getStTrend());
                    setDoubleOrNull(pstmt, 18, candle.getStMa2());
                    setDoubleOrNull(pstmt, 19, candle.getStUp2());
                    setDoubleOrNull(pstmt, 20, candle.getStDn2());
                    setIntegerOrNull(pstmt, 21, candle.getStTrend2());

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
            // NOTE: updateSyncStatus removed from the live hot path.
            // It runs on every candle save (hundreds of times/min) and fires
            // an expensive MIN/MAX/COUNT(*) aggregate on the full table.

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
        String sql = "SELECT time, open, high, low, close, " +
                "ema15, ema15_5, ema15_15, ema15_15_15, ema15_15_15_15, ema15_15_15_15_30, " +
                "st_ma, st_up, st_dn, st_trend, st_ma2, st_up2, st_dn2, st_trend2 " +
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
                            rs.getTimestamp("time").toLocalDateTime());

                    // Load indicators
                    candle.setEma15(getDoubleOrNull(rs, "ema15"));
                    candle.setEma15_5(getDoubleOrNull(rs, "ema15_5"));
                    candle.setEma15_15(getDoubleOrNull(rs, "ema15_15"));
                    candle.setEma15_15_15(getDoubleOrNull(rs, "ema15_15_15"));
                    candle.setEma15_15_15_15(getDoubleOrNull(rs, "ema15_15_15_15"));
                    candle.setEma15_15_15_15_30(getDoubleOrNull(rs, "ema15_15_15_15_30"));

                    candle.setStMa(getDoubleOrNull(rs, "st_ma"));
                    candle.setStUp(getDoubleOrNull(rs, "st_up"));
                    candle.setStDn(getDoubleOrNull(rs, "st_dn"));
                    candle.setStTrend(getIntegerOrNull(rs, "st_trend"));

                    candle.setStMa2(getDoubleOrNull(rs, "st_ma2"));
                    candle.setStUp2(getDoubleOrNull(rs, "st_up2"));
                    candle.setStDn2(getDoubleOrNull(rs, "st_dn2"));
                    candle.setStTrend2(getIntegerOrNull(rs, "st_trend2"));

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

        // Reverse to get chronological order (oldest to newest) for charting/indicators
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

    /**
     * Get all currently tracked symbols
     */
    public List<String[]> getTrackedSymbols() {
        List<String[]> symbols = new ArrayList<>();
        String sql = "SELECT symbol, exchange FROM tracked_symbols";
        Connection conn = null;
        try {
            conn = timescaleClient.getConnection();
            if (conn == null)
                return symbols;
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    symbols.add(new String[] { rs.getString("symbol"), rs.getString("exchange") });
                }
            }
        } catch (SQLException e) {
            System.err.println("Error fetching tracked symbols: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                }
            }
        }
        return symbols;
    }

    /**
     * Add a new symbol to track
     */
    public void addTrackedSymbol(String symbol, String exchange) {
        String sql = "INSERT INTO tracked_symbols (symbol, exchange) VALUES (?, ?) ON CONFLICT DO NOTHING";
        Connection conn = null;
        try {
            conn = timescaleClient.getConnection();
            if (conn == null)
                return;
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, symbol);
                pstmt.setString(2, exchange);
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            System.err.println("Error adding tracked symbol: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                }
            }
        }
    }

    /**
     * Remove a symbol from tracking
     */
    public void removeTrackedSymbol(String symbol) {
        String sql = "DELETE FROM tracked_symbols WHERE symbol = ?";
        Connection conn = null;
        try {
            conn = timescaleClient.getConnection();
            if (conn == null)
                return;
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, symbol);
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            System.err.println("Error removing tracked symbol: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                }
            }
        }
    }

    /**
     * Delete all candle data and sync status for a symbol
     */
    public void deleteCandleData(String symbol) {
        Connection conn = null;
        try {
            conn = timescaleClient.getConnection();
            if (conn == null)
                return;
            conn.setAutoCommit(false);
            try (PreparedStatement p1 = conn.prepareStatement("DELETE FROM candles WHERE symbol = ?");
                    PreparedStatement p2 = conn.prepareStatement("DELETE FROM data_sync_status WHERE symbol = ?")) {
                p1.setString(1, symbol);
                p1.executeUpdate();
                p2.setString(1, symbol);
                p2.executeUpdate();
                conn.commit();
                System.out.println("TimescaleRepository: Flushed data for " + symbol);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            System.err.println("Error flushing candle data: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                }
            }
        }
    }

    /**
     * Delete ALL candle data and sync status from the database
     */
    public void flushAllData() {
        Connection conn = null;
        try {
            conn = timescaleClient.getConnection();
            if (conn == null)
                return;
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("TRUNCATE TABLE candles");
                stmt.executeUpdate("TRUNCATE TABLE data_sync_status");
                System.out.println("TimescaleRepository: FLUSHED ALL DATA");
            }
        } catch (SQLException e) {
            System.err.println("Error flushing all data: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                }
            }
        }
    }
}
