package com.students.lnu.elastic.config;


import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchConfiguration;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;
import org.springframework.data.elasticsearch.support.HttpHeaders;

@Configuration
@EnableConfigurationProperties(value = {ElasticProperties.class})
@EnableElasticsearchRepositories
@RequiredArgsConstructor
public class ElasticClientConfig extends ElasticsearchConfiguration {
  private final ElasticProperties elasticProperties;

  @Override
  public ClientConfiguration clientConfiguration() {
    return ClientConfiguration.builder()
        .connectedTo(elasticProperties.baseUrl())
        .withHeaders(
            () -> {
              HttpHeaders headers = new HttpHeaders();
              headers.add("Authorization", "ApiKey " + elasticProperties.token());
              return headers;
            })
        .build();
  }
}