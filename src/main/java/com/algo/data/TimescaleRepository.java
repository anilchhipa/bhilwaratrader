package com.algo.data;

import com.algo.core.Candle;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jctools.queues.MpscArrayQueue;

/**
 * Repository for CRUD operations on candle data in TimescaleDB
 * Inspired by CandleRepository but designed for TimescaleDB hypertables
 */
public class TimescaleRepository {
    private final TimescaleClient timescaleClient;
    private final MpscArrayQueue<WriteTask> writeQueue = new MpscArrayQueue<>(65536);
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "TimescaleFlusher-0");
        t.setDaemon(true);
        return t;
    });

    public TimescaleRepository(TimescaleClient timescaleClient) {
        this.timescaleClient = timescaleClient;
        initializeSchema();
        startFlusher();
    }

    private void startFlusher() {
        flusher.scheduleAtFixedRate(this::flushQueue, 2, 2, TimeUnit.SECONDS);
    }

    private void flushQueue() {
        if (writeQueue.isEmpty())
            return;

        List<WriteTask> tasks = new ArrayList<>();
        writeQueue.drain(tasks::add);

        if (tasks.isEmpty())
            return;

        // Grouping by symbol/interval could optimize further, but batching is the main
        // win
        for (WriteTask task : tasks) {
            executeSave(task.candles(), task.symbol(), task.interval());
        }
    }

    /**
     * Public entry point: Queues the save operation to be executed asynchronously.
     */
    public int saveCandles(List<Candle> candles, String symbol, String interval) {
        if (candles == null || candles.isEmpty())
            return 0;

        if (!writeQueue.offer(new WriteTask(new ArrayList<>(candles), symbol, interval))) {
            System.err.println("TimescaleRepository: Write queue full! Dropping data for " + symbol);
            return 0;
        }
        return candles.size();
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
                        "volume DOUBLE PRECISION NOT NULL DEFAULT 0, " +
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
                        "atr DOUBLE PRECISION, " +
                        "signal INTEGER, " +
                        "trade_score DOUBLE PRECISION, " +
                        "er DOUBLE PRECISION, " +
                        "PRIMARY KEY (time, symbol, interval)" +
                        ")");

                // Migration: Add missing columns if they don't exist
                try {
                    stmt.execute("ALTER TABLE candles ADD COLUMN IF NOT EXISTS signal INTEGER");
                    stmt.execute("ALTER TABLE candles ADD COLUMN IF NOT EXISTS trade_score DOUBLE PRECISION");
                    stmt.execute("ALTER TABLE candles ADD COLUMN IF NOT EXISTS er DOUBLE PRECISION");
                    stmt.execute(
                            "ALTER TABLE candles ADD COLUMN IF NOT EXISTS volume DOUBLE PRECISION NOT NULL DEFAULT 0");
                } catch (SQLException e) {
                }

                // Convert to hypertable
                try {
                    stmt.execute("SELECT create_hypertable('candles', 'time', if_not_exists => TRUE)");
                } catch (SQLException e) {
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

                // Pre-populate default symbols
                String[][] defaultSymbols = { { "SENSEX", "BSE" }, { "NIFTY 50", "NSE" } };
                for (String[] symInfo : defaultSymbols) {
                    stmt.execute("INSERT INTO tracked_symbols (symbol, exchange) VALUES ('" + symInfo[0]
                            + "', '" + symInfo[1] + "') ON CONFLICT DO NOTHING");
                }
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
     * Internal synchronous save logic.
     */
    private int executeSave(List<Candle> candles, String symbol, String interval) {
        String sql = "INSERT INTO candles (time, symbol, interval, open, high, low, close, volume, " +
                "ema15, ema15_5, ema15_15, ema15_15_15, ema15_15_15_15, ema15_15_15_15_30, " +
                "st_ma, st_up, st_dn, st_trend, st_ma2, st_up2, st_dn2, st_trend2, atr, signal, trade_score, er) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (time, symbol, interval) DO UPDATE SET " +
                "open = EXCLUDED.open, high = EXCLUDED.high, low = EXCLUDED.low, " +
                "close = EXCLUDED.close, volume = EXCLUDED.volume, " +
                "ema15 = EXCLUDED.ema15, ema15_5 = EXCLUDED.ema15_5, ema15_15 = EXCLUDED.ema15_15, " +
                "ema15_15_15 = EXCLUDED.ema15_15_15, ema15_15_15_15 = EXCLUDED.ema15_15_15_15, ema15_15_15_15_30 = EXCLUDED.ema15_15_15_15_30, "
                +
                "st_ma = EXCLUDED.st_ma, st_up = EXCLUDED.st_up, st_dn = EXCLUDED.st_dn, st_trend = EXCLUDED.st_trend, "
                +
                "st_ma2 = EXCLUDED.st_ma2, st_up2 = EXCLUDED.st_up2, st_dn2 = EXCLUDED.st_dn2, st_trend2 = EXCLUDED.st_trend2, atr = EXCLUDED.atr, "
                +
                "signal = EXCLUDED.signal, trade_score = EXCLUDED.trade_score, er = EXCLUDED.er";

        int savedCount = 0;
        Connection conn = null;

        try {
            conn = timescaleClient.getConnection();
            if (conn == null)
                return 0;

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
                    pstmt.setDouble(8, candle.getVolume());

                    setDoubleOrNull(pstmt, 9, candle.getEma15());
                    setDoubleOrNull(pstmt, 10, candle.getEma15_5());
                    setDoubleOrNull(pstmt, 11, candle.getEma15_15());
                    setDoubleOrNull(pstmt, 12, candle.getEma15_15_15());
                    setDoubleOrNull(pstmt, 13, candle.getEma15_15_15_15());
                    setDoubleOrNull(pstmt, 14, candle.getEma15_15_15_15_30());
                    setDoubleOrNull(pstmt, 15, candle.getStMa());
                    setDoubleOrNull(pstmt, 16, candle.getStUp());
                    setDoubleOrNull(pstmt, 17, candle.getStDn());
                    setIntegerOrNull(pstmt, 18, candle.getStTrend());
                    setDoubleOrNull(pstmt, 19, candle.getStMa2());
                    setDoubleOrNull(pstmt, 20, candle.getStUp2());
                    setDoubleOrNull(pstmt, 21, candle.getStDn2());
                    setIntegerOrNull(pstmt, 22, candle.getStTrend2());
                    setDoubleOrNull(pstmt, 23, candle.getAtr());
                    setIntegerOrNull(pstmt, 24, candle.getSignal());
                    setDoubleOrNull(pstmt, 25, candle.getTradeScore());
                    setDoubleOrNull(pstmt, 26, candle.getEr());

                    pstmt.addBatch();
                }
                pstmt.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            System.err.println("TimescaleRepository: Batch save error: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                }
            }
        }
        return savedCount;
    }

    public List<Candle> getLatestCandles(String symbol, String interval, int count) {
        List<Candle> candles = new ArrayList<>();
        String sql = "SELECT time, open, high, low, close, volume, " +
                "ema15, ema15_5, ema15_15, ema15_15_15, ema15_15_15_15, ema15_15_15_15_30, " +
                "st_ma, st_up, st_dn, st_trend, st_ma2, st_up2, st_dn2, st_trend2, atr, signal, trade_score, er " +
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
                            rs.getDouble("volume"),
                            rs.getTimestamp("time").toLocalDateTime());

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
                    candle.setAtr(getDoubleOrNull(rs, "atr"));
                    candle.setSignal(getIntegerOrNull(rs, "signal"));
                    candle.setTradeScore(getDoubleOrNull(rs, "trade_score"));
                    candle.setEr(getDoubleOrNull(rs, "er"));

                    candles.add(candle);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error fetching candles: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                }
            }
        }

        List<Candle> reversed = new ArrayList<>();
        for (int i = candles.size() - 1; i >= 0; i--) {
            reversed.add(candles.get(i));
        }
        return reversed;
    }

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
                }
            }
        }
        return null;
    }

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
                if (rs.next())
                    return rs.getInt("count");
            }
        } catch (SQLException e) {
            System.err.println("Error getting candle count: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                }
            }
        }
        return 0;
    }

    private void setDoubleOrNull(PreparedStatement pstmt, int index, Double value) throws SQLException {
        if (value != null)
            pstmt.setDouble(index, value);
        else
            pstmt.setNull(index, Types.DOUBLE);
    }

    private void setIntegerOrNull(PreparedStatement pstmt, int index, Integer value) throws SQLException {
        if (value != null)
            pstmt.setInt(index, value);
        else
            pstmt.setNull(index, Types.INTEGER);
    }

    private Double getDoubleOrNull(ResultSet rs, String columnName) throws SQLException {
        double value = rs.getDouble(columnName);
        return rs.wasNull() ? null : value;
    }

    private Integer getIntegerOrNull(ResultSet rs, String columnName) throws SQLException {
        int value = rs.getInt(columnName);
        return rs.wasNull() ? null : value;
    }

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

    public void shutdown() {
        flusher.shutdown();
        flushQueue(); // Final flush
    }

    private static record WriteTask(List<Candle> candles, String symbol, String interval) {
    }
}
