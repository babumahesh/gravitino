#!/usr/bin/env python3
"""
Simple test script for GoogleAuthenticator
Tests authentication by making direct HTTP requests to Gravitino Iceberg REST API
"""

import json
import sys
import requests
from google.auth.transport.requests import Request
from google.oauth2 import service_account

# Configuration
SERVICE_ACCOUNT_KEY_PATH = "/path/to/your/gravitino-test-sa.json"
GRAVITINO_ICEBERG_REST_URL = "http://localhost:9001/iceberg"
CATALOG_PREFIX = "test_metalake"

def get_google_oauth_token(service_account_key_path):
    """
    Get a Google OAuth token from a service account key file.

    This simulates what Spark's GoogleAuthManager does.
    """
    print(f"📄 Loading service account credentials from: {service_account_key_path}")

    credentials = service_account.Credentials.from_service_account_file(
        service_account_key_path,
        scopes=['https://www.googleapis.com/auth/cloud-platform']
    )

    # Refresh to get the access token
    credentials.refresh(Request())

    print(f"✅ Got OAuth token for: {credentials.service_account_email}")
    print(f"   Token expires at: {credentials.expiry}")
    print(f"   Token (first 50 chars): {credentials.token[:50]}...")

    return credentials.token

def test_iceberg_rest_api(token, catalog_prefix):
    """
    Test Gravitino Iceberg REST API with Google OAuth token.
    """
    headers = {
        'Authorization': f'Bearer {token}',
        'Content-Type': 'application/json',
        'Accept': 'application/json'
    }

    print("\n" + "="*60)
    print("Testing Gravitino Iceberg REST API with Google Authentication")
    print("="*60)

    # Test 1: Get config (public endpoint, but still authenticated)
    print("\n[Test 1] GET /v1/config")
    url = f"{GRAVITINO_ICEBERG_REST_URL}/v1/{catalog_prefix}/config"
    print(f"   URL: {url}")

    try:
        response = requests.get(url, headers=headers)
        print(f"   Status: {response.status_code}")
        if response.status_code == 200:
            print(f"   ✅ Authentication successful!")
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
                print(f"      - {'.'.join(ns)}")
        else:
            print(f"   ⚠️  Status: {response.status_code}")
            print(f"   Response: {response.text}")
    except Exception as e:
        print(f"   ❌ Request failed: {e}")

    # Test 3: Get namespace metadata
    print("\n[Test 3] GET /v1/namespaces/test_schema")
    url = f"{GRAVITINO_ICEBERG_REST_URL}/v1/{catalog_prefix}/namespaces/test_schema"
    print(f"   URL: {url}")

    try:
        response = requests.get(url, headers=headers)
        print(f"   Status: {response.status_code}")
        if response.status_code == 200:
            print(f"   ✅ Namespace found!")
            print(f"   Response: {json.dumps(response.json(), indent=2)}")
        elif response.status_code == 404:
            print(f"   ℹ️  Namespace 'test_schema' not found (expected if not created yet)")
        else:
            print(f"   Response: {response.text}")
    except Exception as e:
        print(f"   ❌ Request failed: {e}")

    # Test 4: List tables in namespace
    print("\n[Test 4] GET /v1/namespaces/test_schema/tables")
    url = f"{GRAVITINO_ICEBERG_REST_URL}/v1/{catalog_prefix}/namespaces/test_schema/tables"
    print(f"   URL: {url}")

    try:
        response = requests.get(url, headers=headers)
        print(f"   Status: {response.status_code}")
        if response.status_code == 200:
            tables = response.json().get('identifiers', [])
            print(f"   ✅ Found {len(tables)} tables")
            for table in tables:
                print(f"      - {table.get('namespace')}.{table.get('name')}")
        elif response.status_code == 404:
            print(f"   ℹ️  Namespace not found (expected if not created yet)")
        else:
            print(f"   Response: {response.text}")
    except Exception as e:
        print(f"   ❌ Request failed: {e}")

    print("\n" + "="*60)
    print("✅ Google Authentication Test Complete!")
    print("="*60)
    return True

def main():
    print("🚀 Starting Google Authentication Test\n")

    # Step 1: Get OAuth token from service account
    try:
        token = get_google_oauth_token(SERVICE_ACCOUNT_KEY_PATH)
    except Exception as e:
        print(f"\n❌ Failed to get OAuth token: {e}")
        print("\nMake sure:")
        print(f"  1. Service account key file exists at: {SERVICE_ACCOUNT_KEY_PATH}")
        print(f"  2. You have the google-auth library installed: pip install google-auth")
        sys.exit(1)

    # Step 2: Test Iceberg REST API
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