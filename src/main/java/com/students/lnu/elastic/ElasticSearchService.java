package com.students.lnu.elastic;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.students.lnu.geotools.GeoTools;
import lombok.RequiredArgsConstructor;
import org.geojson.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ElasticSearchService {

    private final ElasticsearchOperations elasticsearchOperations;

    private static final String INDEX_NAME = "geo_features";
    private static final String ADDRESS_FIELD = "properties.address";

    /**
     * Splits user input into words on anything but letters and digits (spaces, commas, apostrophes, ...).
     * Splitting on apostrophes lets "в'ячеслава" find "Вʼячеслава" even when the apostrophe characters differ.
     */
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    /**
     * Searches buildings by address. Every word of the input must appear in the address,
     * either as a whole word, as the start of a word, anywhere inside a word, or as a whole
     * word with typos. Results are ranked in that order.
     *
     * <p>Examples: "панч 18" finds "Львів, вулиця Петра Панча, 18а";
     * "чорновала" (typo) finds "Львів, проспект Вʼячеслава Чорновола, 93".
     */
    public List<org.geojson.Feature> search(String searchText) {
        List<String> words = toWords(searchText);
        if (words.isEmpty()) {
            return List.of();
        }

        List<Query> wordQueries = words.stream().map(ElasticSearchService::wordQuery).toList();

        var query = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> b.must(wordQueries)))
                .withPageable(PageRequest.of(0, 5))
                .build();

        var searchResponse = elasticsearchOperations.search(query, org.geojson.Feature.class, IndexCoordinates.of(INDEX_NAME));

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

    /**
     * Lowercases and splits the input. Lowercasing is done here because the index stores
     * lowercased words and the "case_insensitive" option of Elasticsearch prefix/wildcard
     * queries does not fold Cyrillic letters.
     */
    static List<String> toWords(String searchText) {
        if (searchText == null) {
            return List.of();
        }
        return Arrays.stream(NON_WORD.split(searchText.toLowerCase(Locale.ROOT)))
                .filter(word -> !word.isEmpty())
                .toList();
    }

    /**
     * Allowed typos per word, by word length: under 4 characters none, 4 to 7 characters one,
     * longer words two. A swap of two neighbouring letters counts as one typo.
     * Short words get no typos so that short house numbers stay exact ("128" does not find "126").
     */
    private static final String TYPO_FUZZINESS = "AUTO:4,7";

    /** Matches one word as a whole word, a word start, a part of a word, or a whole word with typos. */
    private static Query wordQuery(String word) {
        // Words contain only letters and digits, so they hold no wildcard characters (* ?) to escape.
        return Query.of(q -> q.bool(b -> b
                .should(s -> s.match(m -> m.field(ADDRESS_FIELD).query(word).boost(3f)))
                .should(s -> s.prefix(p -> p.field(ADDRESS_FIELD).value(word).boost(2f)))
                .should(s -> s.wildcard(w -> w.field(ADDRESS_FIELD).value("*" + word + "*").boost(1f)))
                .should(s -> s.match(m -> m.field(ADDRESS_FIELD).query(word).fuzziness(TYPO_FUZZINESS).boost(0.5f)))
                .minimumShouldMatch("1")
        ));
    }
}