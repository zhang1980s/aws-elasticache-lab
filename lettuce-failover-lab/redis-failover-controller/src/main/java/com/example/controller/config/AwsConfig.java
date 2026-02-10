package com.example.controller.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;

@Configuration
public class AwsConfig {

    @Bean
    public ElastiCacheClient elastiCacheClient() {
        return ElastiCacheClient.builder()
                .region(Region.US_EAST_1)
                .build();
    }

    @Bean
    public CloudWatchClient cloudWatchClient() {
        return CloudWatchClient.builder()
                .region(Region.US_EAST_1)
                .build();
    }
}
