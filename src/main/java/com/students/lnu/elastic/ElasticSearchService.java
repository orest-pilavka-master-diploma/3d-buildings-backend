package com.students.lnu.elastic;

import com.students.lnu.geotools.GeoTools;
import lombok.RequiredArgsConstructor;
import org.geojson.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ElasticSearchService {

    private final ElasticsearchOperations elasticsearchOperations;

    private static final String INDEX_NAME = "geo_features";

    public List<Feature> search(String searchText) {
        var query = NativeQuery.builder()
                .withQuery(q -> q.match(m -> m
                        .field("properties.address")
                        .query(searchText)
                ))
                .withPageable(PageRequest.of(0, 5))
                .build();

        var searchResponse = elasticsearchOperations.search(query, Feature.class, IndexCoordinates.of(INDEX_NAME));

        List<Feature> resFeature = searchResponse.getSearchHits().stream()
                .map(hit -> {
                    MultiPolygon multiPolygon = (MultiPolygon) hit.getContent().getGeometry();

                    var coord = GeoTools.convertToMultiPolygon(multiPolygon).getCentroid();

                    var feature = hit.getContent();
                    feature.setGeometry(new Point(coord.getX(), coord.getY()));

                    return feature;
                })
                .collect(Collectors.toList());

        return resFeature;
    }
}