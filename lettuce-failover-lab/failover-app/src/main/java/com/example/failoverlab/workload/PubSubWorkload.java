package com.example.failoverlab.workload;

import com.example.failoverlab.metrics.FailoverMetrics;
import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands;
import io.lettuce.core.cluster.pubsub.RedisClusterPubSubListener;
import io.lettuce.core.cluster.pubsub.StatefulRedisClusterPubSubConnection;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
@RequiredArgsConstructor
public class PubSubWorkload {

    private final RedisAdvancedClusterAsyncCommands<String, String> asyncCommands;
    private final StatefulRedisClusterPubSubConnection<String, String> pubSubConnection;
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
    private boolean subscribed = false;

    private static final String CHANNEL_PREFIX = "failover-lab:channel:";
    private static final String CHANNEL_NAME = CHANNEL_PREFIX + "main";

    private final AtomicLong publishedCount = new AtomicLong(0);
    private final AtomicLong receivedCount = new AtomicLong(0);
    private final ConcurrentHashMap<String, Long> pendingMessages = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        this.instanceId = UUID.randomUUID().toString().substring(0, 8);
        this.enabled = workloadTypes.contains("pubsub");

        if (enabled) {
            setupSubscriber();
            log.info("PubSubWorkload enabled: instanceId={}", instanceId);
        } else {
            log.info("PubSubWorkload disabled");
        }
    }

    private void setupSubscriber() {
        boolean isConsumer = "consumer".equals(workloadMode) || "both".equals(workloadMode);

        if (!isConsumer) {
            log.info("Skipping subscriber setup - not in consumer mode");
            return;
        }

        pubSubConnection.addListener(new RedisClusterPubSubListener<String, String>() {
            @Override
            public void message(String channel, String message) {
                handleMessage(channel, message);
            }

            @Override
            public void message(io.lettuce.core.cluster.models.partitions.RedisClusterNode node,
                               String channel, String message) {
                handleMessage(channel, message);
            }

            @Override
            public void subscribed(String channel, long count) {
                log.info("Subscribed to channel: {}, total subscriptions: {}", channel, count);
            }

            @Override
            public void subscribed(io.lettuce.core.cluster.models.partitions.RedisClusterNode node,
                                  String channel, long count) {
                log.info("Subscribed to channel {} on node {}", channel, node.getNodeId());
            }

            @Override
            public void unsubscribed(String channel, long count) {
                log.info("Unsubscribed from channel: {}", channel);
            }

            @Override
            public void unsubscribed(io.lettuce.core.cluster.models.partitions.RedisClusterNode node,
                                    String channel, long count) {
                log.info("Unsubscribed from channel {} on node {}", channel, node.getNodeId());
            }

            @Override
            public void psubscribed(String pattern, long count) {}

            @Override
            public void psubscribed(io.lettuce.core.cluster.models.partitions.RedisClusterNode node,
                                   String pattern, long count) {}

            @Override
            public void punsubscribed(String pattern, long count) {}

            @Override
            public void punsubscribed(io.lettuce.core.cluster.models.partitions.RedisClusterNode node,
                                     String pattern, long count) {}
        });

        // Subscribe to channel
        try {
            pubSubConnection.sync().subscribe(CHANNEL_NAME);
            subscribed = true;
            log.info("Successfully subscribed to {}", CHANNEL_NAME);
        } catch (Exception e) {
            log.error("Failed to subscribe to channel: {}", e.getMessage());
        }
    }

    private void handleMessage(String channel, String message) {
        receivedCount.incrementAndGet();

        try {
            // Extract message ID from the message
            String messageId = extractMessageId(message);
            if (messageId != null) {
                failoverMetrics.recordPubSubReceived(messageId);
                pendingMessages.remove(messageId);
                log.debug("Received message on channel {}: id={}", channel, messageId);
            }
        } catch (Exception e) {
            log.error("Error handling message: {}", e.getMessage());
        }
    }

    private String extractMessageId(String message) {
        // Simple extraction - message format: {"id":"xxx",...}
        int idStart = message.indexOf("\"id\":\"");
        if (idStart >= 0) {
            int valueStart = idStart + 6;
            int valueEnd = message.indexOf("\"", valueStart);
            if (valueEnd > valueStart) {
                return message.substring(valueStart, valueEnd);
            }
        }
        return null;
    }

    @Scheduled(fixedRateString = "#{${workload.ops-per-second} > 0 ? (1000 / ${workload.ops-per-second}) : 1000}")
    public void executeWorkload() {
        if (!enabled) {
            return;
        }

        boolean isProducer = "producer".equals(workloadMode) || "both".equals(workloadMode);

        if (isProducer) {
            publishMessage();
        }
    }

    private void publishMessage() {
        String messageId = UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis();
        long sequence = publishedCount.incrementAndGet();

        String message = String.format(
                "{\"id\":\"%s\",\"seq\":%d,\"ts\":%d,\"src\":\"%s\",\"size\":%d}",
                messageId, sequence, timestamp, instanceId, messageSizeBytes);

        // Pad message if needed
        if (message.length() < messageSizeBytes) {
            StringBuilder sb = new StringBuilder(message.substring(0, message.length() - 1));
            sb.append(",\"pad\":\"");
            while (sb.length() < messageSizeBytes - 2) {
                sb.append('x');
            }
            sb.append("\"}");
            message = sb.toString();
        }

        final String finalMessage = message;

        try {
            pendingMessages.put(messageId, timestamp);
            failoverMetrics.recordPubSubPublished(messageId);

            asyncCommands.publish(CHANNEL_NAME, finalMessage)
                    .whenComplete((receiverCount, throwable) -> {
                        if (throwable != null) {
                            log.error("Failed to publish message {}: {}", messageId, throwable.getMessage());
                            pendingMessages.remove(messageId);
                        } else {
                            log.debug("Published message {}, receivers: {}", messageId, receiverCount);
                        }
                    });

        } catch (Exception e) {
            log.error("Publish error: {}", e.getMessage());
            pendingMessages.remove(messageId);
        }
    }

    @Scheduled(fixedRate = 5000)
    public void checkMessageLoss() {
        if (!enabled) {
            return;
        }

        // Check for messages that haven't been acknowledged in 10 seconds
        failoverMetrics.checkForLostMessages(10000);

        // Also check local pending messages
        long now = System.currentTimeMillis();
        pendingMessages.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > 10000) {
                log.warn("Local pending message {} timed out", entry.getKey());
                return true;
            }
            return false;
        });

        log.debug("PubSub status: published={}, received={}, pending={}",
                publishedCount.get(), receivedCount.get(), pendingMessages.size());
    }

    @PreDestroy
    public void cleanup() {
        if (subscribed) {
            try {
                pubSubConnection.sync().unsubscribe(CHANNEL_NAME);
                log.info("Unsubscribed from channel {}", CHANNEL_NAME);
            } catch (Exception e) {
                log.warn("Error unsubscribing: {}", e.getMessage());
            }
        }
    }

    public long getPublishedCount() {
        return publishedCount.get();
    }

    public long getReceivedCount() {
        return receivedCount.get();
    }
}
