# Zooma 2

Ontology mapping service. Maps free-text property values to ontology terms using curated mappings, lexical matching, and embedding similarity search.

## Quick start

```bash
mvn clean package -pl backend
java -jar backend/target/zooma2-1.0-SNAPSHOT.jar
```

The API is available at `http://localhost:8090`. See `/v3/api/status` for health.

## Configuration

Runtime tuning lives in `config.json`, and the file is watched for changes while the app is running. `ZOOMA2_CONFIG_PATH` can also point to an `https` URL; in that case the config is fetched once at startup and is not watched for changes.

Environment variables:

| Variable | Default | Required | Description |
|---|---|---|---|
| `ZOOMA2_DB_URL` | `jdbc:sqlite:zooma.db` | No | JDBC URL (SQLite or PostgreSQL) |
| `ZOOMA2_DB_USER` | – | PostgreSQL only | Database username |
| `ZOOMA2_DB_PASS` | – | PostgreSQL only | Database password |
| `ZOOMA2_CONFIG_PATH` | `config.json` | No | Path to config file, or an `https` URL |
| `ZOOMA2_OLS_URL` | `https://www.ebi.ac.uk/ols4` | No | OLS4 API base URL |
| `ZOOMA2_CONTEXT_PATH` | (none) | No | URL path prefix, e.g. `/spot/zooma` |
| `ZOOMA2_CORS_ALLOWED_ORIGINS` | EBI + localhost origins | No | Comma-separated allowed browser origins; use `*` only for local testing |
| `ZOOMA2_MAX_REQUEST_BYTES` | `2097152` | No | Maximum request body size |
| `ZOOMA2_MAX_PROPERTIES` | `1000` | No | Maximum properties accepted by V3 map endpoints |
| `ZOOMA2_MAX_DEEP_PROPERTIES` | `200` | No | Maximum properties accepted when `deep=true` |
| `ZOOMA2_MAX_PROPERTY_TEXT_LENGTH` | `1000` | No | Maximum length for one property value |
| `ZOOMA2_MAX_ANNOTATE_TEXT_LENGTH` | `50000` | No | Maximum length for annotate-text input |
| `ZOOMA2_CACHE_TTL_SECONDS` | `2592000` (30 days) | No | How long cached OLS responses and terms stay valid; `0` never expires (the test suite uses this) |

## Database

The database stores: cached OLS term lookups and cached external API responses (OLS, OXO). The votes table still exists for compatibility, but V3 vote routes are currently disabled until anonymous feedback has abuse controls. The cache allows the test suite to run offline and reduces latency in production.

### SQLite (default)

No setup needed. The database file is created automatically:

```bash
# Default: zooma.db in the current directory
java -jar backend/target/zooma2-1.0-SNAPSHOT.jar

# Custom path
ZOOMA2_DB_URL=jdbc:sqlite:/data/zooma.db java -jar backend/target/zooma2-1.0-SNAPSHOT.jar
```

### PostgreSQL

```bash
export ZOOMA2_DB_URL=jdbc:postgresql://localhost:5432/zooma
export ZOOMA2_DB_USER=zooma
export ZOOMA2_DB_PASS=secret
java -jar backend/target/zooma2-1.0-SNAPSHOT.jar
```

## Tests

```bash
# Run integration tests (uses pre-populated cache for offline testing)
./tests/run_tests.sh

# Update expected output after intentional changes
UPDATE_EXPECTED=1 ./tests/run_tests.sh
```

## Docker

```bash
docker build -t zooma2-backend backend/
docker run -p 8090:8090 -v /path/to/data:/data zooma2-backend
```
