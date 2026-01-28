package com.algo.ui;

import com.algo.core.Candle;
import com.algo.core.RenkoProcessor;
import com.algo.core.Strategy;
import com.algo.data.AngelClient;
import com.algo.data.LiveFeed;
import com.algo.data.TokenManager;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.Map;

public class Dashboard extends JFrame {
    private JTable table;
    private DefaultTableModel tableModel;
    private Map<String, RenkoProcessor> processors = new HashMap<>();
    private Map<String, Strategy> strategies = new HashMap<>();
    private Map<String, java.util.List<Candle>> historyMap = new HashMap<>();
    private TokenManager tokenManager;
    private LiveFeed liveFeed;

    public Dashboard() {
        setTitle("Angel One Renko Algo - Nifty 50");
        setSize(1000, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // 1. Setup Data
        tokenManager = new TokenManager();
        initializeAlgos();

        // 2. Setup UI
        String[] columns = { "Symbol", "LTP", "Brick Size", "EMA15", "EMA15_5", "EMA15_15", "EMA15_15_15",
                "EMA15_30" };
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false; // Disable editing
            }
        };
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Populate initial rows
        for (String symbol : processors.keySet()) {
            tableModel.addRow(new Object[] { symbol, "0.00", "0.00", "0.00", "0.00", "0.00", "0.00", "0.00" });
        }

        JScrollPane scrollPane = new JScrollPane(table);
        add(scrollPane, BorderLayout.CENTER);

        // 3. Click Listener for Chart
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 1) { // Changed to Single Click
                    int row = table.getSelectedRow();
                    if (row != -1) {
                        String symbol = (String) tableModel.getValueAt(row, 0);
                        openChart(symbol);
                    }
                }
            }
        });

        // 4. Start Feed (Mock for now)
        startFeed();
    }

    private void initializeAlgos() {
        // Expanded Nifty 50 subset
        String[] symbols = {
                "RELIANCE", "TCS", "HDFCBANK", "INFY", "SBIN",
                "ICICIBANK", "BHARTIARTL", "KOTAKBANK", "LT", "ITC",
                "AXISBANK", "HINDUNILVR", "ADANIENT", "BAJFINANCE", "ASIANPAINT"
        };

        // 1. Initialize AngelClient and Login ONCE
        AngelClient client = new AngelClient("A436240", "2016", "ij2Si8cy", "H2CJID2A2BS47VZMHF7OBKGNZE");
        boolean loginSuccess = client.login();

        if (!loginSuccess) {
            System.err.println("CRITICAL: Angel One Login Failed. Charts will use Mock Data.");
        }

        for (String symbol : symbols) {
            // Strategy: 15 EMA (Low), 7 Supertrend, Multiplier 3.0
            Strategy strategy = new Strategy(15, 7, 3.0);
            strategies.put(symbol, strategy);

            // 2. Fetch Intraday History for Renko (contains 30 days of 3-minute data via
            // chunked fetch)
            String token = tokenManager.getToken(symbol);
            if (token != null) {
                java.util.List<Candle> history = client.fetchHistory(token, "THREE_MINUTE");

                if (!history.isEmpty()) {
                    historyMap.put(symbol, history);
                }

                double lastDayClose = 0;
                if (!history.isEmpty()) {
                    // Extract last day's close from 1-minute history
                    // The history contains 5 days of data. We want the close of the last candle of
                    // the PREVIOUS day.
                    java.time.LocalDate lastDate = history.get(history.size() - 1).getTimestamp().toLocalDate();
                    for (int i = history.size() - 2; i >= 0; i--) {
                        java.time.LocalDate currentDate = history.get(i).getTimestamp().toLocalDate();
                        if (!currentDate.equals(lastDate)) {
                            // Found the last candle of the previous day
                            lastDayClose = history.get(i).getClose();
                            break;
                        }
                    }
                }

                if (!history.isEmpty()) {
                    double startPrice = history.get(0).getOpen();
                    // Dynamic Brick Size: 2% of last day close (fallback to start price if daily
                    // history fails)
                    double referencePrice = (lastDayClose > 0) ? lastDayClose : startPrice;
                    double brickSize = Math.max(0.1, Math.round((referencePrice * 0.02) * 10.0) / 10.0);

                    System.out.println("--------------------------------------------------");
                    System.out.println("DEBUG INFO for " + symbol);
                    System.out.println("History Size: " + history.size());
                    System.out.println("First Candle: " + history.get(0).getTimestamp());
                    System.out.println("Last Candle: " + history.get(history.size() - 1).getTimestamp());
                    System.out.println("Last Day Close: " + lastDayClose);
                    System.out.println("Reference Price: " + referencePrice);
                    System.out.println("Calculated Brick Size (2%): " + brickSize);
                    System.out.println("--------------------------------------------------");

                    RenkoProcessor processor = new RenkoProcessor(brickSize, (brick) -> {
                        strategy.onNewBrick(brick);
                        updateRow(symbol, brick.getClose(), brickSize, strategy);
                    });

                    processor.init(startPrice);
                    processor.processHistory(history);
                    System.out.println("Processed history for " + symbol + ". Bricks: " + processor.getBricks().size());
                    System.out.println("--------------------------------------------------");

                    processors.put(symbol, processor);

                    // Update initial row with brick size
                    SwingUtilities.invokeLater(() -> {
                        int row = getRowForSymbol(symbol);
                        if (row != -1) {
                            tableModel.setValueAt(String.format("%.2f", brickSize), row, 2);
                        }
                    });
                } else {
                    System.err.println("Skipping " + symbol + " due to no historical data.");
                }
            }
        }
    }

    private Map<String, Candle> currentCandles = new HashMap<>();

    private void startFeed() {
        // Mock Credentials
        liveFeed = new LiveFeed("CLIENT_CODE", "FEED_TOKEN", "API_KEY", (token, price) -> {
            String symbol = tokenManager.getSymbol(token);
            if (symbol != null && processors.containsKey(symbol)) {
                // 1. Update LTP in table (UI Thread)
                SwingUtilities.invokeLater(() -> {
                    int row = getRowForSymbol(symbol);
                    if (row != -1) {
                        tableModel.setValueAt(String.format("%.2f", price), row, 1);
                    }
                });

                // 2. Aggregate into 1-minute candle
                java.time.LocalDateTime now = java.time.LocalDateTime.now();
                java.time.LocalDateTime minuteStart = now.withSecond(0).withNano(0);

                Candle currentCandle = currentCandles.get(symbol);

                if (currentCandle == null || !currentCandle.getTimestamp().equals(minuteStart)) {
                    // Minute changed! Process the PREVIOUS candle if it exists
                    if (currentCandle != null) {
                        processors.get(symbol).onTick(currentCandle.getClose(), currentCandle.getTimestamp());
                    }

                    // Start new candle
                    currentCandle = new Candle(price, price, price, price, minuteStart);
                    currentCandles.put(symbol, currentCandle);
                } else {
                    // Update existing candle
                    currentCandle.setHigh(Math.max(currentCandle.getHigh(), price));
                    currentCandle.setLow(Math.min(currentCandle.getLow(), price));
                    currentCandle.setClose(price);
                }
            }
        });

        liveFeed.connect();

        // Start mock feed with initial prices from processors to avoid jumps
        java.util.Map<String, Double> initialPrices = new java.util.HashMap<>();
        for (String symbol : processors.keySet()) {
            String token = tokenManager.getToken(symbol);
            java.util.List<com.algo.core.RenkoBrick> bricks = processors.get(symbol).getBricks();
            double lastPrice = bricks.isEmpty() ? 1000.0 : bricks.get(bricks.size() - 1).getClose();
            initialPrices.put(token, lastPrice);
        }

        liveFeed.setInitialPrices(initialPrices);
        liveFeed.subscribe(processors.keySet()); // Subscribe to symbols
    }

    private void updateRow(String symbol, double price, double brickSize, Strategy strategy) {
        SwingUtilities.invokeLater(() -> {
            int row = getRowForSymbol(symbol);
            if (row != -1) {
                tableModel.setValueAt(String.format("%.2f", brickSize), row, 2);
                tableModel.setValueAt(String.format("%.2f", strategy.getEma15()), row, 3);
                tableModel.setValueAt(String.format("%.2f", strategy.getEma15_5()), row, 4);
                tableModel.setValueAt(String.format("%.2f", strategy.getEma15_15()), row, 5);
                tableModel.setValueAt(String.format("%.2f", strategy.getEma15_15_15()), row, 6);
                tableModel.setValueAt(String.format("%.2f", strategy.getEma15_15_15_30()), row, 7);
            }
        });
    }

    private int getRowForSymbol(String symbol) {
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            if (tableModel.getValueAt(i, 0).equals(symbol)) {
                return i;
            }
        }
        return -1;
    }

    private void openChart(String symbol) {
        JFrame chartFrame = new JFrame("Candlestick Chart: " + symbol);
        chartFrame.setSize(800, 500);
        RenkoChart chartPanel = new RenkoChart();

        java.util.List<Candle> history = historyMap.get(symbol);
        if (history != null && !history.isEmpty()) {
            // Create a new Strategy instance for the chart to calculate indicators on the
            // full history
            Strategy chartStrategy = new Strategy(15, 7, 3.0);
            for (Candle candle : history) {
                chartStrategy.onNewCandle(candle);
            }
            chartPanel.update(history, chartStrategy);
        } else {
            JOptionPane.showMessageDialog(this, "No history available for " + symbol);
        }

        chartFrame.add(chartPanel);
        chartFrame.setVisible(true);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new Dashboard().setVisible(true);
        });
    }
}
