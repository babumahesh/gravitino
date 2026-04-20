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

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.UrlEncodedContent;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.GenericData;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Strategy for authenticating Google access tokens (opaque format).
 *
 * <p>Access tokens are opaque strings that cannot be validated locally. This strategy validates
 * them by calling Google's tokeninfo API and extracts the principal claim from the response.
 *
 * <p>This is the token type sent by Apache Iceberg's GoogleAuthManager when using Spark/Trino with
 * Iceberg REST catalogs.
 */
public class AccessTokenStrategy implements GoogleTokenStrategy {
  private static final Logger LOG = LoggerFactory.getLogger(AccessTokenStrategy.class);
  private static final String GOOGLE_TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo";
  private static final String EXPIRES_IN = "expires_in";
  private static final String ACCESS_TOKEN = "access_token";
  private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

  private final HttpRequestFactory httpRequestFactory;
  private final List<String> principalFields;

  /**
   * Constructs an AccessTokenStrategy with the given HttpRequestFactory.
   *
   * @param httpRequestFactory The HTTP client factory for calling Google's tokeninfo API
   * @param principalFields List of claim fields to try for principal extraction (e.g., "email",
   *     "sub"). Tried in order, first non-null value is used.
   */
  public AccessTokenStrategy(HttpRequestFactory httpRequestFactory, List<String> principalFields) {
    this.httpRequestFactory = httpRequestFactory;
    this.principalFields = principalFields;
  }

  @Override
  public boolean supports(String token) {
    // Access tokens are opaque and don't have a specific format
    // This strategy acts as a fallback for non-JWT tokens
    return true;
  }

  @Override
  @SuppressWarnings("FormatStringAnnotation")
  public String extractClaim(String token) throws UnauthorizedException {
    try {
      // Call Google's tokeninfo endpoint to validate the access token
      HttpRequest request =
          httpRequestFactory.buildPostRequest(
              new GenericUrl(GOOGLE_TOKENINFO_URL),
              new UrlEncodedContent(Collections.singletonMap(ACCESS_TOKEN, token)));
      request.setParser(new JsonObjectParser(JSON_FACTORY));

      HttpResponse response = request.execute();
      int statusCode = response.getStatusCode();
      if (statusCode != 200) {
        throw new UnauthorizedException(
            "Access token validation failed with status: %d", statusCode);
      }

      // Parse the tokeninfo response
      GenericData tokenInfo = response.parseAs(GenericData.class);

      // Verify the token hasn't expired
      Object expiresIn = tokenInfo.get(EXPIRES_IN);
      if (expiresIn != null) {
        long expiresInSeconds = Long.parseLong(expiresIn.toString());
        if (expiresInSeconds <= 0) {
          throw new UnauthorizedException("Access token has expired");
        }
      }

      // Extract principal using configured fields (try in order, use first non-null)
      String principal = extractPrincipal(tokenInfo);

      LOG.debug("Successfully validated access token for: {}", principal);
      return principal;

    } catch (IOException e) {
      LOG.warn("Failed to validate access token with tokeninfo API: {}", e.getMessage());
      throw new UnauthorizedException(e, "Failed to validate access token");
    }
  }

  /**
   * Extracts the principal from tokeninfo response using configured principal fields. Tries each
   * field in order and returns the first non-null value.
   */
  private String extractPrincipal(GenericData tokenInfo) throws UnauthorizedException {
    if (principalFields != null && !principalFields.isEmpty()) {
      for (String field : principalFields) {
        if (StringUtils.isNotBlank(field)) {
          Object claimValue = tokenInfo.get(field);
          if (claimValue != null) {
            String principal = claimValue.toString();
            if (StringUtils.isNotBlank(principal)) {
              LOG.debug("Extracted principal from field '{}': {}", field, principal);
              return principal;
            }
          }
        }
      }
    }

    throw new UnauthorizedException(
        "No valid principal found in access token. Checked fields: " + principalFields);
  }
}
