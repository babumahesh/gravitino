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
package org.apache.gravitino.server.authentication.google;

import java.util.List;
import org.apache.gravitino.config.ConfigBuilder;
import org.apache.gravitino.config.ConfigConstants;
import org.apache.gravitino.config.ConfigEntry;

/**
 * Configuration for Google OAuth2 authenticator.
 *
 * <p>This configuration allows restricting which Google tokens are accepted by Gravitino.
 */
public interface GoogleAuthConfig {

  String GOOGLE_AUTH_CONFIG_PREFIX = "gravitino.authenticator.google.";

  /**
   * Comma-separated list of allowed audiences. If not set, audience validation is skipped.
   *
   * <p>Example: "gravitino-api,gravitino-ui"
   */
  ConfigEntry<String> ALLOWED_AUDIENCES =
      new ConfigBuilder(GOOGLE_AUTH_CONFIG_PREFIX + "allowed-audiences")
          .doc(
              "Comma-separated list of allowed audiences for Google OAuth2 tokens. "
                  + "If not set, audience validation is skipped.")
          .version(ConfigConstants.VERSION_0_8_0)
          .stringConf()
          .create();

  /**
   * Comma-separated list of allowed service account emails. If not set, all service accounts are
   * allowed.
   *
   * <p>Example: "spark-sa@project.iam.gserviceaccount.com,trino-sa@project.iam.gserviceaccount.com"
   *
   * <p>Use this to restrict which GCP service accounts can authenticate to Gravitino.
   */
  ConfigEntry<String> ALLOWED_SERVICE_ACCOUNTS =
      new ConfigBuilder(GOOGLE_AUTH_CONFIG_PREFIX + "allowed-service-accounts")
          .doc(
              "Comma-separated list of allowed Google service account emails. "
                  + "If not set, all service accounts are allowed.")
          .version(ConfigConstants.VERSION_0_8_0)
          .stringConf()
          .create();

  /**
   * Token claim field(s) to use as principal identity. Comma-separated list for fallback in order.
   *
   * <p>Example: "email,sub"
   *
   * <p>Google tokens contain multiple claims. This config specifies which claim(s) to extract as
   * the authenticated principal. The authenticator will try each field in order and use the first
   * non-null value found.
   *
   * <p>Common fields in Google tokens:
   *
   * <ul>
   *   <li><b>email</b>: Service account or user email (e.g.,
   *       "my-sa@project.iam.gserviceaccount.com")
   *   <li><b>sub</b>: Unique subject ID (e.g., "108234567890123456789")
   * </ul>
   *
   * <p>Default: "email"
   */
  ConfigEntry<List<String>> PRINCIPAL_FIELDS =
      new ConfigBuilder(GOOGLE_AUTH_CONFIG_PREFIX + "principalFields")
          .doc(
              "Token claim field(s) to use as principal identity. Comma-separated list for fallback in order (e.g., 'email,sub'). Default: 'email'.")
          .version(ConfigConstants.VERSION_0_8_0)
          .stringConf()
          .toSequence()
          .createWithDefault(java.util.Arrays.asList("email"));
}
