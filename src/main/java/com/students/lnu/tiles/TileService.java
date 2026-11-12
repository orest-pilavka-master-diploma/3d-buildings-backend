package com.students.lnu.tiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Serves building features from the geohash-tiled compiler output.
 *
 * <p>The compiler writes, under {@code tilesRoot}, one or more version
 * directories named {@code v<timestamp>}. Inside a version directory each
 * occupied geohash cell is a leaf file nested one directory per geohash
 * character, e.g. the level-6 geohash {@code u8e2p1} lives at
 * {@code u/u8/u8e/u8e2/u8e2p/u8e2p1.geojson}.
 *
 * <p>A requested geohash is resolved by length relative to {@code storedLevel}:
 * equal length reads the single leaf; shorter (coarser) reads and merges every
 * stored leaf under that prefix subtree; longer (finer) is clamped to the
 * stored level. Missing cells simply contribute nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TileService {

  /** Base-32 geohash alphabet (no a, i, l, o). Used for path-safety validation. */
  private static final String GEOHASH_ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz";

  private final ObjectMapper objectMapper;

  @Value("${geo.tiles.root}")
  private String tilesRoot;

  @Value("${geo.tiles.stored-level}")
  private int storedLevel;

  /**
   * Resolves the version directory to read. When {@code version} is given it is
   * validated and used; otherwise the newest {@code v*} directory under the
   * tiles root is chosen (lexicographically greatest name, which matches the
   * ascending timestamp format).
   */
  public Optional<Path> resolveVersionDir(String version) {
    Path root = Paths.get(tilesRoot);
    if (!Files.isDirectory(root)) {
      log.warn("Tiles root does not exist or is not a directory: {}", root.toAbsolutePath());
      return Optional.empty();
    }
    if (version != null && !version.isBlank()) {
      // Guard against path traversal: a version is a single directory name.
      if (version.contains("/") || version.contains("\\") || version.contains("..")) {
        return Optional.empty();
      }
      Path dir = root.resolve(version);
      return Files.isDirectory(dir) ? Optional.of(dir) : Optional.empty();
    }
    try (Stream<Path> entries = Files.list(root)) {
      return entries
          .filter(Files::isDirectory)
          .filter(p -> p.getFileName().toString().startsWith("v"))
          .max(Comparator.comparing(p -> p.getFileName().toString()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The geographic extent of a version's data: [minLng, minLat, maxLng, maxLat]. */
  public record Bounds(double minLng, double minLat, double maxLng, double maxLat) {}

  /**
   * Computes the data extent of a version by decoding every leaf tile's geohash
   * (from its filename) into a cell box and unioning them. Cheap: it reads only
   * directory entries, never the GeoJSON contents. Returns empty when there are
   * no leaves.
   */
  public Optional<Bounds> computeBounds(Path versionDir) {
    double minLng = Double.POSITIVE_INFINITY;
    double minLat = Double.POSITIVE_INFINITY;
    double maxLng = Double.NEGATIVE_INFINITY;
    double maxLat = Double.NEGATIVE_INFINITY;
    boolean any = false;

    try (Stream<Path> walk = Files.walk(versionDir)) {
      for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)
          .filter(f -> f.getFileName().toString().endsWith(".geojson"))::iterator) {
        String name = p.getFileName().toString();
        String geohash = name.substring(0, name.length() - ".geojson".length());
        if (!isValidGeohash(geohash)) {
          continue;
        }
        double[] box = decodeGeohashBox(geohash); // [minLng, minLat, maxLng, maxLat]
        minLng = Math.min(minLng, box[0]);
        minLat = Math.min(minLat, box[1]);
        maxLng = Math.max(maxLng, box[2]);
        maxLat = Math.max(maxLat, box[3]);
        any = true;
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    return any ? Optional.of(new Bounds(minLng, minLat, maxLng, maxLat)) : Optional.empty();
  }

  /** Decodes a geohash to its cell box [minLng, minLat, maxLng, maxLat]. */
  private double[] decodeGeohashBox(String geohash) {
    double latMin = -90, latMax = 90, lngMin = -180, lngMax = 180;
    boolean even = true;
    for (int i = 0; i < geohash.length(); i++) {
      int cd = GEOHASH_ALPHABET.indexOf(Character.toLowerCase(geohash.charAt(i)));
      for (int mask = 16; mask > 0; mask >>= 1) {
        if (even) {
          double mid = (lngMin + lngMax) / 2;
          if ((cd & mask) != 0) lngMin = mid; else lngMax = mid;
        } else {
          double mid = (latMin + latMax) / 2;
          if ((cd & mask) != 0) latMin = mid; else latMax = mid;
        }
        even = !even;
      }
    }
    return new double[] {lngMin, latMin, lngMax, latMax};
  }

  /** True when {@code geohash} is non-empty and uses only the base-32 alphabet. */
  public boolean isValidGeohash(String geohash) {
    if (geohash == null || geohash.isEmpty()) {
      return false;
    }
    for (int i = 0; i < geohash.length(); i++) {
      if (GEOHASH_ALPHABET.indexOf(Character.toLowerCase(geohash.charAt(i))) < 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * Collects all building features for the given geohashes into one GeoJSON
   * {@code FeatureCollection} node. Invalid geohashes are skipped. Duplicate
   * features (possible when overlapping prefixes are requested) are de-duplicated
   * by their GeoJSON {@code id} when present.
   */
  public ObjectNode collectFeatures(List<String> geohashes, Path versionDir) {
    ArrayNode features = objectMapper.createArrayNode();
    Set<String> seenIds = new LinkedHashSet<>();

    for (String raw : geohashes) {
      String geohash = raw == null ? "" : raw.trim().toLowerCase();
      if (!isValidGeohash(geohash)) {
        continue;
      }
      for (Path leaf : leafFilesFor(geohash, versionDir)) {
        appendLeafFeatures(leaf, features, seenIds);
      }
    }

    ObjectNode collection = objectMapper.createObjectNode();
    collection.put("type", "FeatureCollection");
    collection.set("features", features);
    return collection;
  }

  /**
   * The leaf files that satisfy one requested geohash: the single leaf when the
   * geohash is at the stored level, or every leaf under the prefix subtree when
   * it is coarser. A finer-than-stored geohash is clamped to the stored level.
   */
  private List<Path> leafFilesFor(String geohash, Path versionDir) {
    String g = geohash.length() > storedLevel ? geohash.substring(0, storedLevel) : geohash;

    if (g.length() == storedLevel) {
      Path leaf = leafPath(versionDir, g);
      return Files.isRegularFile(leaf) ? List.of(leaf) : List.of();
    }

    // Coarser: walk the directory for this prefix and collect every leaf under it.
    Path subtree = subtreeDir(versionDir, g);
    if (!Files.isDirectory(subtree)) {
      return List.of();
    }
    List<Path> leaves = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(subtree)) {
      walk.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".geojson"))
          .forEach(leaves::add);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return leaves;
  }

  /**
   * The directory tree for a geohash, one directory per character: e.g. for
   * {@code u8c5d} returns {@code <version>/u/u8/u8c/u8c5/u8c5d}. Used as the
   * subtree root when a coarser (prefix) geohash is requested.
   */
  private Path subtreeDir(Path versionDir, String geohash) {
    Path dir = versionDir;
    for (int i = 1; i <= geohash.length(); i++) {
      dir = dir.resolve(geohash.substring(0, i));
    }
    return dir;
  }

  /**
   * Leaf file for a stored-level geohash. The compiler nests one directory per
   * character up to the (length-1) prefix and places the leaf file there, e.g.
   * {@code u8c5dmh -> u/u8/u8c/u8c5/u8c5d/u8c5dm/u8c5dmh.geojson}. So the leaf's
   * containing directory is the (length-1) prefix tree, NOT a directory named
   * the full geohash.
   */
  private Path leafPath(Path versionDir, String geohash) {
    Path dir = versionDir;
    for (int i = 1; i < geohash.length(); i++) {
      dir = dir.resolve(geohash.substring(0, i));
    }
    return dir.resolve(geohash + ".geojson");
  }

  /** Reads one leaf FeatureCollection and appends its features, de-duped by id. */
  private void appendLeafFeatures(Path leaf, ArrayNode out, Set<String> seenIds) {
    try {
      JsonNode root = objectMapper.readTree(leaf.toFile());
      JsonNode features = root.get("features");
      if (features == null || !features.isArray()) {
        return;
      }
      for (JsonNode feature : features) {
        JsonNode idNode = feature.get("id");
        if (idNode != null && idNode.isValueNode()) {
          if (!seenIds.add(idNode.asText())) {
            continue;
          }
        }
        out.add(feature);
      }
    } catch (IOException e) {
      // A corrupt or unreadable tile should not fail the whole request.
      log.warn("Failed to read tile {}: {}", leaf, e.getMessage());
    }
  }
}
