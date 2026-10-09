import React, { useState, useEffect } from 'react';

/**
 * High-precision candle close countdown timer for chart headers.
 */
const CountdownTimer = React.memo(({ interval, isReplayMode }) => {
    const [timeRemaining, setTimeRemaining] = useState('--:--');

    useEffect(() => {
        const calculateRemaining = () => {
            if (isReplayMode || !interval || !interval.includes('min')) return '--:--';
            const now = new Date();
            const min = now.getMinutes(), sec = now.getSeconds();
            let totalMins = 1;
            if (interval === '3min') totalMins = 3 - (min % 3);
            else if (interval === '5min') totalMins = 5 - (min % 5);

            let rMin = totalMins - 1, rSec = 60 - sec;
            if (rSec === 60) {
                rMin += 1;
                rSec = 0;
            }
            return `${rMin.toString().padStart(2, '0')}:${rSec.toString().padStart(2, '0')}`;
        };

        const timer = setInterval(() => setTimeRemaining(calculateRemaining()), 1000);
        setTimeRemaining(calculateRemaining());
        return () => clearInterval(timer);
    }, [interval, isReplayMode]);

    return (
        <span style={{ color: '#cbd5e1', fontSize: '10px', minWidth: '35px' }}>
            {timeRemaining}
        </span>
    );
});

export default CountdownTimer;
