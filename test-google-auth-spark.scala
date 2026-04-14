/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

// Test GoogleAuthenticator with Spark + Iceberg
// Save this as test-google-auth-spark.scala and run with spark-shell

import org.apache.spark.sql.SparkSession

// Configuration
val serviceAccountKeyPath = "/Users/babumaheshpr/Downloads/gravitino-dev-492915-a9e195088e55.json"
val gravitinoIcebergRestUrl = "http://localhost:9001/iceberg"
val catalogName = "iceberg_catalog"

// Create Spark session with Iceberg + Google Auth
val spark = SparkSession.builder()
  .appName("Test Google Auth")
  .master("local[*]")
  .config("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
  .config(s"spark.sql.catalog.$catalogName", "org.apache.iceberg.spark.SparkCatalog")
  .config(s"spark.sql.catalog.$catalogName.catalog-impl", "org.apache.iceberg.rest.RESTCatalog")
  .config(s"spark.sql.catalog.$catalogName.uri", gravitinoIcebergRestUrl)
  // Google Authentication Configuration
  .config(s"spark.sql.catalog.$catalogName.rest.auth-manager", "org.apache.iceberg.gcp.auth.GoogleAuthManager")
  .config(s"spark.sql.catalog.$catalogName.gcp.auth.credentials-path", serviceAccountKeyPath)
  .config(s"spark.sql.catalog.$catalogName.prefix", catalogName)  // Use catalog name as prefix
  .getOrCreate()

println("========================================")
println("Testing Google Authentication with Gravitino")
println("========================================")

try {
  // Test 1: List namespaces (this will trigger authentication)
  println("\n[Test 1] Listing namespaces...")
  spark.sql(s"SHOW NAMESPACES IN $catalogName").show()
  println("✅ Authentication successful!")

  // Test 2: Create a test table
  println("\n[Test 2] Creating test table...")
  spark.sql(s"""
    CREATE TABLE IF NOT EXISTS $catalogName.test_schema.users (
      id BIGINT,
      name STRING,
      email STRING,
      created_at TIMESTAMP
    ) USING iceberg
  """)
  println("✅ Table created!")

  // Test 3: Insert test data
  println("\n[Test 3] Inserting test data...")
  spark.sql(s"""
    INSERT INTO $catalogName.test_schema.users VALUES
    (1, 'Alice', 'alice@example.com', current_timestamp()),
    (2, 'Bob', 'bob@example.com', current_timestamp()),
    (3, 'Charlie', 'charlie@example.com', current_timestamp())
  """)
  println("✅ Data inserted!")

  // Test 4: Query the table
  println("\n[Test 4] Querying data...")
  spark.sql(s"SELECT * FROM $catalogName.test_schema.users").show()
  println("✅ Query successful!")

  // Test 5: Show table properties (verify it's an Iceberg table)
  println("\n[Test 5] Showing table properties...")
  spark.sql(s"DESCRIBE EXTENDED $catalogName.test_schema.users").show(100, false)

  println("\n========================================")
  println("✅ ALL TESTS PASSED!")
  println("GoogleAuthenticator is working correctly!")
  println("========================================")

} catch {
  case e: Exception =>
    println("\n========================================")
    println("❌ TEST FAILED!")
    println(s"Error: ${e.getMessage}")
    e.printStackTrace()
    println("========================================")
}

spark.stop()