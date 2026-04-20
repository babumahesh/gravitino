#!/usr/bin/env python3
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#  http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
"""
Test script for Google ACCESS TOKEN authentication (not ID token)

This script simulates what Apache Iceberg's GoogleAuthManager does - it gets
an OAuth2 access token (opaque) and sends it to Gravitino.

Gravitino validates it by calling Google's tokeninfo API.
"""

import json
import sys
import requests
from google.auth.transport.requests import Request
from google.oauth2 import service_account

# Configuration
SERVICE_ACCOUNT_KEY_PATH = "/Users/babumaheshpr/Downloads/gravitino-dev-492915-a9e195088e55.json"
GRAVITINO_ICEBERG_REST_URL = "http://localhost:9001/iceberg"
GRAVITINO_SERVER_URL = "http://localhost:8090"
CATALOG_PREFIX = "iceberg_catalog"

def get_google_access_token(service_account_key_path):
    """
    Get a Google ACCESS token (opaque) from a service account key file.

    This is what Apache Iceberg's GoogleAuthManager does.
    Access tokens are opaque strings (not JWTs) that Gravitino validates
    by calling Google's tokeninfo API.
    """
    print(f"📄 Loading service account credentials from: {service_account_key_path}")

    # Use regular Credentials (not IDTokenCredentials) to get access token
    credentials = service_account.Credentials.from_service_account_file(
        service_account_key_path,
        scopes=['https://www.googleapis.com/auth/cloud-platform']
    )

    # Refresh to get the access token
    credentials.refresh(Request())

    print(f"✅ Got ACCESS token for: {credentials.service_account_email}")
    print(f"   Token type: Access Token (opaque)")
    print(f"   Token (first 50 chars): {credentials.token[:50]}...")
    print(f"   Token expiry: {credentials.expiry}")

    # Verify it's NOT a JWT (access tokens are opaque)
    if credentials.token.count('.') == 2:
        print(f"   ⚠️  WARNING: This looks like a JWT, not an access token!")
    else:
        print(f"   ✅ Confirmed: Opaque access token (not JWT)")

    return credentials.token

def test_iceberg_rest_api(token, catalog_prefix):
    """
    Test Gravitino Iceberg REST API with Google ACCESS token.
    """
    headers = {
        'Authorization': f'Bearer {token}',
        'Content-Type': 'application/json',
        'Accept': 'application/json'
    }

    print("\n" + "="*60)
    print("Testing Gravitino with Google ACCESS TOKEN")
    print("="*60)

    # Test 1: Get config
    print("\n[Test 1] GET /v1/config")
    url = f"{GRAVITINO_ICEBERG_REST_URL}/v1/{catalog_prefix}/config"
    print(f"   URL: {url}")

    try:
        response = requests.get(url, headers=headers)
        print(f"   Status: {response.status_code}")
        if response.status_code == 200:
            print(f"   ✅ Access token authentication successful!")
            print(f"   Response: {json.dumps(response.json(), indent=2)}")
        else:
            print(f"   ❌ Authentication failed!")
            print(f"   Response: {response.text}")
            return False
    except Exception as e:
        print(f"   ❌ Request failed: {e}")
        return False

    # Test 2: List namespaces
    print("\n[Test 2] GET /v1/namespaces")
    url = f"{GRAVITINO_ICEBERG_REST_URL}/v1/{catalog_prefix}/namespaces"
    print(f"   URL: {url}")

    try:
        response = requests.get(url, headers=headers)
        print(f"   Status: {response.status_code}")
        if response.status_code == 200:
            namespaces = response.json().get('namespaces', [])
            print(f"   ✅ Found {len(namespaces)} namespaces")
            for ns in namespaces:
                print(f"      - {'.'.join(ns) if isinstance(ns, list) else ns}")
        else:
            print(f"   ⚠️  Status: {response.status_code}")
            print(f"   Response: {response.text}")
    except Exception as e:
        print(f"   ❌ Request failed: {e}")

    print("\n" + "="*60)
    print("✅ Google ACCESS Token Test Complete!")
    print("="*60)
    return True

def main():
    print("🚀 Testing Google ACCESS TOKEN Authentication\n")
    print("This script simulates Apache Iceberg's GoogleAuthManager")
    print("which sends ACCESS tokens (opaque), not ID tokens (JWT).\n")

    # Step 1: Get access token from service account
    try:
        token = get_google_access_token(SERVICE_ACCOUNT_KEY_PATH)
    except Exception as e:
        print(f"\n❌ Failed to get access token: {e}")
        print("\nMake sure:")
        print(f"  1. Service account key file exists at: {SERVICE_ACCOUNT_KEY_PATH}")
        print(f"  2. You have the google-auth library installed: pip install google-auth")
        sys.exit(1)

    # Step 2: Test Iceberg REST API with access token
    try:
        success = test_iceberg_rest_api(token, CATALOG_PREFIX)
        sys.exit(0 if success else 1)
    except Exception as e:
        print(f"\n❌ Test failed: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)

if __name__ == "__main__":
    # Update these before running
    if SERVICE_ACCOUNT_KEY_PATH == "/path/to/your/gravitino-test-sa.json":
        print("⚠️  Please update SERVICE_ACCOUNT_KEY_PATH in the script!")
        sys.exit(1)

    main()