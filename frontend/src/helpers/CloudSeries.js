
class CloudRenderer {
    constructor() {
        this._data = null;
    }

    draw(target, priceConverter) {
        target.useBitmapCoordinateSpace(scope => {
            const bars = this._data ? (this._data.bars || this._data.items) : null;
            if (!bars || bars.length < 2) return;

            const ctx = scope.context;

            // 1. ATOMIC WIPE: The 'Nuclear' clear option. 
            // We reset the transform and clear the entire bitmap buffer.
            ctx.save();
            ctx.setTransform(1, 0, 0, 1, 0, 0);
            ctx.clearRect(0, 0, scope.bitmapSize.width, scope.bitmapSize.height);
            ctx.restore();

            ctx.save();

            // 2. INFINITE RIBBON RENDERING
            // We draw the shading as a single continuous batch. 
            // We do NOT filter for visibility here; the browser's clipping handles 
            // the edges, which moves the 'vertical walls' way off-screen.

            const drawCloudBatch = (batchItems, color) => {
                if (batchItems.length < 2) return;

                ctx.fillStyle = color;
                ctx.beginPath();

                // Trace Upper Line
                let first = true;
                for (const it of batchItems) {
                    const y = priceConverter(it.originalData.upper);
                    if (y !== null) {
                        if (first) { ctx.moveTo(it.x, y); first = false; }
                        else ctx.lineTo(it.x, y);
                    }
                }

                // Trace Lower Line (backwards)
                for (let i = batchItems.length - 1; i >= 0; i--) {
                    const it = batchItems[i];
                    const y = priceConverter(it.originalData.lower);
                    if (y !== null) ctx.lineTo(it.x, y);
                }

                ctx.closePath();
                ctx.fill();
            };

            let currentBatch = [];
            let currentColor = null;

            for (let i = 0; i < bars.length; i++) {
                const item = bars[i];
                const itemColor = item.originalData.color || (item.originalData.upper > item.originalData.lower ? 'rgba(60, 120, 255, 0.6)' : 'rgba(255, 0, 0, 0.6)');

                if (currentColor !== null && itemColor !== currentColor) {
                    // Draw current trend batch
                    drawCloudBatch(currentBatch, currentColor);
                    // Start new batch, connecting to the last point to ensure continuity
                    currentBatch = [bars[i - 1], item];
                    currentColor = itemColor;
                } else {
                    currentBatch.push(item);
                    currentColor = itemColor;
                }
            }
            // Draw last batch
            drawCloudBatch(currentBatch, currentColor);

            ctx.restore();
        });
    }

    update(data) {
        this._data = data;
    }
}

const sharedRenderer = new CloudRenderer();

export const CloudSeriesDefinition = {
    renderer: function () {
        return sharedRenderer;
    },
    update: function (data, options) {
        sharedRenderer.update(data);
    },
    priceValueBuilder: function (plotRow) {
        return [plotRow.upper, plotRow.lower];
    },
    isWhitespace: function (data) {
        return !data || data.upper === undefined || data.lower === undefined;
    },
    defaultOptions: function () {
        return { color: 'rgba(0, 255, 0, 0.2)' };
    },
};
