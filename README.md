# Zooma 2

Ontology mapping service. Maps free-text property values to ontology terms using curated mappings, lexical matching, and embedding similarity search.

## Quick start

```bash
mvn clean package -pl backend
java -jar backend/target/zooma2-1.0-SNAPSHOT.jar
```

The API is available at `http://localhost:8090`. See `/v3/api/status` for health.

## Configuration

All configuration is via environment variables:

| Variable | Default | Description |
|---|---|---|
| `ZOOMA2_DB_URL` | `jdbc:sqlite:zooma.db` | JDBC URL (SQLite or PostgreSQL) |
| `ZOOMA2_DB_USER` | – | Database username (PostgreSQL only) |
| `ZOOMA2_DB_PASS` | – | Database password (PostgreSQL only) |
| `ZOOMA2_DATA_PATH` | `data` | Path to curated mapping TSV files |
| `ZOOMA2_CONFIG_PATH` | `config.json` | Path to config file |
| `ZOOMA2_MODELS_PATH` | `models` | Directory containing PCA projection JSON files |
| `ZOOMA2_OLS_URL` | `https://wwwdev.ebi.ac.uk/ols4` | OLS4 API base URL |
| `ZOOMA2_CONTEXT_PATH` | (none) | URL path prefix, e.g. `/spot/zooma` |

## Database

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

## Embedding models

PCA projection matrices are discovered from the models directory (`ZOOMA2_MODELS_PATH`). Files must match the pattern `<model>_pca<N>.json.gz` (or `.json`), e.g. `llama-embed-nemotron-8b_pca512.json.gz`.

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
