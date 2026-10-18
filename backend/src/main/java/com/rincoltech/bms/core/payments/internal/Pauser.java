package com.rincoltech.bms.core.payments.internal;

import java.time.Duration;

/** The pause between two attempts to create an order; a bean so a test never sleeps. */
interface Pauser {

    void pause(Duration pause) throws InterruptedException;
}
