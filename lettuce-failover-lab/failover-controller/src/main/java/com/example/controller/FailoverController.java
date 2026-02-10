package com.example.controller;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticache.model.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
@Slf4j
@RequiredArgsConstructor
public class FailoverController {

    private final ElastiCacheClient elastiCacheClient;

    @Value("${elasticache.replication-group-id}")
    private String replicationGroupId;

    private final ConcurrentHashMap<String, FailoverEvent> failoverHistory = new ConcurrentHashMap<>();

    @PostMapping("/failover/{shardId}")
    public ResponseEntity<Map<String, Object>> triggerFailover(@PathVariable String shardId) {
        log.info("Triggering failover for shard: {} in replication group: {}", shardId, replicationGroupId);

        Map<String, Object> response = new HashMap<>();
        String nodeGroupId = String.format("%04d", Integer.parseInt(shardId));

        try {
            // Get current primary for the shard
            String currentPrimary = getCurrentPrimary(nodeGroupId);
            response.put("previousPrimary", currentPrimary);

            // Trigger failover
            TestFailoverRequest request = TestFailoverRequest.builder()
                    .replicationGroupId(replicationGroupId)
                    .nodeGroupId(nodeGroupId)
                    .build();

            TestFailoverResponse testFailoverResponse = elastiCacheClient.testFailover(request);

            // Record the failover event
            String eventId = UUID.randomUUID().toString();
            FailoverEvent event = new FailoverEvent();
            event.setEventId(eventId);
            event.setShardId(shardId);
            event.setNodeGroupId(nodeGroupId);
            event.setPreviousPrimary(currentPrimary);
            event.setStartTime(Instant.now());
            event.setStatus("INITIATED");
            failoverHistory.put(eventId, event);

            response.put("status", "INITIATED");
            response.put("eventId", eventId);
            response.put("replicationGroupId", replicationGroupId);
            response.put("nodeGroupId", nodeGroupId);
            response.put("message", "Failover initiated successfully");

            log.info("Failover initiated: eventId={}, nodeGroupId={}", eventId, nodeGroupId);

        } catch (InvalidReplicationGroupStateException e) {
            log.error("Cannot initiate failover - invalid state: {}", e.getMessage());
            response.put("status", "ERROR");
            response.put("error", "Replication group is not in a valid state for failover");
            return ResponseEntity.badRequest().body(response);

        } catch (NodeGroupNotFoundException e) {
            log.error("Node group not found: {}", nodeGroupId);
            response.put("status", "ERROR");
            response.put("error", "Node group " + nodeGroupId + " not found");
            return ResponseEntity.notFound().build();

        } catch (Exception e) {
            log.error("Failed to trigger failover: {}", e.getMessage(), e);
            response.put("status", "ERROR");
            response.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getClusterStatus() {
        Map<String, Object> response = new HashMap<>();

        try {
            DescribeReplicationGroupsRequest request = DescribeReplicationGroupsRequest.builder()
                    .replicationGroupId(replicationGroupId)
                    .build();

            DescribeReplicationGroupsResponse describeResponse = elastiCacheClient.describeReplicationGroups(request);

            if (!describeResponse.replicationGroups().isEmpty()) {
                ReplicationGroup rg = describeResponse.replicationGroups().get(0);

                response.put("replicationGroupId", rg.replicationGroupId());
                response.put("status", rg.statusAsString());
                response.put("clusterEnabled", rg.clusterEnabled());
                response.put("configurationEndpoint", rg.configurationEndpoint() != null ?
                        rg.configurationEndpoint().address() + ":" + rg.configurationEndpoint().port() : null);

                List<Map<String, Object>> nodeGroups = new ArrayList<>();
                for (NodeGroup ng : rg.nodeGroups()) {
                    Map<String, Object> ngInfo = new HashMap<>();
                    ngInfo.put("nodeGroupId", ng.nodeGroupId());
                    ngInfo.put("status", ng.status());
                    ngInfo.put("primaryEndpoint", ng.primaryEndpoint() != null ?
                            ng.primaryEndpoint().address() : null);
                    ngInfo.put("slots", ng.slots());

                    List<Map<String, String>> members = new ArrayList<>();
                    for (NodeGroupMember member : ng.nodeGroupMembers()) {
                        Map<String, String> memberInfo = new HashMap<>();
                        memberInfo.put("cacheClusterId", member.cacheClusterId());
                        memberInfo.put("role", member.currentRole());
                        memberInfo.put("preferredAZ", member.preferredAvailabilityZone());
                        members.add(memberInfo);
                    }
                    ngInfo.put("members", members);
                    nodeGroups.add(ngInfo);
                }
                response.put("nodeGroups", nodeGroups);
            }

        } catch (Exception e) {
            log.error("Failed to get cluster status: {}", e.getMessage());
            response.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/failover/{eventId}")
    public ResponseEntity<Map<String, Object>> getFailoverStatus(@PathVariable String eventId) {
        FailoverEvent event = failoverHistory.get(eventId);

        if (event == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("eventId", event.getEventId());
        response.put("shardId", event.getShardId());
        response.put("nodeGroupId", event.getNodeGroupId());
        response.put("previousPrimary", event.getPreviousPrimary());
        response.put("newPrimary", event.getNewPrimary());
        response.put("startTime", event.getStartTime().toString());
        response.put("status", event.getStatus());

        if (event.getEndTime() != null) {
            response.put("endTime", event.getEndTime().toString());
            response.put("durationMs", event.getEndTime().toEpochMilli() - event.getStartTime().toEpochMilli());
        }

        // Check current state
        try {
            String currentPrimary = getCurrentPrimary(event.getNodeGroupId());
            if (currentPrimary != null && !currentPrimary.equals(event.getPreviousPrimary())) {
                event.setNewPrimary(currentPrimary);
                event.setStatus("COMPLETED");
                event.setEndTime(Instant.now());
                response.put("newPrimary", currentPrimary);
                response.put("status", "COMPLETED");
                response.put("endTime", event.getEndTime().toString());
                response.put("durationMs", event.getEndTime().toEpochMilli() - event.getStartTime().toEpochMilli());
            }
        } catch (Exception e) {
            log.warn("Could not check current primary: {}", e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        Map<String, Object> response = new HashMap<>();

        response.put("totalFailovers", failoverHistory.size());

        long completedCount = failoverHistory.values().stream()
                .filter(e -> "COMPLETED".equals(e.getStatus()))
                .count();
        response.put("completedFailovers", completedCount);

        // Calculate average failover duration
        OptionalDouble avgDuration = failoverHistory.values().stream()
                .filter(e -> e.getEndTime() != null)
                .mapToLong(e -> e.getEndTime().toEpochMilli() - e.getStartTime().toEpochMilli())
                .average();
        response.put("averageFailoverDurationMs", avgDuration.orElse(0));

        // Recent failovers
        List<Map<String, Object>> recentFailovers = new ArrayList<>();
        failoverHistory.values().stream()
                .sorted((a, b) -> b.getStartTime().compareTo(a.getStartTime()))
                .limit(10)
                .forEach(event -> {
                    Map<String, Object> eventSummary = new HashMap<>();
                    eventSummary.put("eventId", event.getEventId());
                    eventSummary.put("shardId", event.getShardId());
                    eventSummary.put("status", event.getStatus());
                    eventSummary.put("startTime", event.getStartTime().toString());
                    if (event.getEndTime() != null) {
                        eventSummary.put("durationMs",
                                event.getEndTime().toEpochMilli() - event.getStartTime().toEpochMilli());
                    }
                    recentFailovers.add(eventSummary);
                });
        response.put("recentFailovers", recentFailovers);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("timestamp", Instant.now().toString());
        response.put("replicationGroupId", replicationGroupId);
        return ResponseEntity.ok(response);
    }

    private String getCurrentPrimary(String nodeGroupId) {
        try {
            DescribeReplicationGroupsRequest request = DescribeReplicationGroupsRequest.builder()
                    .replicationGroupId(replicationGroupId)
                    .build();

            DescribeReplicationGroupsResponse response = elastiCacheClient.describeReplicationGroups(request);

            for (ReplicationGroup rg : response.replicationGroups()) {
                for (NodeGroup ng : rg.nodeGroups()) {
                    if (ng.nodeGroupId().equals(nodeGroupId)) {
                        for (NodeGroupMember member : ng.nodeGroupMembers()) {
                            if ("primary".equalsIgnoreCase(member.currentRole())) {
                                return member.cacheClusterId();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to get current primary: {}", e.getMessage());
        }
        return null;
    }

    @Data
    private static class FailoverEvent {
        private String eventId;
        private String shardId;
        private String nodeGroupId;
        private String previousPrimary;
        private String newPrimary;
        private Instant startTime;
        private Instant endTime;
        private String status;
    }
}
