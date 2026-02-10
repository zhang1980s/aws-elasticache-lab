package com.example.failoverlab.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
@Getter
public class FailoverMetrics {

    private final MeterRegistry meterRegistry;

    // Connection metrics
    private final AtomicLong connectionDropDurationMs = new AtomicLong(0);
    private final Counter topologyRefreshCounter;
    private final AtomicLong currentConnectionState = new AtomicLong(1); // 1 = connected, 0 = disconnected

    // Operation metrics
    private final Counter operationsSuccessCounter;
    private final Counter operationsFailedCounter;
    private final Counter operationsFailedDuringFailoverCounter;
    private final Timer operationLatencyTimer;

    // GET/SET specific metrics
    private final Counter getsetSuccessCounter;
    private final Counter getsetFailedCounter;
    private final Counter sequenceGapCounter;
    private final AtomicLong lastSequenceNumber = new AtomicLong(0);

    // Pub/Sub metrics
    private final Counter pubsubPublishedCounter;
    private final Counter pubsubReceivedCounter;
    private final Counter pubsubLossCounter;
    private final ConcurrentHashMap<String, Long> publishedMessages = new ConcurrentHashMap<>();

    // Streams metrics
    private final Counter streamsAddedCounter;
    private final Counter streamsConsumedCounter;
    private final AtomicLong streamsLagMs = new AtomicLong(0);

    // Failover tracking
    private volatile long disconnectTimestamp = 0;
    private volatile long reconnectTimestamp = 0;
    private volatile boolean inFailover = false;

    public FailoverMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // Register connection metrics
        Gauge.builder("connection.drop.duration.ms", connectionDropDurationMs, AtomicLong::get)
                .description("Duration of connection drop in milliseconds")
                .register(meterRegistry);

        this.topologyRefreshCounter = Counter.builder("topology.refresh.count")
                .description("Number of topology refresh events")
                .register(meterRegistry);

        Gauge.builder("connection.state", currentConnectionState, AtomicLong::get)
                .description("Current connection state (1=connected, 0=disconnected)")
                .register(meterRegistry);

        // Register operation metrics
        this.operationsSuccessCounter = Counter.builder("operations.success")
                .description("Total successful operations")
                .register(meterRegistry);

        this.operationsFailedCounter = Counter.builder("operations.failed")
                .description("Total failed operations")
                .register(meterRegistry);

        this.operationsFailedDuringFailoverCounter = Counter.builder("operations.failed.during.failover")
                .description("Operations failed during failover")
                .register(meterRegistry);

        this.operationLatencyTimer = Timer.builder("operations.latency")
                .description("Operation latency distribution")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        // GET/SET metrics
        this.getsetSuccessCounter = Counter.builder("getset.operations.success")
                .description("Successful GET/SET operations")
                .register(meterRegistry);

        this.getsetFailedCounter = Counter.builder("getset.operations.failed")
                .description("Failed GET/SET operations")
                .register(meterRegistry);

        this.sequenceGapCounter = Counter.builder("getset.sequence.gaps")
                .description("Number of sequence gaps detected")
                .register(meterRegistry);

        // Pub/Sub metrics
        this.pubsubPublishedCounter = Counter.builder("pubsub.messages.published")
                .description("Total messages published")
                .register(meterRegistry);

        this.pubsubReceivedCounter = Counter.builder("pubsub.messages.received")
                .description("Total messages received")
                .register(meterRegistry);

        this.pubsubLossCounter = Counter.builder("pubsub.message.loss.count")
                .description("Number of messages lost")
                .register(meterRegistry);

        // Streams metrics
        this.streamsAddedCounter = Counter.builder("streams.messages.added")
                .description("Messages added to streams")
                .register(meterRegistry);

        this.streamsConsumedCounter = Counter.builder("streams.messages.consumed")
                .description("Messages consumed from streams")
                .register(meterRegistry);

        Gauge.builder("streams.lag.ms", streamsLagMs, AtomicLong::get)
                .description("Stream consumer lag in milliseconds")
                .register(meterRegistry);

        log.info("FailoverMetrics initialized");
    }

    // Connection tracking methods
    public void recordDisconnect() {
        disconnectTimestamp = System.currentTimeMillis();
        currentConnectionState.set(0);
        inFailover = true;
        log.warn("Connection disconnected at timestamp: {}", disconnectTimestamp);
    }

    public void recordReconnect() {
        reconnectTimestamp = System.currentTimeMillis();
        currentConnectionState.set(1);
        if (disconnectTimestamp > 0) {
            long duration = reconnectTimestamp - disconnectTimestamp;
            connectionDropDurationMs.set(duration);
            log.info("Connection restored after {} ms", duration);
        }
        inFailover = false;
    }

    public void recordTopologyRefresh() {
        topologyRefreshCounter.increment();
        log.debug("Topology refresh triggered");
    }

    // Operation tracking methods
    public void recordOperationSuccess(long latencyMs) {
        operationsSuccessCounter.increment();
        operationLatencyTimer.record(latencyMs, TimeUnit.MILLISECONDS);
    }

    public void recordOperationFailure() {
        operationsFailedCounter.increment();
        if (inFailover) {
            operationsFailedDuringFailoverCounter.increment();
        }
    }

    // GET/SET tracking methods
    public void recordGetSetSuccess(long latencyMs) {
        getsetSuccessCounter.increment();
        recordOperationSuccess(latencyMs);
    }

    public void recordGetSetFailure() {
        getsetFailedCounter.increment();
        recordOperationFailure();
    }

    public void recordSequenceGap(long expected, long actual) {
        long gaps = actual - expected;
        for (int i = 0; i < gaps; i++) {
            sequenceGapCounter.increment();
        }
        log.warn("Sequence gap detected: expected {}, got {}", expected, actual);
    }

    public long getAndIncrementSequence() {
        return lastSequenceNumber.getAndIncrement();
    }

    public void setLastSequence(long seq) {
        lastSequenceNumber.set(seq);
    }

    // Pub/Sub tracking methods
    public void recordPubSubPublished(String messageId) {
        pubsubPublishedCounter.increment();
        publishedMessages.put(messageId, System.currentTimeMillis());
    }

    public void recordPubSubReceived(String messageId) {
        pubsubReceivedCounter.increment();
        publishedMessages.remove(messageId);
    }

    public void checkForLostMessages(long timeoutMs) {
        long now = System.currentTimeMillis();
        publishedMessages.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > timeoutMs) {
                pubsubLossCounter.increment();
                log.warn("Message {} considered lost (published {} ms ago)",
                        entry.getKey(), now - entry.getValue());
                return true;
            }
            return false;
        });
    }

    // Streams tracking methods
    public void recordStreamAdd() {
        streamsAddedCounter.increment();
    }

    public void recordStreamConsume() {
        streamsConsumedCounter.increment();
    }

    public void recordStreamLag(long lagMs) {
        streamsLagMs.set(lagMs);
    }

    public boolean isInFailover() {
        return inFailover;
    }
}
