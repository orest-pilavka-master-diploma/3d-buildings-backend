package com.students.lnu.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.students.lnu.elastic.ElasticGeoService;
import com.students.lnu.elastic.ElasticSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class Controller {

  private final ElasticSearchService elasticSearchService;
  private final ElasticGeoService elasticGeoService;
  private final ObjectMapper objectMapper;

  @Value("${geo.data.file}")
  private String geoDataFile;

  @GetMapping("/fetch-data")
  public ResponseEntity<JsonNode> fetchData() throws IOException {
    Path path = Paths.get(geoDataFile);
    String jsonContent = new String(Files.readAllBytes(path));

    JsonNode jsonObject = objectMapper.readValue(jsonContent, JsonNode.class);
    return ResponseEntity.ok(jsonObject);
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
