# Zooma 2

Ontology mapping service. Maps free-text property values to ontology terms using curated mappings, lexical matching, and embedding similarity search.

## Quick start

```bash
mvn clean package -pl backend
java -jar backend/target/zooma2-1.0-SNAPSHOT.jar
```

The API is available at `http://localhost:8090`. See `/v3/api/status` for health.

## Configuration

Runtime tuning lives in `config.json`, and the file is watched for changes while the app is running. `ZOOMA2_CONFIG_PATH` can also point to an `http(s)` URL; in that case the config is fetched once at startup and is not watched for changes.

Environment variables:

| Variable | Default | Required | Description |
|---|---|---|---|
| `ZOOMA2_DB_URL` | `jdbc:sqlite:zooma.db` | No | JDBC URL (SQLite or PostgreSQL) |
| `ZOOMA2_DB_USER` | – | PostgreSQL only | Database username |
| `ZOOMA2_DB_PASS` | – | PostgreSQL only | Database password |
| `ZOOMA2_CONFIG_PATH` | `config.json` | No | Path to config file, or an `http(s)` URL |
| `ZOOMA2_OLS_URL` | `https://www.ebi.ac.uk/ols4` | No | OLS4 API base URL |
| `ZOOMA2_CONTEXT_PATH` | (none) | No | URL path prefix, e.g. `/spot/zooma` |

## Database

The database stores: user mapping votes, cached OLS term lookups, and cached external API responses (OLS, OXO). The cache allows the test suite to run offline and reduces latency in production.

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
docker run -p 8090:8090 -v /path/to/data:/data_import zooma2-backend
```
