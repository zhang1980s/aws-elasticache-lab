package com.example.failoverlab.workload;

import com.example.failoverlab.metrics.FailoverMetrics;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RequiredArgsConstructor
public class GetSetWorkload {

    private final RedisAdvancedClusterAsyncCommands<String, String> asyncCommands;
    private final FailoverMetrics failoverMetrics;

    @Value("${workload.mode}")
    private String workloadMode;

    @Value("${workload.types}")
    private String workloadTypes;

    @Value("${workload.ops-per-second}")
    private int opsPerSecond;

    @Value("${workload.message-size-bytes}")
    private int messageSizeBytes;

    private String instanceId;
    private boolean enabled = false;
    private long operationIntervalMs;

    private static final String KEY_PREFIX = "redis-failover-lab:getset:";
    private static final String SEQUENCE_KEY = "redis-failover-lab:sequence";

    @PostConstruct
    public void init() {
        this.instanceId = UUID.randomUUID().toString().substring(0, 8);
        this.enabled = workloadTypes.contains("getset");
        this.operationIntervalMs = 1000 / Math.max(1, opsPerSecond / 3); // Divide ops among 3 workload types

        if (enabled) {
            log.info("GetSetWorkload enabled: instanceId={}, opsPerSecond={}, messageSizeBytes={}",
                    instanceId, opsPerSecond, messageSizeBytes);
        } else {
            log.info("GetSetWorkload disabled");
        }
    }

    @Scheduled(fixedRateString = "#{${workload.ops-per-second} > 0 ? (1000 / ${workload.ops-per-second}) : 1000}")
    public void executeWorkload() {
        if (!enabled) {
            return;
        }

        boolean isProducer = "producer".equals(workloadMode) || "both".equals(workloadMode);
        boolean isConsumer = "consumer".equals(workloadMode) || "both".equals(workloadMode);

        if (isProducer) {
            executeProducer();
        }

        if (isConsumer) {
            executeConsumer();
        }
    }

    private void executeProducer() {
        long startTime = System.currentTimeMillis();
        long sequence = failoverMetrics.getAndIncrementSequence();

        String key = KEY_PREFIX + instanceId + ":" + sequence;
        String value = generatePayload(sequence);

        try {
            RedisFuture<String> setFuture = asyncCommands.set(key, value);

            setFuture.whenComplete((result, throwable) -> {
                long latency = System.currentTimeMillis() - startTime;

                if (throwable != null) {
                    log.error("SET failed for key {}: {}", key, throwable.getMessage());
                    failoverMetrics.recordGetSetFailure();
                } else {
                    log.debug("SET success: key={}, latency={}ms", key, latency);
                    failoverMetrics.recordGetSetSuccess(latency);

                    // Also increment the sequence counter in Redis
                    asyncCommands.incr(SEQUENCE_KEY);
                }
            });

            // Set TTL for the key
            asyncCommands.expire(key, 300); // 5 minutes TTL

        } catch (Exception e) {
            log.error("Producer error: {}", e.getMessage());
            failoverMetrics.recordGetSetFailure();
        }
    }

    private void executeConsumer() {
        long startTime = System.currentTimeMillis();

        try {
            // Read the current sequence number
            RedisFuture<String> seqFuture = asyncCommands.get(SEQUENCE_KEY);

            seqFuture.whenComplete((result, throwable) -> {
                long latency = System.currentTimeMillis() - startTime;

                if (throwable != null) {
                    log.error("GET sequence failed: {}", throwable.getMessage());
                    failoverMetrics.recordGetSetFailure();
                } else if (result != null) {
                    long currentSeq = Long.parseLong(result);
                    long lastKnown = failoverMetrics.getLastSequenceNumber().get();

                    // Check for gaps
                    if (lastKnown > 0 && currentSeq > lastKnown + 1) {
                        failoverMetrics.recordSequenceGap(lastKnown + 1, currentSeq);
                    }

                    failoverMetrics.setLastSequence(currentSeq);
                    failoverMetrics.recordGetSetSuccess(latency);

                    log.debug("GET sequence: current={}, lastKnown={}, latency={}ms",
                            currentSeq, lastKnown, latency);
                }
            });

        } catch (Exception e) {
            log.error("Consumer error: {}", e.getMessage());
            failoverMetrics.recordGetSetFailure();
        }
    }

    private String generatePayload(long sequence) {
        StringBuilder payload = new StringBuilder();
        payload.append("{\"seq\":").append(sequence);
        payload.append(",\"ts\":").append(System.currentTimeMillis());
        payload.append(",\"src\":\"").append(instanceId).append("\"");
        payload.append(",\"data\":\"");

        // Pad to reach desired size
        int currentSize = payload.length() + 2; // +2 for closing quotes
        int paddingNeeded = Math.max(0, messageSizeBytes - currentSize);

        if (paddingNeeded > 0) {
            SecureRandom random = new SecureRandom();
            byte[] paddingBytes = new byte[paddingNeeded];
            random.nextBytes(paddingBytes);
            for (byte b : paddingBytes) {
                payload.append((char) ('a' + (b & 0x0F)));
            }
        }

        payload.append("\"}");
        return payload.toString();
    }

    public void runBurstWorkload(int operations) {
        if (!enabled) {
            log.warn("GetSetWorkload is not enabled");
            return;
        }

        log.info("Running burst workload: {} operations", operations);
        long startTime = System.currentTimeMillis();
        int successCount = 0;
        int failCount = 0;

        for (int i = 0; i < operations; i++) {
            try {
                long seq = failoverMetrics.getAndIncrementSequence();
                String key = KEY_PREFIX + instanceId + ":burst:" + seq;
                String value = generatePayload(seq);

                String result = asyncCommands.set(key, value).get(5, TimeUnit.SECONDS);
                if ("OK".equals(result)) {
                    successCount++;
                    failoverMetrics.recordGetSetSuccess(System.currentTimeMillis() - startTime);
                }
            } catch (Exception e) {
                failCount++;
                failoverMetrics.recordGetSetFailure();
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Burst completed: success={}, failed={}, duration={}ms, throughput={} ops/sec",
                successCount, failCount, duration, (successCount * 1000) / Math.max(1, duration));
    }
}
