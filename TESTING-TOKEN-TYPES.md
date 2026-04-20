# Testing Different Google Token Types

Gravitino's GoogleAuthenticator supports **two types** of Google tokens. Use the appropriate test script for each.

## Configuration

You can configure which claim fields to extract as the principal identity:

```properties
# Default: extract email as principal
gravitino.authenticator.google.principalFields = email

# Alternative: try email first, fallback to sub (subject ID)
gravitino.authenticator.google.principalFields = email,sub

# Use subject ID as principal (numeric identifier)
gravitino.authenticator.google.principalFields = sub
```

**Common fields in Google tokens:**
- `email`: Service account or user email (e.g., `my-sa@project.iam.gserviceaccount.com`)
- `sub`: Unique subject ID (e.g., `108234567890123456789`)

The authenticator tries each field in order and uses the first non-null value found.

## Token Type Comparison

| Feature | ID Token (JWT) | Access Token (Opaque) |
|---------|---------------|----------------------|
| **Format** | JWT (3 parts: header.payload.signature) | Opaque string |
| **Validation** | Local (Google's public keys) | Remote (tokeninfo API) |
| **Contains email?** | Yes (in payload) | No (fetched from API) |
| **Used by** | Custom clients | Apache Iceberg GoogleAuthManager |
| **Test script** | `test-google-auth-simple.py` | `test-google-access-token.py` |

## Test Script 1: ID Tokens (JWT)

**File**: `test-google-auth-simple.py`

**What it tests**: Custom clients that explicitly request ID tokens

**How to run**:
```bash
# Install dependencies
pip install google-auth requests

# Update SERVICE_ACCOUNT_KEY_PATH in the script
# Then run:
python test-google-auth-simple.py
```

**What happens**:
1. Gets ID token using `IDTokenCredentials`
2. Token is a JWT (can be decoded)
3. Gravitino validates locally using Google's public keys
4. No external API call needed

**Example token**: `eyJhbGc...` (3 parts separated by dots)

---

## Test Script 2: Access Tokens (Opaque) ⭐ **NEW**

**File**: `test-google-access-token.py`

**What it tests**: Apache Iceberg's GoogleAuthManager behavior (real-world use case)

**How to run**:
```bash
# Install dependencies
pip install google-auth requests

# Update SERVICE_ACCOUNT_KEY_PATH in the script
# Then run:
python test-google-access-token.py
```

**What happens**:
1. Gets access token using regular `Credentials`
2. Token is opaque (cannot be decoded locally)
3. Gravitino validates by calling Google's tokeninfo API
4. External API call validates token and returns email

**Example token**: `ya29.c.c0AY_VpZ...` (opaque string, no dots)

---

## Test Script 3: Spark with Iceberg ⭐ **Real Production Use Case**

**File**: `test-google-auth-spark.scala`

**What it tests**: Real Spark + Iceberg + Gravitino flow

**How to run**:
```bash
# Download Spark and Iceberg JARs (see GOOGLE_AUTH_TESTING.md)
# Update serviceAccountKeyPath in the script
# Then run:
spark-shell -i test-google-auth-spark.scala
```

**What happens**:
1. Spark configures `GoogleAuthManager`
2. GoogleAuthManager generates **access tokens** (opaque)
3. Sends access token to Gravitino Iceberg REST
4. Gravitino validates via tokeninfo API
5. Spark queries work authenticated

---

## Which Test Should You Use?

| Scenario | Test Script |
|----------|------------|
| Testing basic Google auth | `test-google-auth-simple.py` (ID tokens) |
| **Testing Iceberg compatibility** | `test-google-access-token.py` (access tokens) ⭐ |
| Testing real Spark integration | `test-google-auth-spark.scala` |
| CI/CD automated tests | Both Python scripts |

## Verifying Both Token Types Work

Run BOTH test scripts to ensure complete coverage:

```bash
# Test 1: ID tokens (custom clients)
python test-google-auth-simple.py

# Test 2: Access tokens (Iceberg/Spark)
python test-google-access-token.py
```

Both should succeed! ✅

---

## How to Tell Which Token Type You Have

```python
token = "your_token_here"

# Count dots
if token.count('.') == 2:
    print("ID Token (JWT) - 3 parts")
else:
    print("Access Token (opaque)")
```

Or check the prefix:
- ID tokens: usually start with `eyJ...`
- Access tokens: usually start with `ya29.` or similar

---

## Behind the Scenes

### ID Token Flow:
```
Client → Get ID token → Send to Gravitino
         ↓
Gravitino → Parse JWT → Verify signature (local)
         ↓
Extract email from payload → Success
```

### Access Token Flow:
```
Client → Get access token → Send to Gravitino
         ↓
Gravitino → Call tokeninfo API → Google validates
         ↓
Google returns email → Success
```

Both flows result in the same outcome: authenticated principal with email!