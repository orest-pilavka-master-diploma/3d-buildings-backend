package com.students.lnu.elastic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "elastic")
public record ElasticProperties(String baseUrl, String token) {}