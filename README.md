# 3d-buildings-backend

A Spring Boot REST service that serves 3D building data to the web client (`../front`).

It does three things:

1. **Serves building tiles.** It reads the geohash-tiled GeoJSON written by `../osm-to-3d-geojson`
   and returns the buildings for the requested map cells.
2. **Indexes buildings in Elasticsearch.** It loads the newest tile version into the `geo_features` index.
3. **Searches buildings by address.** Partial words and typos are supported.

```
osm-to-3d-geojson ──► <tiles root>/v<timestamp>/u/u8/.../u8c5dm.geojson
                                   │
                                   ▼
                         3d-buildings-backend (:8082)  ──── /index ───►  Elasticsearch (geo_features)
                                   │                   ◄─── /search ───
                                   ▼
                         front (Vite proxy /backend → :8082)
```

---

## Contents

1. [Requirements](#1-requirements)
2. [Quick start](#2-quick-start)
3. [Configuration](#3-configuration)
4. [API reference](#4-api-reference)
5. [Address search](#5-address-search)
6. [Tile storage and geohash lookup](#6-tile-storage-and-geohash-lookup)
7. [Elasticsearch](#7-elasticsearch)
8. [Project structure](#8-project-structure)
9. [Troubleshooting](#9-troubleshooting)

---

## 1. Requirements

| Tool           | Version | Used for                                                       |
|----------------|---------|----------------------------------------------------------------|
| JDK            | **21**  | Building and running (`java.version` in `pom.xml`; the code also uses Java 21 APIs such as `List.getFirst()`). |
| Maven          | 3.9+    | Build                                                          |
| Docker Desktop | any recent | Elasticsearch 8.17, Kibana and an nginx proxy (`elastic/compose.yaml`) |
| Tile data      | –       | Output of `../osm-to-3d-geojson` (see its README)              |

Main libraries: Spring Boot 3.3.6 (web, Spring Data Elasticsearch), JTS 1.19, geojson-jackson 1.14, Lombok.

---

## 2. Quick start

### 2.1 Start Elasticsearch

```powershell
cd elastic
docker compose up -d
```

This starts four containers (settings come from `elastic/.env`):

| Container      | Address                  | Purpose                                                        |
|----------------|--------------------------|----------------------------------------------------------------|
| `setup`        | –                        | Creates TLS certificates and the `kibana_system` password, then exits |
| `es01`         | `https://localhost:9200` | Elasticsearch 8.17.0 (HTTPS, security on)                      |
| `kibana`       | `http://localhost:5601`  | Kibana. Log in as `elastic` / `elastic`                        |
| `elastic-http` | `http://localhost:9201`  | nginx proxy: plain HTTP → HTTPS Elasticsearch. **The backend connects here.** |

The first start takes a minute or two. Elasticsearch is ready when this command returns JSON:

```powershell
curl.exe -u elastic:elastic http://localhost:9201
```

### 2.2 Create an API key

The backend authenticates with an Elasticsearch API key, not a password. Create one:

```powershell
$basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("elastic:elastic"))
Invoke-RestMethod -Method Post -Uri http://localhost:9201/_security/api_key `
  -Headers @{ Authorization = "Basic $basic" } -ContentType "application/json" `
  -Body '{"name":"3d-buildings-backend"}'
```

Copy the `encoded` value from the response. You can also create the key in Kibana:
**Stack Management → Security → API keys → Create API key**, then copy the *Encoded* value.

### 2.3 Point the backend at your data

Set these environment variables (or edit `src/main/resources/application.properties`):

```powershell
$env:ELASTIC_TOKEN  = "<encoded API key from 2.2>"
$env:GEO_TILES_ROOT = "C:/Users/<you>/Documents/master-diploma-lnu/data/output"   # folder that contains v<timestamp> folders
$env:GEO_TILES_STORED_LEVEL = "6"   # must equal common.geohash-precision used by the compiler
```

### 2.4 Build and run

Run from the `3d-buildings-backend` folder, because `geo.data.file` is a relative path:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.7"   # any JDK 21
mvn clean package
java -jar target\3d-buildings-backend-0.0.1-SNAPSHOT.jar
```

Or run directly with `mvn spring-boot:run`, or start `Buildings3DBackendApplication` from IntelliJ IDEA.
The server listens on **http://localhost:8082**.

### 2.5 Index the buildings and try a search

```powershell
curl.exe http://localhost:8082/index                     # loads the newest tile version into Elasticsearch
curl.exe "http://localhost:8082/search?q=городоцька%20355"
curl.exe http://localhost:8082/tiles/info
```

---

## 3. Configuration

All settings are in `src/main/resources/application.properties`. Settings written as `${ENV_VAR:default}`
can be overridden with an environment variable. Any setting can also be passed on the command line,
for example `java -jar ... --server.port=8093`.

| Property                     | Env variable                 | Default                                      | Description |
|------------------------------|------------------------------|----------------------------------------------|-------------|
| `server.port`                | –                            | `8082`                                       | HTTP port. The front's Vite proxy expects 8082 (`BACKEND_ORIGIN` in the front changes it). |
| `geo.tiles.root`             | `GEO_TILES_ROOT`             | `C:/Users/opilavka/Documents/master-diploma-lnu/data/output` | Folder with `v<timestamp>` tile versions from the compiler. |
| `geo.tiles.stored-level`     | `GEO_TILES_STORED_LEVEL`     | `6`                                          | Geohash length of the leaf tiles. **Must match** the compiler's `common.geohash-precision`. |
| `geo.tiles.max-geohashes`    | `GEO_TILES_MAX_GEOHASHES`    | `256`                                        | Maximum geohashes per `/tiles` request. |
| `geo.data.file`              | –                            | `src/main/resources/buildings-3d-v20260506204248.geojson` | Single GeoJSON file returned by `/fetch-data`. |
| `elastic.base-url`           | `ELASTIC_BASE_URL`           | `localhost:9201`                             | Elasticsearch `host:port`, plain HTTP. |
| `elastic.token`              | `ELASTIC_TOKEN`              | a development key                            | Encoded Elasticsearch API key, sent as `Authorization: ApiKey <token>`. |
| `elastic.connect-timeout-ms` | `ELASTIC_CONNECT_TIMEOUT_MS` | `10000`                                      | Time limit for opening a connection. |
| `elastic.socket-timeout-ms`  | `ELASTIC_SOCKET_TIMEOUT_MS`  | `120000`                                     | Time limit for one request. Kept high so bulk indexing does not time out. |

> **Security note.** `application.properties` contains a default API key, and `elastic/.env`
> contains the `elastic` password (`elastic`). Both are committed to git and are only suitable for a
> local development setup. Use `ELASTIC_TOKEN` and your own passwords anywhere else.

---

## 4. API reference

All endpoints are `GET`. CORS is open to every origin (`@CrossOrigin(origins = "*")`).
The front calls the backend through its Vite proxy as `/backend/<endpoint>`.

### `GET /tiles/info`

Returns the newest tile version and the area it covers. The front uses it to choose the geohash
level to request and where to centre the map.

| Query parameter | Required | Description                                      |
|-----------------|----------|--------------------------------------------------|
| `version`       | no       | A specific version folder, e.g. `v20261112122735`. Default: the newest. |

```json
{
  "storedLevel": 6,
  "maxGeohashes": 256,
  "version": "v20261112122735",
  "bbox": [24.005126953125, 49.8614501953125, 24.027099609375, 49.866943359375],
  "center": { "lng": 24.01611328125, "lat": 49.86419677734375 }
}
```

`bbox` is `[minLng, minLat, maxLng, maxLat]`. It is computed from the tile file names only; the GeoJSON
contents are not read. `version`, `bbox` and `center` are left out when no version or no tiles exist.

### `GET /tiles`

Returns all buildings in the requested geohash cells as one GeoJSON `FeatureCollection`.

| Query parameter | Required | Description                                                   |
|-----------------|----------|---------------------------------------------------------------|
| `geohashes`     | yes      | Comma-separated geohashes, e.g. `u8c5dm,u8c5dt` or a coarser `u8c5d`. |
| `version`       | no       | Version folder. Default: the newest.                          |

```
GET /tiles?geohashes=u8c5dm,u8c5dt
```

```json
{
  "type": "FeatureCollection",
  "features": [
    {
      "type": "Feature",
      "id": "29",
      "geometry": { "type": "MultiPolygon", "coordinates": [[[[24.0118532, 49.8658678], [24.0119654, 49.8658933], [24.0119951, 49.865838], [24.0118829, 49.8658125], [24.0118532, 49.8658678]]]] },
      "properties": { "height": 3, "address": "Львів, Варшавська вулиця, 171", "source": "osm", "dataSource": "osm" }
    }
  ]
}
```

The feature properties are written by the compiler: `height` in meters, `address`, `source` of the
floor count (`osm`, `overture`, `predicted`, `fallback`) and `dataSource` (`osm`, `overture`, `osm+overture`).
See the `osm-to-3d-geojson` README for details.

| Status | When |
|--------|------|
| `200`  | Success. Cells with no tile file add nothing, so the list can be empty. |
| `400`  | `geohashes` is empty after splitting, e.g. `geohashes=,` |
| `404`  | The tiles root does not exist, has no `v*` folder, or `version` was not found. |
| `422`  | More than `geo.tiles.max-geohashes` geohashes were requested. |

### `GET /search`

Searches buildings by address in Elasticsearch. Returns **at most 5** features, best match first.
The `geometry` of each result is the **centroid** of the building (a `Point`), not its footprint,
so the front can fly to it.

| Query parameter | Required | Description        |
|-----------------|----------|--------------------|
| `q`             | yes      | Search text.       |

```
GET /search?q=городоцька 302
```

```json
[
  {
    "type": "Feature",
    "id": "3470",
    "geometry": { "type": "Point", "coordinates": [23.91404738234539, 49.82539622620775] },
    "properties": { "height": 3, "address": "Львів, Городоцька вулиця, 302", "source": "fallback" }
  }
]
```

Blank `q` returns `[]` without querying Elasticsearch. Matching rules are in [5. Address search](#5-address-search).

### `GET /index`

Loads every building from the **newest** tile version under `geo.tiles.root` into the
Elasticsearch index `geo_features`, in batches of 2000. Returns `200` with the text
`Дані почали індексуватись!` once indexing has **finished** (the call blocks until then).
The log shows the result: `Indexed <N> features from <M> tiles of version 'v<timestamp>'.`

Read [7.2 Reindexing](#72-reindexing) before running it a second time.

### `GET /fetch-data`

Returns the whole file `geo.data.file` as JSON. This is the older single-file mode from before
tiling; the front still calls it to load all buildings at once. It reads the full file on every request,
so use `/tiles` for large areas.

---

## 5. Address search

Search is implemented in `elastic/ElasticSearchService.java` and queries the `properties.address` field.

**Rules**

1. The text is lowercased and split into words on anything that is not a letter or digit
   (spaces, commas, hyphens, apostrophes).
2. **Every** word must match the address (AND).
3. A word matches in one of four ways. Results are ranked in this order:

   | Match type             | Example query → match         | Elasticsearch query |
   |------------------------|-------------------------------|---------------------|
   | Whole word             | `городоцька` → Городоцька     | `match`, boost 3    |
   | Start of a word        | `город` → Городоцька          | `prefix`, boost 2   |
   | Inside a word          | `родоц` → Городоцька          | `wildcard *word*`, boost 1 |
   | Whole word with typos  | `городоцка` → Городоцька      | `match` with `fuzziness: AUTO:4,7`, boost 0.5 |

**Typos allowed per word:** none for 1–3 characters, one for 4–7, two for 8 or more.
A wrong, missing or extra letter, or two swapped neighbouring letters, each count as one typo.
Short words such as house numbers must match exactly, so `128` does not find `126`.

**Examples** (Lviv data, 27,023 buildings):

| Query            | Top results                                                        |
|------------------|--------------------------------------------------------------------|
| `городоцька 355` | Городоцька вулиця, 355 · 355/6 · 355-3                              |
| `город 36`       | Городоцька вулиця, 36 · Городницька вулиця, 36 · Миргородська вулиця, 36 |
| `городоцка 302`  | Городоцька вулиця, 302                                             |
| `в'ячеслава`     | проспект Вʼячеслава Чорновола · вулиця Вʼячеслава Липинського (the stored apostrophe is a different character) |

**Limitations**

- A word that is both unfinished and misspelled (e.g. `горадоц`) does not match.
  Typos are only matched against whole words.
- Elasticsearch's `case_insensitive` option does not fold Cyrillic letters, so the input is
  lowercased in Java before the query is built.
- The "inside a word" match uses a wildcard query that starts with `*`. It took about 50 ms on
  27,000 documents. For much larger indexes, an n-gram analyzer on `properties.address` would be faster,
  but it needs a mapping change and a reindex.

---

## 6. Tile storage and geohash lookup

The compiler writes one folder per run under `geo.tiles.root`, with one folder level per geohash
character. A leaf file holds the buildings of one geohash cell at the stored level:

```
<geo.tiles.root>/
├── v20261026211302/
└── v20261112122735/                  ← newest = largest name, used by default
    └── u/u8/u8c/u8c5/u8c5d/
        ├── u8c5dm.geojson            ← leaf at stored level 6
        └── u8c5dt.geojson
```

How `/tiles` resolves each requested geohash (`TileService`):

| Requested length vs. stored level | What is read |
|-----------------------------------|--------------|
| Equal (`u8c5dm`)                  | The single leaf file. |
| Shorter / coarser (`u8c5d`)       | Every leaf under that folder, merged. |
| Longer / finer (`u8c5dmh`)        | Cut to the stored level, then read as "equal". |

- Geohashes with characters outside the base-32 alphabet (`0-9`, `b-z` without `a i l o`) are skipped.
- Features that appear in several requested cells (for example `u8c5d` and `u8c5dm` together)
  are returned once, using the GeoJSON `id` to detect duplicates.
- An unreadable tile file is logged and skipped. It does not fail the request.
- `version` must be a single folder name. Values containing `/`, `\` or `..` return 404.

---

## 7. Elasticsearch

### 7.1 Index

| Item     | Value |
|----------|-------|
| Index    | `geo_features`, created automatically on the first `/index` call |
| Document | One GeoJSON feature per building: `id`, `geometry`, `properties.*` |
| Doc id   | The feature `id` from the compiler output |
| Mapping  | Dynamic. `properties.address` becomes `text` (standard analyzer) with a `keyword` sub-field |

Inspect it through the proxy, with the API key or the `elastic` user:

```powershell
curl.exe -u elastic:elastic http://localhost:9201/geo_features/_count
curl.exe -u elastic:elastic http://localhost:9201/geo_features/_mapping
```

Or in Kibana at http://localhost:5601 → **Dev Tools**.

### 7.2 Reindexing

`/index` writes documents using the compiler's feature `id` as the document id. Those ids are
sequential numbers within one compiler run, so they are **not stable between runs**:

- a document with an existing id is overwritten by the new building with that id;
- documents whose ids no longer exist in the new version **stay** in the index.

To index a new compiler version cleanly, delete the index first:

```powershell
curl.exe -u elastic:elastic -X DELETE http://localhost:9201/geo_features
curl.exe http://localhost:8082/index
```

`/index` always reads the newest version under `geo.tiles.root`. To index a different region,
start the backend with another `GEO_TILES_ROOT` (e.g. `.../data/output/lviv`).

### 7.3 Stop or reset the stack

```powershell
cd elastic
docker compose down        # stop, keep data
docker compose down -v     # stop and DELETE all Elasticsearch data, certificates and Kibana data
```

---

## 8. Project structure

```
3d-buildings-backend/
├── pom.xml
├── elastic/
│   ├── compose.yaml                  # Elasticsearch + Kibana + nginx HTTP proxy
│   ├── .env                          # stack version, ports, passwords, memory limit
│   └── nginx.conf                    # :9201 HTTP → es01:9200 HTTPS
├── change/                           # sample user-created building GeoJSON files (not read by the code)
└── src/
    ├── main/java/com/students/lnu/
    │   ├── Buildings3DBackendApplication.java   # Spring Boot entry point
    │   ├── controller/Controller.java            # all REST endpoints
    │   ├── tiles/TileService.java                # version lookup, geohash → tile files, bounds
    │   ├── elastic/
    │   │   ├── ElasticGeoService.java            # /index: tiles → Elasticsearch
    │   │   ├── ElasticSearchService.java         # /search: address query
    │   │   └── config/                           # Elasticsearch client (URL, API key, timeouts)
    │   └── geotools/GeoTools.java                # GeoJSON → JTS (centroid for search results)
    ├── main/resources/
    │   ├── application.properties
    │   └── buildings-3d-v20260506204248.geojson  # file served by /fetch-data
    └── test/java/.../Buildings3DBackendApplicationTests.java   # Spring context load test
```

Run the test with `mvn test` (JDK 21).

---

## 9. Troubleshooting

| Symptom | Cause and fix |
|---------|---------------|
| `release version 21 not supported` during build | Maven uses an older JDK. Set `JAVA_HOME` to a JDK 21. |
| `/tiles` and `/tiles/info` return 404 or no `version`, log says `Tiles root does not exist` | `GEO_TILES_ROOT` is wrong, or the folder has no `v*` subfolder. |
| `/tiles` returns an empty `features` list for an area that has buildings | `geo.tiles.stored-level` differs from the compiler's `common.geohash-precision`, so the leaf paths don't match. |
| `/search` or `/index` returns 500, log shows `Connection refused` | Elasticsearch or the nginx proxy is not running: `docker compose up -d` in `elastic/`, and check `http://localhost:9201`. |
| Elasticsearch answers `401` / `security_exception` | The API key is wrong, expired or deleted. Create a new one (2.2) and set `ELASTIC_TOKEN`. |
| `/index` fails with `... milliseconds timeout on connection` | Raise `ELASTIC_SOCKET_TIMEOUT_MS`. |
| Search returns buildings that are not in the current tiles | Old documents remain from a previous version. Delete the index and reindex (7.2). |
| `/fetch-data` returns 500 with `NoSuchFileException` | The backend was not started from the project folder, or `geo.data.file` points to a missing file. |
| Elasticsearch container keeps restarting | Not enough memory. Raise `MEM_LIMIT` in `elastic/.env` (bytes; default 1 GB) and the Docker Desktop memory limit. |
