package com.students.lnu.elastic;

import com.bedatadriven.jackson.datatype.jts.JtsModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.geojson.FeatureCollection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

@Slf4j
@Service
@RequiredArgsConstructor
public class ElasticGeoService {
    private static final String INDEX_NAME = "geo_features";
    private final ObjectMapper objectMapper;
    private final ElasticsearchOperations elasticsearchOperations;

    @Value("${geo.data.file}")
    private String geoDataFile;

    //@Async
    @SneakyThrows
    public void indexData() {
        objectMapper.registerModule(new JtsModule());
        FeatureCollection features = objectMapper.readValue(Files.readAllBytes(Paths.get(geoDataFile)), FeatureCollection.class);

        elasticsearchOperations.save(features, IndexCoordinates.of(INDEX_NAME));

        log.info("Дані успішно проіндексовані!");
    }
}