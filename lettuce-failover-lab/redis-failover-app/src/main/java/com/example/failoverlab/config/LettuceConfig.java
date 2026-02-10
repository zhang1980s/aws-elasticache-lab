package com.example.failoverlab.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.SslOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import io.lettuce.core.cluster.pubsub.StatefulRedisClusterPubSubConnection;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
@Slf4j
public class LettuceConfig {

    @Value("${redis.cluster.endpoint}")
    private String clusterEndpoint;

    @Value("${redis.cluster.ssl-enabled}")
    private boolean sslEnabled;

    @Value("${lettuce.profile}")
    private String lettuceProfile;

    private RedisClusterClient redisClusterClient;
    private ClientResources clientResources;

    @Bean
    public ClientResources clientResources() {
        this.clientResources = DefaultClientResources.builder()
                .ioThreadPoolSize(4)
                .computationThreadPoolSize(4)
                .build();
        return clientResources;
    }

    @Bean
    public RedisClusterClient redisClusterClient(ClientResources clientResources) {
        String[] parts = clusterEndpoint.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 6379;

        RedisURI redisURI = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withSsl(sslEnabled)
                .build();

        this.redisClusterClient = RedisClusterClient.create(clientResources, redisURI);

        ClusterClientOptions clientOptions = buildClientOptions();
        redisClusterClient.setOptions(clientOptions);

        log.info("Created RedisClusterClient with profile: {} connecting to: {}", lettuceProfile, clusterEndpoint);
        return redisClusterClient;
    }

    private ClusterClientOptions buildClientOptions() {
        ClusterTopologyRefreshOptions topologyRefreshOptions = buildTopologyRefreshOptions();

        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofSeconds(10))
                .keepAlive(true)
                .build();

        ClusterClientOptions.Builder builder = ClusterClientOptions.builder()
                .topologyRefreshOptions(topologyRefreshOptions)
                .socketOptions(socketOptions)
                .autoReconnect(true)
                .pingBeforeActivateConnection(true)
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofSeconds(5)));

        if (sslEnabled) {
            builder.sslOptions(SslOptions.builder().build());
        }

        return builder.build();
    }

    private ClusterTopologyRefreshOptions buildTopologyRefreshOptions() {
        log.info("Building topology refresh options for profile: {}", lettuceProfile);

        return switch (lettuceProfile.toLowerCase()) {
            case "aggressive" -> buildAggressiveTopologyOptions();
            case "conservative" -> buildConservativeTopologyOptions();
            case "aws-recommended" -> buildAwsRecommendedTopologyOptions();
            default -> {
                log.warn("Unknown lettuce profile: {}, using aws-recommended", lettuceProfile);
                yield buildAwsRecommendedTopologyOptions();
            }
        };
    }

    /**
     * Aggressive profile: Fast detection, frequent refreshes
     * - 10s periodic refresh
     * - All adaptive triggers enabled
     * - Quick timeout for detecting issues
     */
    private ClusterTopologyRefreshOptions buildAggressiveTopologyOptions() {
        log.info("Using AGGRESSIVE topology refresh settings");
        return ClusterTopologyRefreshOptions.builder()
                .enablePeriodicRefresh(Duration.ofSeconds(10))
                .enableAllAdaptiveRefreshTriggers()
                .adaptiveRefreshTriggersTimeout(Duration.ofSeconds(5))
                .refreshTriggersReconnectAttempts(3)
                .dynamicRefreshSources(true)
                .closeStaleConnections(true)
                .build();
    }

    /**
     * Conservative profile: Minimal refreshes, fewer triggers
     * - 60s periodic refresh
     * - Only MOVED redirect triggers refresh
     * - Longer timeouts
     */
    private ClusterTopologyRefreshOptions buildConservativeTopologyOptions() {
        log.info("Using CONSERVATIVE topology refresh settings");
        return ClusterTopologyRefreshOptions.builder()
                .enablePeriodicRefresh(Duration.ofSeconds(60))
                .enableAdaptiveRefreshTrigger(
                        ClusterTopologyRefreshOptions.RefreshTrigger.MOVED_REDIRECT)
                .adaptiveRefreshTriggersTimeout(Duration.ofSeconds(30))
                .refreshTriggersReconnectAttempts(5)
                .dynamicRefreshSources(false)
                .closeStaleConnections(false)
                .build();
    }

    /**
     * AWS-Recommended profile: Balanced settings for ElastiCache
     * - 30s periodic refresh (aligns with typical failover time)
     * - All adaptive triggers for quick detection
     * - Dynamic refresh sources enabled
     */
    private ClusterTopologyRefreshOptions buildAwsRecommendedTopologyOptions() {
        log.info("Using AWS-RECOMMENDED topology refresh settings");
        return ClusterTopologyRefreshOptions.builder()
                .enablePeriodicRefresh(Duration.ofSeconds(30))
                .enableAllAdaptiveRefreshTriggers()
                .adaptiveRefreshTriggersTimeout(Duration.ofSeconds(15))
                .refreshTriggersReconnectAttempts(3)
                .dynamicRefreshSources(true)
                .closeStaleConnections(true)
                .build();
    }

    @Bean
    public StatefulRedisClusterConnection<String, String> clusterConnection(RedisClusterClient client) {
        StatefulRedisClusterConnection<String, String> connection = client.connect();
        connection.setAutoFlushCommands(true);
        connection.setReadFrom(io.lettuce.core.ReadFrom.UPSTREAM_PREFERRED);
        log.info("Created StatefulRedisClusterConnection");
        return connection;
    }

    @Bean
    public RedisAdvancedClusterCommands<String, String> syncCommands(
            StatefulRedisClusterConnection<String, String> connection) {
        return connection.sync();
    }

    @Bean
    public RedisAdvancedClusterAsyncCommands<String, String> asyncCommands(
            StatefulRedisClusterConnection<String, String> connection) {
        return connection.async();
    }

    @Bean
    public StatefulRedisClusterPubSubConnection<String, String> pubSubConnection(RedisClusterClient client) {
        StatefulRedisClusterPubSubConnection<String, String> connection = client.connectPubSub();
        log.info("Created StatefulRedisClusterPubSubConnection");
        return connection;
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Redis connections...");
        if (redisClusterClient != null) {
            redisClusterClient.shutdown(5, 15, TimeUnit.SECONDS);
        }
        if (clientResources != null) {
            clientResources.shutdown(5, 15, TimeUnit.SECONDS);
        }
    }
}
