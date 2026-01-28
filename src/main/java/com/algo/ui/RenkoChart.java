package com.algo.ui;

import com.algo.core.Strategy;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.CandlestickRenderer;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.renderer.xy.XYDifferenceRenderer;
import org.jfree.data.xy.DefaultOHLCDataset;
import org.jfree.data.xy.OHLCDataItem;
import org.jfree.data.xy.OHLCDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.Date;
import java.util.List;

public class RenkoChart extends JPanel {
    private JFreeChart chart;
    private ChartPanel chartPanel;
    private JButton reset;
    private Point2D dragStart;
    private Point2D dragEnd;
    private boolean wasShiftDrag = false;
    private boolean isAxisDragging = false;
    private int dragSubplotIndex = -1;

    private JCheckBox cbEma15, cbEma15_5, cbEma15_15, cbEma15_15_15, cbEma15_30;
    private JCheckBox cbStMa, cbStUp, cbStDn, cbStShade;
    private List<com.algo.core.Candle> lastCandles;
    private Strategy lastStrategy;
    private JPanel controlPanel;

    public RenkoChart() {
        setLayout(new BorderLayout());
        controlPanel = createControlPanel();
        add(controlPanel, BorderLayout.NORTH);
    }

    private JPanel createControlPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        reset = new JButton("Reset");

        // Initially disabled until chartPanel is created
        panel.add(new JLabel("Zoom: "));
        panel.add(reset);
        panel.add(new JSeparator(JSeparator.VERTICAL));

        cbEma15 = new JCheckBox("EMA15", true);
        cbEma15_5 = new JCheckBox("EMA15_5", true);
        cbEma15_15 = new JCheckBox("EMA15_15", true);
        cbEma15_15_15 = new JCheckBox("EMA15_15_15", true);
        cbEma15_30 = new JCheckBox("EMA15_30", true);
        cbStMa = new JCheckBox("ST MA", true);
        cbStUp = new JCheckBox("ST Up", true);
        cbStDn = new JCheckBox("ST Dn", true);
        cbStShade = new JCheckBox("ST Shade", true);

        panel.add(cbEma15);
        panel.add(cbEma15_5);
        panel.add(cbEma15_15);
        panel.add(cbEma15_15_15);
        panel.add(cbEma15_30);
        panel.add(new JSeparator(JSeparator.VERTICAL));
        panel.add(cbStMa);
        panel.add(cbStUp);
        panel.add(cbStDn);
        panel.add(cbStShade);

        var refreshListener = (java.awt.event.ActionListener) e -> refreshChart();
        cbEma15.addActionListener(refreshListener);
        cbEma15_5.addActionListener(refreshListener);
        cbEma15_15.addActionListener(refreshListener);
        cbEma15_15_15.addActionListener(refreshListener);
        cbEma15_30.addActionListener(refreshListener);
        cbStMa.addActionListener(refreshListener);
        cbStUp.addActionListener(refreshListener);
        cbStDn.addActionListener(refreshListener);
        cbStShade.addActionListener(refreshListener);

        return panel;
    }

    private void refreshChart() {
        if (lastCandles != null && lastStrategy != null && chart != null) {
            // Get the main plot from the combined plot
            org.jfree.chart.plot.Plot plot = chart.getPlot();
            if (plot instanceof org.jfree.chart.plot.CombinedDomainXYPlot) {
                org.jfree.chart.plot.CombinedDomainXYPlot combinedPlot = (org.jfree.chart.plot.CombinedDomainXYPlot) plot;
                XYPlot mainPlot = (XYPlot) combinedPlot.getSubplots().get(0);

                // Update shading renderer visibility (dataset 0)
                XYDifferenceRenderer shadingRenderer = (XYDifferenceRenderer) mainPlot.getRenderer(0);
                if (shadingRenderer != null) {
                    shadingRenderer.setSeriesVisible(0, cbStShade.isSelected());
                    shadingRenderer.setSeriesVisible(1, cbStShade.isSelected());
                    shadingRenderer.setSeriesVisibleInLegend(0, cbStShade.isSelected());
                    shadingRenderer.setSeriesVisibleInLegend(1, cbStShade.isSelected());
                }

                // Update indicator renderer visibility (dataset 2)
                XYLineAndShapeRenderer indicatorRenderer = (XYLineAndShapeRenderer) mainPlot.getRenderer(2);
                if (indicatorRenderer != null) {
                    // EMA lines
                    indicatorRenderer.setSeriesVisible(0, cbEma15.isSelected());
                    indicatorRenderer.setSeriesVisibleInLegend(0, cbEma15.isSelected());

                    indicatorRenderer.setSeriesVisible(1, cbEma15_5.isSelected());
                    indicatorRenderer.setSeriesVisibleInLegend(1, cbEma15_5.isSelected());

                    indicatorRenderer.setSeriesVisible(2, cbEma15_15.isSelected());
                    indicatorRenderer.setSeriesVisibleInLegend(2, cbEma15_15.isSelected());

                    indicatorRenderer.setSeriesVisible(3, cbEma15_15_15.isSelected());
                    indicatorRenderer.setSeriesVisibleInLegend(3, cbEma15_15_15.isSelected());

                    indicatorRenderer.setSeriesVisible(4, cbEma15_30.isSelected());
                    indicatorRenderer.setSeriesVisibleInLegend(4, cbEma15_30.isSelected());

                    // SuperTrend lines
                    indicatorRenderer.setSeriesVisible(5, cbStMa.isSelected());
                    indicatorRenderer.setSeriesVisibleInLegend(5, cbStMa.isSelected());

                    indicatorRenderer.setSeriesVisible(6, cbStUp.isSelected());
                    indicatorRenderer.setSeriesVisibleInLegend(6, cbStUp.isSelected());

                    indicatorRenderer.setSeriesVisible(7, cbStDn.isSelected());
                    indicatorRenderer.setSeriesVisibleInLegend(7, cbStDn.isSelected());
                }

                // Notify datasets of changes
                if (mainPlot.getDataset(0) != null) {
                    mainPlot.getDataset(0).addChangeListener(mainPlot);
                }
                if (mainPlot.getDataset(2) != null) {
                    mainPlot.getDataset(2).addChangeListener(mainPlot);
                }

                // Force chart update
                chart.fireChartChanged();

                // Force repaint of chart panel
                if (chartPanel != null) {
                    chartPanel.repaint();
                }
            }
        }
    }

    private void setupZoomButtons() {
        reset.setEnabled(true);

        // Remove old listeners if any
        for (var listener : reset.getActionListeners()) {
            reset.removeActionListener(listener);
        }

        // Add new listeners
        // Add new listeners
        reset.addActionListener(e -> {
            if (chartPanel != null) {
                chartPanel.restoreAutoBounds();
            }
        });
    }

    private void setupPanningListeners() {
        // TradingView-style mouse wheel zoom (horizontal by default, vertical with
        // Ctrl)
        chartPanel.addMouseWheelListener(e -> {
            Rectangle2D screenDataArea = chartPanel.getScreenDataArea(e.getX(), e.getY());
            if (screenDataArea == null)
                return;

            org.jfree.chart.plot.Plot plot = chart.getPlot();
            int rotation = e.getWheelRotation();
            double zoomFactor = rotation < 0 ? 0.9 : 1.1; // Zoom in/out

            if (e.isControlDown()) {
                // Ctrl + Scroll = Vertical zoom in the subplot under the mouse
                org.jfree.chart.plot.PlotRenderingInfo plotInfo = chartPanel.getChartRenderingInfo().getPlotInfo();
                int subplotIndex = plotInfo.getSubplotIndex(chartPanel.translateScreenToJava2D(e.getPoint()));

                if (subplotIndex != -1 && plot instanceof org.jfree.chart.plot.CombinedDomainXYPlot) {
                    org.jfree.chart.plot.CombinedDomainXYPlot combinedPlot = (org.jfree.chart.plot.CombinedDomainXYPlot) plot;
                    XYPlot subplot = (XYPlot) combinedPlot.getSubplots().get(subplotIndex);
                    NumberAxis rangeAxis = (NumberAxis) subplot.getRangeAxis();
                    double minY = rangeAxis.getLowerBound();
                    double maxY = rangeAxis.getUpperBound();
                    double centerY = (minY + maxY) / 2.0;
                    double rangeY = maxY - minY;
                    double newRangeY = rangeY * zoomFactor;
                    rangeAxis.setRange(centerY - newRangeY / 2.0, centerY + newRangeY / 2.0);
                }
            } else {
                // Mouse wheel = Horizontal zoom at mouse position (shared domain axis)
                XYPlot mainPlot = chart.getXYPlot();
                NumberAxis domainAxis = (NumberAxis) mainPlot.getDomainAxis();
                double minX = domainAxis.getLowerBound();
                double maxX = domainAxis.getUpperBound();
                double range = maxX - minX;

                // Get mouse position in data coordinates
                Point2D point = chartPanel.translateScreenToJava2D(new Point(e.getX(), e.getY()));
                double mouseX = domainAxis.java2DToValue(point.getX(), screenDataArea, mainPlot.getDomainAxisEdge());

                // Calculate zoom around mouse position
                double leftRatio = (mouseX - minX) / range;
                double rightRatio = (maxX - mouseX) / range;

                double newRange = range * zoomFactor;
                double newMinX = mouseX - newRange * leftRatio;
                double newMaxX = mouseX + newRange * rightRatio;

                domainAxis.setRange(newMinX, newMaxX);
            }
        });

        // Click and drag to pan
        chartPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    Point2D java2DPoint = chartPanel.translateScreenToJava2D(e.getPoint());
                    Rectangle2D screenDataArea = chartPanel.getScreenDataArea(e.getX(), e.getY());

                    org.jfree.chart.plot.PlotRenderingInfo plotInfo = chartPanel.getChartRenderingInfo().getPlotInfo();
                    int subplotIndex = plotInfo.getSubplotIndex(java2DPoint);

                    if (screenDataArea != null) {
                        // Clicked inside data area
                        dragStart = java2DPoint;
                        isAxisDragging = false;
                        if (e.isShiftDown()) {
                            chartPanel.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                        } else {
                            chartPanel.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                        }
                    } else {
                        // Clicked outside data area - check for axis dragging
                        if (subplotIndex == -1) {
                            subplotIndex = findSubplot(java2DPoint, plotInfo);
                        }

                        if (subplotIndex != -1) {
                            Rectangle2D dataArea = plotInfo.getSubplotInfo(subplotIndex).getDataArea();
                            // Check if click is to the right of the data area (Y-axis)
                            if (java2DPoint.getX() > dataArea.getMaxX()) {
                                dragStart = java2DPoint;
                                isAxisDragging = true;
                                dragSubplotIndex = subplotIndex;
                                chartPanel.setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
                                return;
                            }
                        }

                        // Check for X-axis (shared domain axis at the bottom)
                        int subplotCount = plotInfo.getSubplotCount();
                        if (subplotCount > 0) {
                            Rectangle2D lastSubplotArea = plotInfo.getSubplotInfo(subplotCount - 1).getPlotArea();

                            // If click is below the last subplot's data area but within the plot area
                            if (java2DPoint.getY() > lastSubplotArea.getMaxY() ||
                                    (subplotIndex == subplotCount - 1 && java2DPoint.getY() > plotInfo
                                            .getSubplotInfo(subplotIndex).getDataArea().getMaxY())) {
                                dragStart = java2DPoint;
                                isAxisDragging = true;
                                dragSubplotIndex = -2; // Special index for X-axis
                                chartPanel.setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
                            }
                        }
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                // Handle shift+drag zoom on release
                if (wasShiftDrag && dragStart != null && dragEnd != null) {
                    double dx = Math.abs(dragEnd.getX() - dragStart.getX());
                    if (dx > 10) { // Minimum drag distance
                        Rectangle2D screenDataArea = chartPanel.getScreenDataArea();
                        if (screenDataArea != null) {
                            XYPlot plot = chart.getXYPlot();
                            NumberAxis domainAxis = (NumberAxis) plot.getDomainAxis();

                            double x1 = domainAxis.java2DToValue(dragStart.getX(), screenDataArea,
                                    plot.getDomainAxisEdge());
                            double x2 = domainAxis.java2DToValue(dragEnd.getX(), screenDataArea,
                                    plot.getDomainAxisEdge());

                            domainAxis.setRange(Math.min(x1, x2), Math.max(x1, x2));
                        }
                    }
                }

                dragStart = null;
                dragEnd = null;
                wasShiftDrag = false;
                isAxisDragging = false;
                dragSubplotIndex = -1;
                chartPanel.setCursor(Cursor.getDefaultCursor());
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                // Double-click to reset zoom
                if (e.getClickCount() == 2 && e.getButton() == MouseEvent.BUTTON1) {
                    chartPanel.restoreAutoBounds();
                }
            }
        });

        chartPanel.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragStart != null && (e.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) != 0) {
                    dragEnd = chartPanel.translateScreenToJava2D(e.getPoint());

                    if (e.isShiftDown()) {
                        // Track that this is a shift+drag for zoom on release
                        wasShiftDrag = true;
                    } else if (isAxisDragging && dragSubplotIndex >= 0) {
                        // Axis Drag Zoom
                        double dy = dragEnd.getY() - dragStart.getY();
                        org.jfree.chart.plot.Plot plot = chart.getPlot();
                        if (plot instanceof org.jfree.chart.plot.CombinedDomainXYPlot) {
                            org.jfree.chart.plot.CombinedDomainXYPlot combinedPlot = (org.jfree.chart.plot.CombinedDomainXYPlot) plot;
                            XYPlot subplot = (XYPlot) combinedPlot.getSubplots().get(dragSubplotIndex);
                            NumberAxis rangeAxis = (NumberAxis) subplot.getRangeAxis();

                            double zoomFactor = 1.0 + (dy / 100.0); // Sensitivity
                            double minY = rangeAxis.getLowerBound();
                            double maxY = rangeAxis.getUpperBound();
                            double centerY = (minY + maxY) / 2.0;
                            double rangeY = maxY - minY;
                            double newRangeY = rangeY * zoomFactor;

                            if (newRangeY > 0) {
                                rangeAxis.setRange(centerY - newRangeY / 2.0, centerY + newRangeY / 2.0);
                            }
                        }
                        dragStart = dragEnd;
                    } else if (isAxisDragging && dragSubplotIndex == -2) {
                        // X-Axis Drag Zoom
                        double dx = dragEnd.getX() - dragStart.getX();
                        XYPlot plot = chart.getXYPlot();
                        NumberAxis domainAxis = (NumberAxis) plot.getDomainAxis();

                        double zoomFactor = 1.0 - (dx / 100.0); // Sensitivity (inverted for natural feel)
                        double minX = domainAxis.getLowerBound();
                        double maxX = domainAxis.getUpperBound();
                        double centerX = (minX + maxX) / 2.0;
                        double rangeX = maxX - minX;
                        double newRangeX = rangeX * zoomFactor;

                        if (newRangeX > 0) {
                            domainAxis.setRange(centerX - newRangeX / 2.0, centerX + newRangeX / 2.0);
                        }
                        dragStart = dragEnd;
                    } else {
                        // Regular drag = Pan (only when NOT holding shift)
                        wasShiftDrag = false;
                        double dx = dragEnd.getX() - dragStart.getX();
                        double dy = dragEnd.getY() - dragStart.getY();

                        org.jfree.chart.plot.Plot plot = chart.getPlot();
                        org.jfree.chart.plot.PlotRenderingInfo plotInfo = chartPanel.getChartRenderingInfo()
                                .getPlotInfo();
                        int subplotIndex = plotInfo.getSubplotIndex(dragStart);

                        if (subplotIndex != -1 && plot instanceof org.jfree.chart.plot.CombinedDomainXYPlot) {
                            org.jfree.chart.plot.CombinedDomainXYPlot combinedPlot = (org.jfree.chart.plot.CombinedDomainXYPlot) plot;
                            XYPlot subplot = (XYPlot) combinedPlot.getSubplots().get(subplotIndex);
                            Rectangle2D screenDataArea = plotInfo.getSubplotInfo(subplotIndex).getDataArea();

                            // 1. Horizontal Pan (shared domain axis)
                            NumberAxis domainAxis = (NumberAxis) combinedPlot.getDomainAxis();
                            double minX = domainAxis.getLowerBound();
                            double maxX = domainAxis.getUpperBound();
                            double rangeX = maxX - minX;
                            double dataMovementX = (dx / screenDataArea.getWidth()) * rangeX;
                            domainAxis.setRange(minX - dataMovementX, maxX - dataMovementX);

                            // 2. Vertical Pan (subplot range axis)
                            NumberAxis rangeAxis = (NumberAxis) subplot.getRangeAxis();
                            double minY = rangeAxis.getLowerBound();
                            double maxY = rangeAxis.getUpperBound();
                            double rangeY = maxY - minY;
                            double dataMovementY = (dy / screenDataArea.getHeight()) * rangeY;
                            rangeAxis.setRange(minY + dataMovementY, maxY + dataMovementY);
                        }

                        dragStart = dragEnd;
                    }
                }
            }
        });
    }

    private void addSeries(XYSeries series, List<Double> history, int candleCount) {
        int offset = 0;
        if (candleCount > history.size()) {
            offset = candleCount - history.size();
        }
        for (int i = 0; i < history.size(); i++) {
            series.add(i + offset, history.get(i));
        }
    }

    private int findSubplot(Point2D java2DPoint, org.jfree.chart.plot.PlotRenderingInfo plotInfo) {
        for (int i = 0; i < plotInfo.getSubplotCount(); i++) {
            Rectangle2D area = plotInfo.getSubplotInfo(i).getPlotArea();
            if (java2DPoint.getY() >= area.getMinY() && java2DPoint.getY() <= area.getMaxY()) {
                return i;
            }
        }
        return -1;
    }

    public void update(List<com.algo.core.Candle> candles, Strategy strategy) {
        this.lastCandles = candles;
        this.lastStrategy = strategy;

        if (chartPanel != null) {
            remove(chartPanel);
        }

        int count = candles.size();
        OHLCDataItem[] items = new OHLCDataItem[count];

        for (int i = 0; i < count; i++) {
            com.algo.core.Candle candle = candles.get(i);
            double open = candle.getOpen();
            double high = candle.getHigh();
            double low = candle.getLow();
            double close = candle.getClose();
            // Use simple index as X value
            items[i] = new OHLCDataItem(new Date(i), open, high, low, close, 0);
        }

        DefaultOHLCDataset dataset = new DefaultOHLCDataset("Candles", items);

        XYSeries ema15Series = new XYSeries("EMA15");
        XYSeries ema15_5Series = new XYSeries("EMA15_5");
        XYSeries ema15_15Series = new XYSeries("EMA15_15");
        XYSeries ema15_15_15Series = new XYSeries("EMA15_15_15");
        XYSeries ema15_30Series = new XYSeries("EMA15_30");
        XYSeries stmaSeries = new XYSeries("ST MA");
        XYSeries stUpSeries = new XYSeries("ST Up");
        XYSeries stDnSeries = new XYSeries("ST Dn");
        XYSeries ohlc4Series = new XYSeries("OHLC4");
        XYSeries stActiveSeries = new XYSeries("ST Active");

        List<Double> h15 = strategy.getEma15History();
        List<Double> h5 = strategy.getEma15_5History();
        List<Double> h15_15 = strategy.getEma15_15History();
        List<Double> h15_15_15 = strategy.getEma15_15_15History();
        List<Double> h30 = strategy.getEma15_15_15_30History();

        addSeries(ema15Series, h15, candles.size());
        addSeries(ema15_5Series, h5, candles.size());
        addSeries(ema15_15Series, h15_15, candles.size());
        addSeries(ema15_15_15Series, h15_15_15, candles.size());
        addSeries(ema15_30Series, h30, candles.size());

        // Add SuperTrend Data
        com.algo.indicators.SuperTrendedMA stma = strategy.getStma();
        if (stma != null) {
            addSeries(stmaSeries, stma.getMaHistory(), candles.size());

            List<Double> ups = stma.getUpHistory();
            List<Double> dns = stma.getDnHistory();
            List<Integer> trends = stma.getTrendHistory();

            int offset = 0;
            if (candles.size() > ups.size()) {
                offset = candles.size() - ups.size();
            }

            for (int i = 0; i < ups.size(); i++) {
                int trend = trends.get(i);
                double stValue = 0;
                if (trend == 1) {
                    stValue = ups.get(i);
                    stUpSeries.add(i + offset, stValue);
                    stDnSeries.add(i + offset, null); // Break line
                } else {
                    stValue = dns.get(i);
                    stUpSeries.add(i + offset, null); // Break line
                    stDnSeries.add(i + offset, stValue);
                }
                stActiveSeries.add(i + offset, stValue);

                // Calculate OHLC4 for shading
                if (i + offset < candles.size()) {
                    com.algo.core.Candle c = candles.get(i + offset);
                    double ohlc4 = (c.getOpen() + c.getHigh() + c.getLow() + c.getClose()) / 4.0;
                    ohlc4Series.add(i + offset, ohlc4);
                }
            }
        }

        XYSeriesCollection indicatorDataset = new XYSeriesCollection();
        indicatorDataset.addSeries(ema15Series); // 0
        indicatorDataset.addSeries(ema15_5Series); // 1
        indicatorDataset.addSeries(ema15_15Series); // 2
        indicatorDataset.addSeries(ema15_15_15Series); // 3
        indicatorDataset.addSeries(ema15_30Series); // 4
        indicatorDataset.addSeries(stmaSeries); // 5
        indicatorDataset.addSeries(stUpSeries); // 6
        indicatorDataset.addSeries(stDnSeries); // 7

        XYSeriesCollection shadingDataset = new XYSeriesCollection();
        shadingDataset.addSeries(ohlc4Series);
        shadingDataset.addSeries(stActiveSeries);

        chart = ChartFactory.createCandlestickChart("Candlestick Chart", "Time (1m)", "Price", dataset, false);
        chart.setBackgroundPaint(Color.BLACK);
        chart.getTitle().setPaint(Color.WHITE);

        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(Color.BLACK);
        plot.setDomainGridlinePaint(Color.DARK_GRAY);
        plot.setRangeGridlinePaint(Color.DARK_GRAY);
        plot.setRangeAxisLocation(org.jfree.chart.axis.AxisLocation.BOTTOM_OR_RIGHT);

        // 1. Style Candles
        Color upColor = new Color(76, 175, 80); // #4caf50
        Color downColor = new Color(242, 54, 69); // #f23645

        // 0. Style Shading
        XYDifferenceRenderer shadingRenderer = new XYDifferenceRenderer();
        shadingRenderer.setPositivePaint(new Color(76, 175, 80, 153)); // #4caf50 with 60% opacity
        shadingRenderer.setNegativePaint(new Color(242, 54, 69, 153)); // #f23645 with 60% opacity
        shadingRenderer.setShapesVisible(false);

        // Hide the border lines of the shaded area (make them transparent)
        shadingRenderer.setSeriesPaint(0, new Color(0, 0, 0, 0)); // Transparent
        shadingRenderer.setSeriesPaint(1, new Color(0, 0, 0, 0)); // Transparent
        shadingRenderer.setSeriesStroke(0, new BasicStroke(0.0f)); // Zero width
        shadingRenderer.setSeriesStroke(1, new BasicStroke(0.0f)); // Zero width

        shadingRenderer.setSeriesVisible(0, cbStShade.isSelected());
        shadingRenderer.setSeriesVisible(1, cbStShade.isSelected());

        plot.setDataset(0, shadingDataset);
        plot.setRenderer(0, shadingRenderer);

        // 1. Style Candles
        CandlestickRenderer renderer = new CandlestickRenderer() {
            @Override
            public Paint getItemOutlinePaint(int row, int column) {
                OHLCDataset dataset = (OHLCDataset) getPlot().getDataset(1);
                double open = dataset.getOpenValue(row, column);
                double close = dataset.getCloseValue(row, column);
                return (close >= open) ? upColor : downColor;
            }
        };
        renderer.setUpPaint(upColor);
        renderer.setDownPaint(downColor);
        renderer.setUseOutlinePaint(true);
        plot.setDataset(1, dataset);
        plot.setRenderer(1, renderer);

        // 2. Style Indicators and Signals
        XYLineAndShapeRenderer indicatorRenderer = new XYLineAndShapeRenderer(true, false);
        indicatorRenderer.setSeriesPaint(0, Color.WHITE); // EMA15
        indicatorRenderer.setSeriesPaint(1, Color.RED); // EMA15_5
        indicatorRenderer.setSeriesPaint(2, Color.BLUE); // EMA15_15
        indicatorRenderer.setSeriesPaint(3, Color.RED); // EMA15_15_15
        indicatorRenderer.setSeriesPaint(4, Color.YELLOW); // EMA15_30
        indicatorRenderer.setSeriesPaint(5, Color.GRAY); // ST MA
        indicatorRenderer.setSeriesPaint(6, upColor); // ST Up
        indicatorRenderer.setSeriesPaint(7, downColor); // ST Dn

        for (int i = 0; i < 8; i++) {
            indicatorRenderer.setSeriesStroke(i, new BasicStroke(1.5f));
        }

        // Prevent drawing lines across null values (gaps in discontinuous series)
        indicatorRenderer.setDrawSeriesLineAsPath(true);

        // Set Visibility
        indicatorRenderer.setSeriesVisible(0, cbEma15.isSelected());
        indicatorRenderer.setSeriesVisible(1, cbEma15_5.isSelected());
        indicatorRenderer.setSeriesVisible(2, cbEma15_15.isSelected());
        indicatorRenderer.setSeriesVisible(3, cbEma15_15_15.isSelected());
        indicatorRenderer.setSeriesVisible(4, cbEma15_30.isSelected());
        indicatorRenderer.setSeriesVisible(5, cbStMa.isSelected());
        indicatorRenderer.setSeriesVisible(6, cbStUp.isSelected());
        indicatorRenderer.setSeriesVisible(7, cbStDn.isSelected());

        plot.setDataset(2, indicatorDataset);
        plot.setRenderer(2, indicatorRenderer);

        // 3. Style Axes
        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setTickLabelPaint(Color.WHITE);
        rangeAxis.setLabelPaint(Color.WHITE);
        rangeAxis.setAutoRangeIncludesZero(false);
        plot.setRangeGridlinePaint(Color.DARK_GRAY);

        // --- MACD Plot ---
        XYSeries macdLineSeries = new XYSeries("MACD");
        XYSeries signalLineSeries = new XYSeries("Signal");
        XYSeries maed5Series = new XYSeries("MAED_5");
        XYSeries macd55Series = new XYSeries("MACD_5_5");

        List<Double> macdH = strategy.getMacdHistory();
        List<Double> signalH = strategy.getSignalHistory();
        List<Double> maed5H = strategy.getMaed5History();
        List<Double> macd55H = strategy.getMacd55History();

        addSeries(macdLineSeries, macdH, candles.size());
        addSeries(signalLineSeries, signalH, candles.size());
        addSeries(maed5Series, maed5H, candles.size());
        addSeries(macd55Series, macd55H, candles.size());

        XYSeriesCollection macdDataset = new XYSeriesCollection();
        macdDataset.addSeries(macdLineSeries);
        macdDataset.addSeries(signalLineSeries);
        macdDataset.addSeries(maed5Series);
        macdDataset.addSeries(macd55Series);

        NumberAxis macdRangeAxis = new NumberAxis("MACD");
        macdRangeAxis.setTickLabelPaint(Color.WHITE);
        macdRangeAxis.setLabelPaint(Color.WHITE);
        macdRangeAxis.setAutoRangeIncludesZero(false);

        XYPlot macdPlot = new XYPlot(macdDataset, null, macdRangeAxis, null);
        macdPlot.setBackgroundPaint(Color.BLACK);
        macdPlot.setDomainGridlinePaint(Color.DARK_GRAY);
        macdPlot.setRangeGridlinePaint(Color.DARK_GRAY);
        macdPlot.setRangeAxisLocation(org.jfree.chart.axis.AxisLocation.BOTTOM_OR_RIGHT);

        // Add Zero Line
        org.jfree.chart.plot.ValueMarker zeroMarker = new org.jfree.chart.plot.ValueMarker(0.0);
        zeroMarker.setPaint(Color.DARK_GRAY);
        zeroMarker.setStroke(
                new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[] { 5.0f }, 0.0f));
        macdPlot.addRangeMarker(zeroMarker);

        XYLineAndShapeRenderer macdRenderer = new XYLineAndShapeRenderer(true, false);
        macdRenderer.setSeriesPaint(0, Color.CYAN); // MACD Line
        macdRenderer.setSeriesPaint(1, Color.ORANGE); // Signal Line
        macdRenderer.setSeriesPaint(2, Color.MAGENTA); // MAED_5
        macdRenderer.setSeriesPaint(3, Color.PINK); // MACD_5_5

        macdPlot.setRenderer(macdRenderer);

        // --- Combined Plot ---
        NumberAxis domainAxis = new NumberAxis("Time (3m)");
        domainAxis.setTickLabelPaint(Color.WHITE);
        domainAxis.setLabelPaint(Color.WHITE);

        // Format X-Axis to show Time instead of Index
        java.time.format.DateTimeFormatter timeFmt = java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm");
        domainAxis.setNumberFormatOverride(new java.text.NumberFormat() {
            @Override
            public StringBuffer format(double number, StringBuffer toAppendTo, java.text.FieldPosition pos) {
                int index = (int) Math.round(number);
                if (index >= 0 && index < candles.size()) {
                    return toAppendTo.append(candles.get(index).getTimestamp().format(timeFmt));
                }
                return toAppendTo;
            }

            @Override
            public StringBuffer format(long number, StringBuffer toAppendTo, java.text.FieldPosition pos) {
                return format((double) number, toAppendTo, pos);
            }

            @Override
            public Number parse(String source, java.text.ParsePosition parsePosition) {
                return null;
            }
        });

        domainAxis.setStandardTickUnits(NumberAxis.createIntegerTickUnits());

        org.jfree.chart.plot.CombinedDomainXYPlot combinedPlot = new org.jfree.chart.plot.CombinedDomainXYPlot(
                domainAxis);
        combinedPlot.setGap(10.0);
        combinedPlot.add(plot, 3); // Main plot takes 3/4 space
        combinedPlot.add(macdPlot, 1); // MACD plot takes 1/4 space
        combinedPlot.setOrientation(org.jfree.chart.plot.PlotOrientation.VERTICAL);

        chart = new JFreeChart("Renko Chart with MACD", JFreeChart.DEFAULT_TITLE_FONT, combinedPlot, false);
        chart.setBackgroundPaint(Color.BLACK);
        chart.getTitle().setPaint(Color.WHITE);

        chartPanel = new ChartPanel(chart);
        chartPanel.setMouseWheelEnabled(false);
        chartPanel.setDomainZoomable(false);
        chartPanel.setRangeZoomable(false);

        setupZoomButtons();
        setupPanningListeners();

        add(chartPanel, BorderLayout.CENTER);

        revalidate();
        repaint();
    }
}
