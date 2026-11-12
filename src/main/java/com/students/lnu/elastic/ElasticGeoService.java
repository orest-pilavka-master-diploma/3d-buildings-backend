package com.students.lnu.elastic;

import com.bedatadriven.jackson.datatype.jts.JtsModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.students.lnu.tiles.TileService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.geojson.Feature;
import org.geojson.FeatureCollection;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Indexes the building features of the LATEST compiler version into Elasticsearch.
 *
 * <p>The compiler no longer writes one combined GeoJSON file; it writes a
 * geohash-tiled tree under {@code geo.tiles.root} with one {@code v<timestamp>}
 * directory per build, each holding many {@code <geohash>.geojson} leaf tiles.
 * Indexing therefore resolves the newest version via {@link TileService}, walks
 * every leaf tile, and saves each tile's features into the {@code geo_features}
 * index (the same index {@code ElasticSearchService} queries by
 * {@code properties.address}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElasticGeoService {
    private static final String INDEX_NAME = "geo_features";
    /** Tiles are indexed in batches so one huge bulk save does not exhaust memory. */
    private static final int BATCH_SIZE = 2000;

    private final ObjectMapper objectMapper;
    private final ElasticsearchOperations elasticsearchOperations;
    private final TileService tileService;

    @SneakyThrows
    public void indexData() {
        objectMapper.registerModule(new JtsModule());

        // Index the newest compiler version (same resolution the /tiles endpoints use).
        Optional<Path> versionDir = tileService.resolveVersionDir(null);
        if (versionDir.isEmpty()) {
            log.warn("No version directory found under the tiles root; nothing to index.");
            return;
        }

        Path version = versionDir.get();
        log.info("Indexing building features from version '{}'.", version.getFileName());

        List<Feature> batch = new ArrayList<>(BATCH_SIZE);
        int indexed = 0;
        int tiles = 0;

        try (Stream<Path> walk = Files.walk(version)) {
            for (Path leaf : (Iterable<Path>) walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".geojson"))::iterator) {
                tiles += 1;
                List<Feature> features = readTileFeatures(leaf);
                for (Feature feature : features) {
                    batch.add(feature);
                    if (batch.size() >= BATCH_SIZE) {
                        indexed += saveBatch(batch);
                        batch.clear();
                    }
                }
            }
        }

        if (!batch.isEmpty()) {
            indexed += saveBatch(batch);
            batch.clear();
        }

        log.info("Indexed {} features from {} tiles of version '{}'.", indexed, tiles, version.getFileName());
    }

    /** Parses one leaf tile into its features, tolerating an unreadable file. */
    private List<Feature> readTileFeatures(Path leaf) {
        try {
            FeatureCollection collection =
                    objectMapper.readValue(Files.readAllBytes(leaf), FeatureCollection.class);
            List<Feature> features = collection.getFeatures();
            return features != null ? features : List.of();
        } catch (Exception e) {
            log.warn("Skipping unreadable tile {}: {}", leaf, e.getMessage());
            return List.of();
        }
    }

    /** Saves one batch of features into the index and returns how many were saved. */
    private int saveBatch(List<Feature> batch) {
        FeatureCollection collection = new FeatureCollection();
        collection.setFeatures(batch);
        elasticsearchOperations.save(collection, IndexCoordinates.of(INDEX_NAME));
        return batch.size();
    }
}