#!/bin/bash
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

set -e

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Google Authenticator Test Setup${NC}"
echo -e "${GREEN}========================================${NC}"

# Check if running from Gravitino root
if [ ! -f "bin/gravitino.sh" ]; then
    echo -e "${RED}Error: Please run this script from Gravitino root directory${NC}"
    exit 1
fi

# Step 1: Check prerequisites
echo -e "\n${YELLOW}Step 1: Checking prerequisites...${NC}"

if ! command -v gcloud &> /dev/null; then
    echo -e "${YELLOW}Warning: gcloud not found. You'll need to create service account manually.${NC}"
    SKIP_SA_CREATION=true
else
    echo -e "${GREEN}✓ gcloud found${NC}"
    SKIP_SA_CREATION=false
fi

if ! command -v python3 &> /dev/null; then
    echo -e "${RED}Error: python3 not found${NC}"
    exit 1
else
    echo -e "${GREEN}✓ python3 found${NC}"
fi

# Step 2: Get GCP project ID
if [ "$SKIP_SA_CREATION" = false ]; then
    echo -e "\n${YELLOW}Step 2: GCP Configuration${NC}"

    read -p "Enter your GCP Project ID (or press Enter to use current project): " PROJECT_ID

    if [ -z "$PROJECT_ID" ]; then
        PROJECT_ID=$(gcloud config get-value project 2>/dev/null)
        if [ -z "$PROJECT_ID" ]; then
            echo -e "${RED}Error: No GCP project configured${NC}"
            exit 1
        fi
    fi

    echo -e "${GREEN}Using project: $PROJECT_ID${NC}"

    # Step 3: Create service account
    echo -e "\n${YELLOW}Step 3: Creating service account...${NC}"

    SA_NAME="gravitino-test-sa"
    SA_EMAIL="$SA_NAME@$PROJECT_ID.iam.gserviceaccount.com"
    KEY_FILE="$HOME/gravitino-test-sa.json"

    # Check if service account exists
    if gcloud iam service-accounts describe "$SA_EMAIL" --project="$PROJECT_ID" &>/dev/null; then
        echo -e "${YELLOW}Service account already exists: $SA_EMAIL${NC}"
    else
        echo "Creating service account: $SA_NAME"
        gcloud iam service-accounts create "$SA_NAME" \
            --display-name="Gravitino Test Service Account" \
            --project="$PROJECT_ID"
        echo -e "${GREEN}✓ Service account created${NC}"
    fi

    # Create key
    echo "Creating service account key..."
    if [ -f "$KEY_FILE" ]; then
        echo -e "${YELLOW}Key file already exists: $KEY_FILE${NC}"
        read -p "Overwrite? (y/n): " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            echo "Using existing key file"
        else
            rm "$KEY_FILE"
            gcloud iam service-accounts keys create "$KEY_FILE" \
                --iam-account="$SA_EMAIL" \
                --project="$PROJECT_ID"
            echo -e "${GREEN}✓ New key created: $KEY_FILE${NC}"
        fi
    else
        gcloud iam service-accounts keys create "$KEY_FILE" \
            --iam-account="$SA_EMAIL" \
            --project="$PROJECT_ID"
        echo -e "${GREEN}✓ Key created: $KEY_FILE${NC}"
    fi
else
    echo -e "\n${YELLOW}Step 2-3: Skipping service account creation${NC}"
    read -p "Enter path to your service account key file: " KEY_FILE

    if [ ! -f "$KEY_FILE" ]; then
        echo -e "${RED}Error: Key file not found: $KEY_FILE${NC}"
        exit 1
    fi

    # Extract email from key file
    SA_EMAIL=$(python3 -c "import json; print(json.load(open('$KEY_FILE'))['client_email'])")
    echo -e "${GREEN}Using service account: $SA_EMAIL${NC}"
fi

# Step 4: Update Gravitino configuration
echo -e "\n${YELLOW}Step 4: Updating Gravitino configuration...${NC}"

CONF_FILE="conf/gravitino.conf"

if [ ! -f "$CONF_FILE" ]; then
    echo -e "${RED}Error: Configuration file not found: $CONF_FILE${NC}"
    exit 1
fi

# Backup original config
cp "$CONF_FILE" "$CONF_FILE.backup"
echo -e "${GREEN}✓ Backed up config to $CONF_FILE.backup${NC}"

# Add/update Google authenticator config
if grep -q "gravitino.authenticators" "$CONF_FILE"; then
    echo "Updating existing authenticators config..."
    sed -i.tmp 's/^gravitino.authenticators.*/gravitino.authenticators = google/' "$CONF_FILE"
    rm -f "$CONF_FILE.tmp"
else
    echo "Adding authenticators config..."
    echo "" >> "$CONF_FILE"
    echo "# Google Authentication" >> "$CONF_FILE"
    echo "gravitino.authenticators = google" >> "$CONF_FILE"
fi

# Add allowed service accounts
if ! grep -q "gravitino.authenticator.google.allowed-service-accounts" "$CONF_FILE"; then
    echo "gravitino.authenticator.google.allowed-service-accounts = $SA_EMAIL" >> "$CONF_FILE"
fi

# Enable Iceberg REST if not already enabled
if ! grep -q "gravitino.auxService.iceberg-rest.enabled" "$CONF_FILE"; then
    echo "" >> "$CONF_FILE"
    echo "# Iceberg REST Service" >> "$CONF_FILE"
    echo "gravitino.auxService.iceberg-rest.enabled = true" >> "$CONF_FILE"
    echo "gravitino.auxService.iceberg-rest.host = 0.0.0.0" >> "$CONF_FILE"
    echo "gravitino.auxService.iceberg-rest.httpPort = 9001" >> "$CONF_FILE"
fi

echo -e "${GREEN}✓ Configuration updated${NC}"

# Step 5: Install Python dependencies
echo -e "\n${YELLOW}Step 5: Installing Python dependencies...${NC}"

pip3 install google-auth requests --quiet
echo -e "${GREEN}✓ Dependencies installed${NC}"

# Step 6: Update test scripts
echo -e "\n${YELLOW}Step 6: Updating test scripts...${NC}"

# Update Python test script
sed -i.tmp "s|SERVICE_ACCOUNT_KEY_PATH = \".*\"|SERVICE_ACCOUNT_KEY_PATH = \"$KEY_FILE\"|" test-google-auth-simple.py
rm -f test-google-auth-simple.py.tmp
chmod +x test-google-auth-simple.py
echo -e "${GREEN}✓ Updated test-google-auth-simple.py${NC}"

# Update Spark test script
sed -i.tmp "s|val serviceAccountKeyPath = \".*\"|val serviceAccountKeyPath = \"$KEY_FILE\"|" test-google-auth-spark.scala
rm -f test-google-auth-spark.scala.tmp
echo -e "${GREEN}✓ Updated test-google-auth-spark.scala${NC}"

# Step 7: Restart Gravitino
echo -e "\n${YELLOW}Step 7: Restarting Gravitino...${NC}"

./bin/gravitino.sh restart

echo -e "${GREEN}✓ Gravitino restarted${NC}"

# Wait for server to be ready
echo -e "\n${YELLOW}Waiting for Gravitino to be ready...${NC}"
for i in {1..30}; do
    if curl -s http://localhost:8090/api/version > /dev/null 2>&1; then
        echo -e "${GREEN}✓ Gravitino is ready!${NC}"
        break
    fi
    if [ $i -eq 30 ]; then
        echo -e "${RED}Error: Gravitino failed to start${NC}"
        exit 1
    fi
    echo -n "."
    sleep 1
done

# Step 8: Create test metalake and catalog
echo -e "\n${YELLOW}Step 8: Creating test metalake and catalog...${NC}"

# Create metalake
curl -s -X POST http://localhost:8090/api/metalakes \
  -H "Content-Type: application/json" \
  -H "Authorization: Basic $(echo -n 'admin:' | base64)" \
  -d '{
    "name": "test_metalake",
    "comment": "Test metalake for Google authentication",
    "properties": {}
  }' > /dev/null

echo -e "${GREEN}✓ Created metalake: test_metalake${NC}"

# Create Iceberg catalog
curl -s -X POST http://localhost:8090/api/metalakes/test_metalake/catalogs \
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
  }' > /dev/null

echo -e "${GREEN}✓ Created catalog: test_catalog${NC}"

# Create schema
curl -s -X POST http://localhost:8090/api/metalakes/test_metalake/catalogs/test_catalog/schemas \
  -H "Content-Type: application/json" \
  -H "Authorization: Basic $(echo -n 'admin:' | base64)" \
  -d '{
    "name": "test_schema",
    "comment": "Test schema",
    "properties": {}
  }' > /dev/null

echo -e "${GREEN}✓ Created schema: test_schema${NC}"

# Step 9: Summary
echo -e "\n${GREEN}========================================${NC}"
echo -e "${GREEN}Setup Complete! 🎉${NC}"
echo -e "${GREEN}========================================${NC}"
echo -e ""
echo -e "Service Account: ${GREEN}$SA_EMAIL${NC}"
echo -e "Key File: ${GREEN}$KEY_FILE${NC}"
echo -e ""
echo -e "${YELLOW}Next Steps:${NC}"
echo -e ""
echo -e "1. Run Python test:"
echo -e "   ${GREEN}python3 test-google-auth-simple.py${NC}"
echo -e ""
echo -e "2. Or run Spark test (requires Spark with Iceberg):"
echo -e "   ${GREEN}spark-shell -i test-google-auth-spark.scala${NC}"
echo -e ""
echo -e "3. Check Gravitino logs:"
echo -e "   ${GREEN}tail -f logs/gravitino-server.out${NC}"
echo -e ""
echo -e "${YELLOW}To restore original configuration:${NC}"
echo -e "   ${GREEN}mv conf/gravitino.conf.backup conf/gravitino.conf${NC}"
echo -e "   ${GREEN}./bin/gravitino.sh restart${NC}"
echo -e ""