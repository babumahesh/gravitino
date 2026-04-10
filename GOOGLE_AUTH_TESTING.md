# Google Authenticator Testing Guide

This guide shows how to test the GoogleAuthenticator implementation with real clients.

## Prerequisites

1. **Google Cloud Project** with billing enabled
2. **Service Account** with appropriate permissions
3. **Gravitino Server** running with GoogleAuthenticator enabled
4. **Test Client**: Either Spark or Python script

## Option 1: Quick Test with Python (Recommended for First Test)

### Setup

```bash
# Install dependencies
pip install google-auth requests

# Download your service account key from Google Cloud Console
# Or create one:
gcloud iam service-accounts create gravitino-test-sa \
    --display-name="Gravitino Test Service Account"

gcloud iam service-accounts keys create ~/gravitino-test-sa.json \
    --iam-account=gravitino-test-sa@YOUR_PROJECT_ID.iam.gserviceaccount.com
```

### Configure Gravitino Server

Edit `conf/gravitino.conf`:

```properties
# Enable Google authenticator
gravitino.authenticators = google

# Optional: restrict to specific service accounts (comma-separated)
gravitino.authenticator.google.allowed-service-accounts = gravitino-test-sa@YOUR_PROJECT_ID.iam.gserviceaccount.com

# Enable Iceberg REST service
gravitino.auxService.iceberg-rest.enabled = true
gravitino.auxService.iceberg-rest.host = 0.0.0.0
gravitino.auxService.iceberg-rest.httpPort = 9001
```

### Start Gravitino

```bash
./bin/gravitino.sh restart
```

### Create Test Catalog

```bash
# Create metalake
curl -X POST http://localhost:8090/api/metalakes \
  -H "Content-Type: application/json" \
  -H "Authorization: Basic $(echo -n 'admin:' | base64)" \
  -d '{
    "name": "test_metalake",
    "comment": "Test metalake",
    "properties": {}
  }'

# Create Iceberg catalog (memory backend for quick testing)
curl -X POST http://localhost:8090/api/metalakes/test_metalake/catalogs \
  -H "Content-Type: application/json" \
  -H "Authorization: Basic $(echo -n 'admin:' | base64)" \
  -d '{
    "name": "test_catalog",
    "type": "RELATIONAL",
    "provider": "lakehouse-iceberg",
    "comment": "Test Iceberg catalog",
    "properties": {
      "catalog-backend": "memory",
      "warehouse": "/tmp/iceberg-warehouse"
    }
  }'

# Create schema
curl -X POST http://localhost:8090/api/metalakes/test_metalake/catalogs/test_catalog/schemas \
  -H "Content-Type: application/json" \
  -H "Authorization: Basic $(echo -n 'admin:' | base64)" \
  -d '{
    "name": "test_schema",
    "comment": "Test schema",
    "properties": {}
  }'
```

### Run Python Test

```bash
# Edit test-google-auth-simple.py and update:
# - SERVICE_ACCOUNT_KEY_PATH = "/path/to/gravitino-test-sa.json"

python test-google-auth-simple.py
```

### Expected Output

```
🚀 Starting Google Authentication Test

📄 Loading service account credentials from: /path/to/gravitino-test-sa.json
✅ Got OAuth token for: gravitino-test-sa@your-project.iam.gserviceaccount.com
   Token expires at: 2026-04-10 15:30:00
   Token (first 50 chars): ya29.c.c0AY_VpZi...

============================================================
Testing Gravitino Iceberg REST API with Google Authentication
============================================================

[Test 1] GET /v1/config
   URL: http://localhost:9001/iceberg/v1/test_metalake/config
   Status: 200
   ✅ Authentication successful!
   Response: {
     "defaults": {},
     "overrides": {}
   }

[Test 2] GET /v1/namespaces
   URL: http://localhost:9001/iceberg/v1/test_metalake/namespaces
   Status: 200
   ✅ Found 1 namespaces
      - test_schema

✅ Google Authentication Test Complete!
```

## Option 2: Test with Spark (More Realistic)

### Setup Spark with Iceberg

```bash
# Download Spark
wget https://archive.apache.org/dist/spark/spark-3.5.0/spark-3.5.0-bin-hadoop3.tgz
tar -xzf spark-3.5.0-bin-hadoop3.tgz
cd spark-3.5.0-bin-hadoop3

# Download required JARs
cd jars
wget https://repo1.maven.org/maven2/org/apache/iceberg/iceberg-spark-runtime-3.5_2.12/1.4.3/iceberg-spark-runtime-3.5_2.12-1.4.3.jar
wget https://repo1.maven.org/maven2/org/apache/iceberg/iceberg-gcp/1.4.3/iceberg-gcp-1.4.3.jar
cd ..
```

### Run Spark Test

```bash
# Edit test-google-auth-spark.scala and update:
# - serviceAccountKeyPath
# - gravitinoIcebergRestUrl

./bin/spark-shell --conf spark.driver.memory=2g -i test-google-auth-spark.scala
```

### Expected Output

```
========================================
Testing Google Authentication with Gravitino
========================================

[Test 1] Listing namespaces...
+-------------+
|    namespace|
+-------------+
| test_schema|
+-------------+
✅ Authentication successful!

[Test 2] Creating test table...
✅ Table created!

[Test 3] Inserting test data...
✅ Data inserted!

[Test 4] Querying data...
+---+-------+---------------------+-------------------+
| id|   name|                email|         created_at|
+---+-------+---------------------+-------------------+
|  1|  Alice|  alice@example.com|2026-04-10 14:30:00|
|  2|    Bob|    bob@example.com|2026-04-10 14:30:00|
|  3|Charlie|charlie@example.com|2026-04-10 14:30:00|
+---+-------+---------------------+-------------------+
✅ Query successful!

========================================
✅ ALL TESTS PASSED!
GoogleAuthenticator is working correctly!
========================================
```

## Option 3: Test with Real BigQuery Table (Advanced)

If you want to test with a real BigQuery table through Gravitino:

### 1. Create BigQuery Catalog in Gravitino

```bash
curl -X POST http://localhost:8090/api/metalakes/test_metalake/catalogs \
  -H "Content-Type: application/json" \
  -H "Authorization: Basic $(echo -n 'admin:' | base64)" \
  -d '{
    "name": "bigquery_catalog",
    "type": "RELATIONAL",
    "provider": "jdbc-bigquery",
    "comment": "BigQuery catalog",
    "properties": {
      "jdbc-url": "jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;ProjectId=YOUR_PROJECT_ID;",
      "jdbc-driver": "com.simba.googlebigquery.jdbc.Driver",
      "jdbc-user": "service-account-email",
      "jdbc-password": "path-to-key-file"
    }
  }'
```

### 2. Use Spark to Query BigQuery Through Gravitino

```scala
val spark = SparkSession.builder()
  .appName("Gravitino BigQuery Test")
  .config("spark.sql.catalog.gravitino", "org.apache.gravitino.spark.connector.catalog.GravitinoCatalog")
  .config("spark.sql.catalog.gravitino.uri", "http://localhost:8090")
  .config("spark.sql.catalog.gravitino.metalake", "test_metalake")
  // Google Authentication for Gravitino API
  .config("spark.sql.catalog.gravitino.auth-manager", "org.apache.iceberg.gcp.auth.GoogleAuthManager")
  .config("spark.sql.catalog.gravitino.gcp.auth.credentials-path", "/path/to/sa-key.json")
  .getOrCreate()

// Query BigQuery table through Gravitino
spark.sql("SELECT * FROM gravitino.bigquery_catalog.your_dataset.your_table LIMIT 10").show()
```

## Debugging Tips

### Check Gravitino Logs

```bash
tail -f logs/gravitino-server.out

# Look for these messages:
# ✅ Success:
#    "Successfully authenticated Google service account: gravitino-test-sa@project.iam.gserviceaccount.com"
#
# ❌ Failure:
#    "Google token verification failed: ..."
#    "Service account xxx is not in allowed list"
```

### Verify Token Locally

```python
# Test token validation locally
from google.oauth2 import service_account
from google.auth.transport.requests import Request

credentials = service_account.Credentials.from_service_account_file(
    '/path/to/sa-key.json',
    scopes=['https://www.googleapis.com/auth/cloud-platform']
)
credentials.refresh(Request())

print(f"Token: {credentials.token[:50]}...")
print(f"Email: {credentials.service_account_email}")
print(f"Expiry: {credentials.expiry}")
```

### Common Issues

| Issue | Solution |
|-------|----------|
| "Empty token authorization header" | Check `Authorization: Bearer <token>` header is set |
| "Google token verification failed" | Check service account key is valid and not expired |
| "Service account not allowed" | Add service account email to `allowed-service-accounts` config |
| Connection refused | Check Gravitino server is running on port 9001 |
| "Invalid token authorization header" | Ensure token starts with "Bearer " prefix |

## What Happens Under the Hood

```
1. Client (Spark/Python)
   └─> Gets OAuth token from Google using service account key
       Token: ya29.c.c0AY_VpZi...
       
2. Client sends HTTP request to Gravitino
   └─> Authorization: Bearer ya29.c.c0AY_VpZi...
   
3. IcebergAuthenticationFilter intercepts
   └─> Extracts token from Authorization header
   
4. GoogleAuthenticator.authenticateToken()
   ├─> Validates signature using Google's public keys
   ├─> Verifies token not expired
   ├─> Checks issuer = "https://accounts.google.com"
   ├─> Extracts email from token claims
   └─> Returns UserPrincipal("gravitino-test-sa@project.iam.gserviceaccount.com")
   
5. Request proceeds with authenticated principal
   └─> Authorization checks (roles/privileges) run
   └─> Business logic executes
   └─> Response returned
```

## Next Steps

After successful testing:

1. **Add authorization**: Configure roles and privileges for the service account
2. **Test with real data**: Connect to production BigQuery/GCS data
3. **Deploy to GKE/Dataproc**: Test with workload identity
4. **Monitor**: Set up logging and metrics for authentication events

## Files Created

- `test-google-auth-simple.py` - Python test script
- `test-google-auth-spark.scala` - Spark test script
- `GOOGLE_AUTH_TESTING.md` - This guide