package com.students.lnu.elastic.config;


import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchConfiguration;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;
import org.springframework.data.elasticsearch.support.HttpHeaders;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(value = {ElasticProperties.class})
@EnableElasticsearchRepositories
@RequiredArgsConstructor
public class ElasticClientConfig extends ElasticsearchConfiguration {
  private final ElasticProperties elasticProperties;

  /**
   * TCP connect timeout (ms). Separate from the socket timeout: this bounds how
   * long we wait to establish the connection, not how long a request may run.
   */
  @Value("${elastic.connect-timeout-ms:10000}")
  private long connectTimeoutMs;

  /**
   * Socket (read) timeout (ms). The default Elasticsearch client socket timeout
   * is only 5s, which aborts long bulk-index requests with
   * "5,000 milliseconds timeout on connection ... [ACTIVE]". Bulk indexing of
   * many geohash tiles takes longer, so this is raised and made configurable.
   */
  @Value("${elastic.socket-timeout-ms:120000}")
  private long socketTimeoutMs;

  @Override
  public ClientConfiguration clientConfiguration() {
    return ClientConfiguration.builder()
        .connectedTo(elasticProperties.baseUrl())
        .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
        .withSocketTimeout(Duration.ofMillis(socketTimeoutMs))
        .withHeaders(
            () -> {
              HttpHeaders headers = new HttpHeaders();
              headers.add("Authorization", "ApiKey " + elasticProperties.token());
              return headers;
            })
        .build();
  }
}