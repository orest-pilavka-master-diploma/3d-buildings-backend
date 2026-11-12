package com.students.lnu.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.students.lnu.elastic.ElasticGeoService;
import com.students.lnu.elastic.ElasticSearchService;
import com.students.lnu.tiles.TileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Slf4j
@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class Controller {

  private final ElasticSearchService elasticSearchService;
  private final ElasticGeoService elasticGeoService;
  private final ObjectMapper objectMapper;
  private final TileService tileService;

  @Value("${geo.data.file}")
  private String geoDataFile;

  @Value("${geo.tiles.stored-level}")
  private int storedLevel;

  @Value("${geo.tiles.max-geohashes}")
  private int maxGeohashes;

  @GetMapping("/fetch-data")
  public ResponseEntity<JsonNode> fetchData() throws IOException {
    Path path = Paths.get(geoDataFile);
    String jsonContent = new String(Files.readAllBytes(path));

    JsonNode jsonObject = objectMapper.readValue(jsonContent, JsonNode.class);
    return ResponseEntity.ok(jsonObject);
  }

  /**
   * Returns the buildings in the requested geohash cells as one GeoJSON
   * FeatureCollection. Geohashes may be at the stored level (one leaf each) or
   * coarser (prefix-merged). Caps the request to geo.tiles.max-geohashes.
   */
  @GetMapping("/tiles")
  public ResponseEntity<JsonNode> tiles(
      @RequestParam("geohashes") String geohashes,
      @RequestParam(value = "version", required = false) String version) {
    log.info("Requesting /tiles with geohashes={} version={}", geohashes, version);
    List<String> requested = Arrays.stream(geohashes.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();

    if (requested.isEmpty()) {
      return ResponseEntity.badRequest().build();
    }
    if (requested.size() > maxGeohashes) {
      log.warn("Rejected /tiles request with {} geohashes (max {})", requested.size(), maxGeohashes);
      return ResponseEntity.unprocessableEntity().build();
    }

    Optional<Path> versionDir = tileService.resolveVersionDir(version);
    if (versionDir.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    ObjectNode collection = tileService.collectFeatures(requested, versionDir.get());
    return ResponseEntity.ok(collection);
  }

  /** Reports the version and stored geohash level the front should request at. */
  @GetMapping("/tiles/info")
  public ResponseEntity<JsonNode> tilesInfo(
      @RequestParam(value = "version", required = false) String version) {
    Optional<Path> versionDir = tileService.resolveVersionDir(version);
    ObjectNode info = objectMapper.createObjectNode();
    info.put("storedLevel", storedLevel);
    info.put("maxGeohashes", maxGeohashes);
    versionDir.ifPresent(dir -> {
      info.put("version", dir.getFileName().toString());
      tileService.computeBounds(dir).ifPresent(b -> {
        var bbox = info.putArray("bbox");
        bbox.add(b.minLng());
        bbox.add(b.minLat());
        bbox.add(b.maxLng());
        bbox.add(b.maxLat());
        var center = info.putObject("center");
        center.put("lng", (b.minLng() + b.maxLng()) / 2);
        center.put("lat", (b.minLat() + b.maxLat()) / 2);
      });
    });
    return ResponseEntity.ok(info);
  }

  @GetMapping("/index")
  public ResponseEntity<String> indexData() {
    elasticGeoService.indexData();
    return ResponseEntity.ok("Дані почали індексуватись!");
  }

  @GetMapping("/search")
  public ResponseEntity<List<org.geojson.Feature>> search(@RequestParam String q) {
    return ResponseEntity.ok(elasticSearchService.search(q));
  }

}
