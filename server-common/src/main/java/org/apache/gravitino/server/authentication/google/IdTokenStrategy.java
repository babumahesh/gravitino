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

import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.json.webtoken.JsonWebSignature;
import com.google.api.client.json.webtoken.JsonWebToken.Payload;
import com.google.auth.oauth2.TokenVerifier;
import com.google.common.base.Preconditions;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Strategy for authenticating Google ID tokens (JWT format).
 *
 * <p>ID tokens are JWTs signed by Google that contain identity claims such as email and issuer.
 * This strategy validates the token signature using Google's public keys, checks the audience
 * claim, and extracts the principal claim (email or sub).
 */
public class IdTokenStrategy implements GoogleTokenStrategy {
  private static final Logger LOG = LoggerFactory.getLogger(IdTokenStrategy.class);
  private static final String GOOGLE_ISSUER = "https://accounts.google.com";
  private static final String AUDIENCE_CLAIM = "aud";
  private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

  private final TokenVerifier tokenVerifier;
  private final List<String> allowedAudiences;
  private final List<String> principalFields;

  /**
   * Constructs an IdTokenStrategy with the given TokenVerifier.
   *
   * @param tokenVerifier The TokenVerifier configured with Google's public keys
   * @param allowedAudiences List of allowed audience values. If null or empty, audience validation
   *     is skipped.
   * @param principalFields List of claim fields to try for principal extraction (e.g., "email",
   *     "sub"). Tried in order, first non-null value is used.
   */
  public IdTokenStrategy(
      TokenVerifier tokenVerifier, List<String> allowedAudiences, List<String> principalFields) {
    this.tokenVerifier = tokenVerifier;
    this.allowedAudiences = allowedAudiences;
    this.principalFields = principalFields;
  }

  @Override
  public boolean supports(String token) {
    try {
      // ID tokens are JWTs with three parts separated by dots
      String[] parts = token.split("\\.");
      if (parts.length != 3) {
        return false;
      }

      // Check if it's a valid JWT with Google issuer
      JsonWebSignature jws = JsonWebSignature.parse(JSON_FACTORY, token);
      Object issuer = jws.getPayload().get("iss");
      return GOOGLE_ISSUER.equals(issuer);
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  @SuppressWarnings("FormatStringAnnotation")
  public String extractClaim(String token) throws UnauthorizedException {
    try {
      // Validate token signature, expiration, and issuer using Google's public keys
      JsonWebSignature jws = tokenVerifier.verify(token);
      Preconditions.checkNotNull(jws, "Token verification returned null");

      // Extract and validate payload claims
      Payload payload = jws.getPayload();

      // Validate audience if configured
      if (allowedAudiences != null && !allowedAudiences.isEmpty()) {
        Object audienceClaim = payload.get(AUDIENCE_CLAIM);
        String audience = audienceClaim != null ? audienceClaim.toString() : null;

        if (StringUtils.isBlank(audience)) {
          LOG.warn("ID token missing audience claim");
          throw new UnauthorizedException("ID token missing audience claim");
        }

        if (!allowedAudiences.contains(audience)) {
          LOG.warn("ID token audience '{}' not in allowed list: {}", audience, allowedAudiences);
          throw new UnauthorizedException("ID token audience is not allowed: " + audience);
        }

        LOG.debug("ID token audience '{}' validated successfully", audience);
      }

      // Extract principal using configured fields (try in order, use first non-null)
      String principal = extractPrincipal(payload);

      LOG.debug("Successfully validated ID token for: {}", principal);
      return principal;

    } catch (TokenVerifier.VerificationException e) {
      LOG.warn("ID token verification failed: {}", e.getMessage());
      throw new UnauthorizedException(e, "ID token verification failed");
    }
  }

  /**
   * Extracts the principal from token payload using configured principal fields. Tries each field
   * in order and returns the first non-null value.
   */
  private String extractPrincipal(Payload payload) throws UnauthorizedException {
    if (principalFields != null && !principalFields.isEmpty()) {
      for (String field : principalFields) {
        if (StringUtils.isNotBlank(field)) {
          Object claimValue = payload.get(field);
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

    LOG.warn("No valid principal found in ID token. Checked fields: {}", principalFields);
    throw new UnauthorizedException("No valid principal found in ID token");
  }
}
