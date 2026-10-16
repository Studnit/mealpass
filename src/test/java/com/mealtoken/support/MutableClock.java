package com.mealtoken.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/** A clock tests can move forward; zone-adjusted copies share the same underlying instant. */
public class MutableClock extends Clock {
    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public MutableClock() { this(new AtomicReference<>(Instant.now()), ZoneId.of("UTC")); }

    private MutableClock(AtomicReference<Instant> now, ZoneId zone) { this.now = now; this.zone = zone; }

    public void advance(Duration d) { now.updateAndGet(i -> i.plus(d)); }
    public void reset() { now.set(Instant.now()); }

    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId z) { return new MutableClock(now, z); }
    @Override public Instant instant() { return now.get(); }
}
