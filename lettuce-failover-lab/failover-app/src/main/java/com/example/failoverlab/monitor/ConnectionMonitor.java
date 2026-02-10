package com.example.failoverlab.monitor;

import com.example.failoverlab.metrics.FailoverMetrics;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.event.ClusterTopologyChangedEvent;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import io.lettuce.core.event.connection.ConnectionActivatedEvent;
import io.lettuce.core.event.connection.ConnectionDeactivatedEvent;
import io.lettuce.core.event.connection.ReconnectFailedEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class ConnectionMonitor {

    private final RedisClusterClient redisClusterClient;
    private final StatefulRedisClusterConnection<String, String> clusterConnection;
    private final FailoverMetrics failoverMetrics;

    private Map<String, String> lastKnownNodeIPs = new HashMap<>();

    @PostConstruct
    public void init() {
        setupEventListeners();
        updateNodeIPMapping();
        log.info("ConnectionMonitor initialized");
    }

    private void setupEventListeners() {
        redisClusterClient.getResources().eventBus().get()
                .subscribe(event -> {
                    if (event instanceof ConnectionActivatedEvent) {
                        handleConnectionActivated((ConnectionActivatedEvent) event);
                    } else if (event instanceof ConnectionDeactivatedEvent) {
                        handleConnectionDeactivated((ConnectionDeactivatedEvent) event);
                    } else if (event instanceof ReconnectFailedEvent) {
                        handleReconnectFailed((ReconnectFailedEvent) event);
                    } else if (event instanceof ClusterTopologyChangedEvent) {
                        handleTopologyChanged((ClusterTopologyChangedEvent) event);
                    }
                });
        log.info("Event listeners registered");
    }

    private void handleConnectionActivated(ConnectionActivatedEvent event) {
        log.info("Connection activated: {}", event);
        failoverMetrics.recordReconnect();
    }

    private void handleConnectionDeactivated(ConnectionDeactivatedEvent event) {
        log.warn("Connection deactivated: {}", event);
        failoverMetrics.recordDisconnect();
    }

    private void handleReconnectFailed(ReconnectFailedEvent event) {
        log.error("Reconnect failed: attempt {} - {}", event.getAttempt(), event.getCause().getMessage());
    }

    private void handleTopologyChanged(ClusterTopologyChangedEvent event) {
        log.info("Cluster topology changed!");
        failoverMetrics.recordTopologyRefresh();

        // Log details about topology change
        List<RedisClusterNode> before = event.before().getPartitions();
        List<RedisClusterNode> after = event.after().getPartitions();

        Map<String, RedisClusterNode> beforeMap = before.stream()
                .collect(Collectors.toMap(n -> n.getNodeId(), n -> n));

        for (RedisClusterNode node : after) {
            RedisClusterNode oldNode = beforeMap.get(node.getNodeId());
            if (oldNode != null && oldNode.is(RedisClusterNode.NodeFlag.MASTER) != node.is(RedisClusterNode.NodeFlag.MASTER)) {
                log.info("Node {} changed role: {} -> {}",
                        node.getNodeId(),
                        oldNode.is(RedisClusterNode.NodeFlag.MASTER) ? "MASTER" : "REPLICA",
                        node.is(RedisClusterNode.NodeFlag.MASTER) ? "MASTER" : "REPLICA");
            }
        }

        updateNodeIPMapping();
    }

    @Scheduled(fixedRate = 10000)
    public void checkConnectionHealth() {
        try {
            String pong = clusterConnection.sync().ping();
            if ("PONG".equals(pong)) {
                log.debug("Health check passed: PONG received");
            }
        } catch (Exception e) {
            log.error("Health check failed: {}", e.getMessage());
            failoverMetrics.recordDisconnect();
        }
    }

    @Scheduled(fixedRate = 30000)
    public void logClusterStatus() {
        try {
            var partitions = redisClusterClient.getPartitions();
            log.info("=== Cluster Status ===");

            partitions.forEach(node -> {
                String role = node.is(RedisClusterNode.NodeFlag.MASTER) ? "MASTER" : "REPLICA";
                log.info("Node {} [{}]: {} - Slots: {}",
                        node.getNodeId().substring(0, 8),
                        role,
                        node.getUri(),
                        node.getSlots().size());
            });

            log.info("Total nodes: {}", partitions.size());
        } catch (Exception e) {
            log.error("Failed to get cluster status: {}", e.getMessage());
        }
    }

    @Scheduled(fixedRate = 60000)
    public void checkDNSChanges() {
        try {
            var partitions = redisClusterClient.getPartitions();

            for (RedisClusterNode node : partitions) {
                String host = node.getUri().getHost();
                String nodeId = node.getNodeId();

                try {
                    InetAddress address = InetAddress.getByName(host);
                    String currentIP = address.getHostAddress();
                    String lastIP = lastKnownNodeIPs.get(nodeId);

                    if (lastIP != null && !lastIP.equals(currentIP)) {
                        log.warn("DNS change detected for node {}: {} -> {}",
                                nodeId.substring(0, 8), lastIP, currentIP);
                    }

                    lastKnownNodeIPs.put(nodeId, currentIP);
                } catch (UnknownHostException e) {
                    log.error("DNS resolution failed for host: {}", host);
                }
            }
        } catch (Exception e) {
            log.error("Failed to check DNS changes: {}", e.getMessage());
        }
    }

    private void updateNodeIPMapping() {
        try {
            var partitions = redisClusterClient.getPartitions();
            lastKnownNodeIPs.clear();

            for (RedisClusterNode node : partitions) {
                String host = node.getUri().getHost();
                try {
                    InetAddress address = InetAddress.getByName(host);
                    lastKnownNodeIPs.put(node.getNodeId(), address.getHostAddress());
                } catch (UnknownHostException e) {
                    log.warn("Could not resolve IP for host: {}", host);
                }
            }

            log.info("Updated node IP mapping: {} nodes", lastKnownNodeIPs.size());
        } catch (Exception e) {
            log.error("Failed to update node IP mapping: {}", e.getMessage());
        }
    }

    public Map<String, Object> getConnectionStatus() {
        Map<String, Object> status = new HashMap<>();

        try {
            boolean connected = clusterConnection.isOpen();
            status.put("connected", connected);
            status.put("inFailover", failoverMetrics.isInFailover());

            var partitions = redisClusterClient.getPartitions();
            status.put("totalNodes", partitions.size());
            status.put("masters", partitions.stream()
                    .filter(n -> n.is(RedisClusterNode.NodeFlag.MASTER))
                    .count());
            status.put("replicas", partitions.stream()
                    .filter(n -> n.is(RedisClusterNode.NodeFlag.REPLICA))
                    .count());

        } catch (Exception e) {
            status.put("error", e.getMessage());
            status.put("connected", false);
        }

        return status;
    }
}
