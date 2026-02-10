package com.example.failoverlab.workload;

import com.example.failoverlab.metrics.FailoverMetrics;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
@RequiredArgsConstructor
public class StreamsWorkload {

    private final RedisAdvancedClusterAsyncCommands<String, String> asyncCommands;
    private final RedisAdvancedClusterCommands<String, String> syncCommands;
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
    private String consumerId;
    private boolean enabled = false;
    private boolean consumerGroupCreated = false;

    private static final String STREAM_KEY = "failover-lab:stream:main";
    private static final String CONSUMER_GROUP = "failover-lab-consumers";

    private final AtomicLong addedCount = new AtomicLong(0);
    private final AtomicLong consumedCount = new AtomicLong(0);
    private String lastConsumedId = ">";

    @PostConstruct
    public void init() {
        this.instanceId = UUID.randomUUID().toString().substring(0, 8);
        this.consumerId = "consumer-" + instanceId;
        this.enabled = workloadTypes.contains("streams");

        if (enabled) {
            initializeConsumerGroup();
            log.info("StreamsWorkload enabled: instanceId={}, consumerId={}", instanceId, consumerId);
        } else {
            log.info("StreamsWorkload disabled");
        }
    }

    private void initializeConsumerGroup() {
        boolean isConsumer = "consumer".equals(workloadMode) || "both".equals(workloadMode);

        if (!isConsumer) {
            log.info("Skipping consumer group creation - not in consumer mode");
            return;
        }

        try {
            // Create the stream if it doesn't exist
            Map<String, String> initialMessage = new HashMap<>();
            initialMessage.put("init", "true");
            initialMessage.put("timestamp", String.valueOf(System.currentTimeMillis()));

            try {
                syncCommands.xadd(STREAM_KEY, initialMessage);
                log.info("Stream {} created", STREAM_KEY);
            } catch (Exception e) {
                log.debug("Stream may already exist: {}", e.getMessage());
            }

            // Create consumer group
            try {
                syncCommands.xgroupCreate(
                        XReadArgs.StreamOffset.from(STREAM_KEY, "0"),
                        CONSUMER_GROUP,
                        XGroupCreateArgs.Builder.mkstream());
                consumerGroupCreated = true;
                log.info("Consumer group {} created", CONSUMER_GROUP);
            } catch (Exception e) {
                if (e.getMessage().contains("BUSYGROUP")) {
                    consumerGroupCreated = true;
                    log.info("Consumer group {} already exists", CONSUMER_GROUP);
                } else {
                    log.error("Failed to create consumer group: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Error initializing consumer group: {}", e.getMessage());
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
            addToStream();
        }

        if (isConsumer && consumerGroupCreated) {
            consumeFromStream();
        }
    }

    private void addToStream() {
        long sequence = addedCount.incrementAndGet();
        long timestamp = System.currentTimeMillis();

        Map<String, String> fields = new HashMap<>();
        fields.put("seq", String.valueOf(sequence));
        fields.put("ts", String.valueOf(timestamp));
        fields.put("src", instanceId);
        fields.put("data", generatePayload());

        try {
            RedisFuture<String> future = asyncCommands.xadd(
                    STREAM_KEY,
                    XAddArgs.Builder.maxlen(10000).approximateTrimming(),
                    fields);

            future.whenComplete((messageId, throwable) -> {
                if (throwable != null) {
                    log.error("XADD failed: {}", throwable.getMessage());
                    failoverMetrics.recordOperationFailure();
                } else {
                    log.debug("XADD success: messageId={}", messageId);
                    failoverMetrics.recordStreamAdd();
                    failoverMetrics.recordOperationSuccess(System.currentTimeMillis() - timestamp);
                }
            });

        } catch (Exception e) {
            log.error("Error adding to stream: {}", e.getMessage());
            failoverMetrics.recordOperationFailure();
        }
    }

    private void consumeFromStream() {
        try {
            long startTime = System.currentTimeMillis();

            // Use XREADGROUP to consume messages
            List<StreamMessage<String, String>> messages = syncCommands.xreadgroup(
                    io.lettuce.core.Consumer.from(CONSUMER_GROUP, consumerId),
                    XReadArgs.Builder.count(10).block(100),
                    XReadArgs.StreamOffset.lastConsumed(STREAM_KEY));

            if (messages != null && !messages.isEmpty()) {
                for (StreamMessage<String, String> message : messages) {
                    processMessage(message);

                    // Acknowledge the message
                    asyncCommands.xack(STREAM_KEY, CONSUMER_GROUP, message.getId());
                }

                long latency = System.currentTimeMillis() - startTime;
                log.debug("Consumed {} messages in {}ms", messages.size(), latency);
            }

        } catch (Exception e) {
            log.error("Error consuming from stream: {}", e.getMessage());
            failoverMetrics.recordOperationFailure();
        }
    }

    private void processMessage(StreamMessage<String, String> message) {
        consumedCount.incrementAndGet();
        failoverMetrics.recordStreamConsume();

        Map<String, String> body = message.getBody();
        String timestampStr = body.get("ts");

        if (timestampStr != null) {
            try {
                long messageTimestamp = Long.parseLong(timestampStr);
                long lag = System.currentTimeMillis() - messageTimestamp;
                failoverMetrics.recordStreamLag(lag);

                if (lag > 1000) {
                    log.warn("High stream lag: {}ms for message {}", lag, message.getId());
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid timestamp in message: {}", timestampStr);
            }
        }

        lastConsumedId = message.getId();
        log.debug("Processed stream message: id={}, seq={}", message.getId(), body.get("seq"));
    }

    @Scheduled(fixedRate = 10000)
    public void checkStreamHealth() {
        if (!enabled) {
            return;
        }

        try {
            // Get stream info
            Long length = syncCommands.xlen(STREAM_KEY);
            log.info("Stream status: length={}, added={}, consumed={}",
                    length, addedCount.get(), consumedCount.get());

            // Get pending messages count
            if (consumerGroupCreated) {
                try {
                    var pending = syncCommands.xpending(STREAM_KEY, CONSUMER_GROUP);
                    if (pending != null) {
                        log.info("Pending messages: count={}", pending.getCount());

                        if (pending.getCount() > 100) {
                            log.warn("High number of pending messages: {}", pending.getCount());
                        }
                    }
                } catch (Exception e) {
                    log.debug("Could not get pending info: {}", e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("Error checking stream health: {}", e.getMessage());
        }
    }

    @Scheduled(fixedRate = 30000)
    public void claimOldMessages() {
        if (!enabled || !consumerGroupCreated) {
            return;
        }

        boolean isConsumer = "consumer".equals(workloadMode) || "both".equals(workloadMode);
        if (!isConsumer) {
            return;
        }

        try {
            // Claim messages that have been pending for more than 30 seconds
            List<StreamMessage<String, String>> claimed = syncCommands.xautoclaim(
                    STREAM_KEY,
                    XReadArgs.Builder.count(10).block(0),
                    io.lettuce.core.Consumer.from(CONSUMER_GROUP, consumerId),
                    30000, // min idle time in ms
                    "0");

            if (claimed != null && !claimed.isEmpty()) {
                log.info("Auto-claimed {} old messages", claimed.size());
                for (StreamMessage<String, String> message : claimed) {
                    processMessage(message);
                    asyncCommands.xack(STREAM_KEY, CONSUMER_GROUP, message.getId());
                }
            }

        } catch (Exception e) {
            log.debug("Auto-claim not available or failed: {}", e.getMessage());
        }
    }

    private String generatePayload() {
        StringBuilder sb = new StringBuilder();
        int targetSize = Math.max(10, messageSizeBytes - 50); // Account for other fields

        while (sb.length() < targetSize) {
            sb.append(UUID.randomUUID().toString().replace("-", ""));
        }

        return sb.substring(0, Math.min(sb.length(), targetSize));
    }

    public long getAddedCount() {
        return addedCount.get();
    }

    public long getConsumedCount() {
        return consumedCount.get();
    }
}
