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

// Verification script - checks if schema, table, and data exist
// Run with: spark-shell --packages org.apache.iceberg:iceberg-spark-runtime-3.5_2.12:1.5.0,org.apache.iceberg:iceberg-gcp:1.5.0 -i verify-google-auth-table.scala

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.analysis.NoSuchTableException

// Configuration
val serviceAccountKeyPath = "/Users/babumaheshpr/Downloads/gravitino-dev-492915-a9e195088e55.json"
val gravitinoIcebergRestUrl = "http://localhost:9001/iceberg"
val catalogName = "iceberg_catalog"
val schemaName = "test_schema"
val tableName = "users"

println("========================================")
println("Verifying Iceberg Table with Google Auth")
println("========================================")

// Create Spark session with Iceberg + Google Auth
val spark = SparkSession.builder()
  .appName("Verify Google Auth")
  .master("local[*]")
  .config("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
  .config(s"spark.sql.catalog.$catalogName", "org.apache.iceberg.spark.SparkCatalog")
  .config(s"spark.sql.catalog.$catalogName.catalog-impl", "org.apache.iceberg.rest.RESTCatalog")
  .config(s"spark.sql.catalog.$catalogName.uri", gravitinoIcebergRestUrl)
  .config(s"spark.sql.catalog.$catalogName.rest.auth-manager", "org.apache.iceberg.gcp.auth.GoogleAuthManager")
  .config(s"spark.sql.catalog.$catalogName.gcp.auth.credentials-path", serviceAccountKeyPath)
  .config(s"spark.sql.catalog.$catalogName.prefix", catalogName)
  .getOrCreate()

try {
  // Check 1: List all namespaces in catalog
  println("\n[Check 1] Listing all namespaces in catalog...")
  val namespaces = spark.sql(s"SHOW NAMESPACES IN $catalogName").collect()
  if (namespaces.isEmpty) {
    println("  ❌ No namespaces found in catalog")
  } else {
    println(s"  ✅ Found ${namespaces.length} namespace(s):")
    namespaces.foreach(row => println(s"     - ${row.getString(0)}"))
  }

  // Check 2: Verify if test_schema exists
  println(s"\n[Check 2] Checking if namespace '$schemaName' exists...")
  val schemaExists = namespaces.exists(_.getString(0) == schemaName)
  if (schemaExists) {
    println(s"  ✅ Namespace '$schemaName' exists")
  } else {
    println(s"  ❌ Namespace '$schemaName' does NOT exist")
    println(s"     You need to create it first!")
    spark.stop()
    sys.exit(1)
  }

  // Check 3: List all tables in test_schema
  println(s"\n[Check 3] Listing tables in '$schemaName'...")
  val tables = spark.sql(s"SHOW TABLES IN $catalogName.$schemaName").collect()
  if (tables.isEmpty) {
    println(s"  ❌ No tables found in '$schemaName'")
    println(s"     You need to run test-google-auth-spark.scala first!")
  } else {
    println(s"  ✅ Found ${tables.length} table(s):")
    tables.foreach(row => println(s"     - ${row.getString(1)}"))
  }

  // Check 4: Verify if 'users' table exists
  println(s"\n[Check 4] Checking if table '$tableName' exists...")
  val tableExists = tables.exists(_.getString(1) == tableName)
  if (tableExists) {
    println(s"  ✅ Table '$tableName' exists")
  } else {
    println(s"  ❌ Table '$tableName' does NOT exist")
    println(s"     Run test-google-auth-spark.scala to create it!")
    spark.stop()
    sys.exit(1)
  }

  // Check 5: Count rows in table
  println(s"\n[Check 5] Counting rows in '$catalogName.$schemaName.$tableName'...")
  val count = spark.sql(s"SELECT COUNT(*) as count FROM $catalogName.$schemaName.$tableName")
    .collect()(0).getLong(0)
  if (count == 0) {
    println(s"  ⚠️  Table exists but has 0 rows")
  } else {
    println(s"  ✅ Table has $count row(s)")
  }

  // Check 6: Read sample data
  println(s"\n[Check 6] Reading sample data (limit 5)...")
  val data = spark.sql(s"SELECT * FROM $catalogName.$schemaName.$tableName LIMIT 5")
  if (data.isEmpty) {
    println("  ⚠️  No data found")
  } else {
    println("  ✅ Sample data:")
    data.show(truncate = false)
  }

  // Check 7: Show table schema
  println(s"\n[Check 7] Table schema:")
  spark.sql(s"DESCRIBE $catalogName.$schemaName.$tableName").show(truncate = false)

  // Check 8: Show table properties
  println(s"\n[Check 8] Table properties (Iceberg metadata):")
  spark.sql(s"SHOW TBLPROPERTIES $catalogName.$schemaName.$tableName").show(50, truncate = false)

  println("\n========================================")
  println("✅ VERIFICATION COMPLETE!")
  println("========================================")

} catch {
  case e: NoSuchTableException =>
    println(s"\n❌ ERROR: Table not found!")
    println(s"   ${e.getMessage}")
    println(s"\n   Run test-google-auth-spark.scala to create the table first.")
  case e: Exception =>
    println(s"\n❌ ERROR: ${e.getMessage}")
    e.printStackTrace()
} finally {
  spark.stop()
}