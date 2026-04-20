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

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.oauth2.TokenVerifier;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.apache.gravitino.server.authentication.Authenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GoogleAuthenticator validates Google OAuth2 tokens (both ID tokens and access tokens).
 *
 * <p>This authenticator supports two types of Google tokens:
 *
 * <ul>
 *   <li><b>ID tokens (JWT format)</b>: Validated locally using Google's public keys. Contains
 *       identity claims like email and issuer. Used by clients that explicitly request ID tokens.
 *   <li><b>Access tokens (opaque)</b>: Validated by calling Google's tokeninfo API. These are the
 *       tokens sent by Iceberg's GoogleAuthManager and standard OAuth2 flows.
 * </ul>
 *
 * <p>The authenticator automatically detects the token type and uses the appropriate validation
 * method.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>gravitino.authenticators = google
 *   <li>gravitino.authenticator.google.allowed-audiences (optional) - Comma-separated list of
 *       allowed audiences. If not set, audience validation is skipped.
 *   <li>gravitino.authenticator.google.allowed-service-accounts (optional) - Comma-separated list
 *       of allowed service account emails. If not set, all service accounts are allowed.
 * </ul>
 *
 * <p>Token validation flow:
 *
 * <ol>
 *   <li>Detects if token is JWT (ID token) or opaque (access token)
 *   <li>For ID tokens: Validates signature using Google's public keys, checks expiration and issuer
 *   <li>For access tokens: Calls Google's tokeninfo API to validate and extract claims
 *   <li>Extracts email (service account identity) from token claims
 *   <li>Optionally checks email is in allowed service accounts list
 *   <li>Returns UserPrincipal with email as username
 * </ol>
 *
 * <p>Example usage with Spark Iceberg REST:
 *
 * <pre>
 * spark.sql.catalog.gravitino.rest.auth-manager = org.apache.iceberg.gcp.auth.GoogleAuthManager
 * spark.sql.catalog.gravitino.gcp.auth.credentials-path = /path/to/service-account.json
 * </pre>
 *
 * <p>After authentication, Gravitino's authorization layer checks if the authenticated service
 * account has permissions to access the requested resources.
 */
public class GoogleAuthenticator implements Authenticator {
  private static final Logger LOG = LoggerFactory.getLogger(GoogleAuthenticator.class);

  private static final String GOOGLE_ISSUER = "https://accounts.google.com";

  private List<GoogleTokenStrategy> strategies;
  private List<String> allowedServiceAccounts;
  private List<String> principalFields;

  @Override
  public boolean isDataFromToken() {
    return true;
  }

  @Override
  public void initialize(Config config) throws RuntimeException {
    try {
      // Initialize authentication strategies
      this.strategies = new ArrayList<>();

      // Parse principal fields (which claims to extract)
      this.principalFields = config.get(GoogleAuthConfig.PRINCIPAL_FIELDS);
      LOG.info("Google authenticator configured with principal fields: {}", principalFields);

      // Parse allowed audiences for ID token validation
      String allowedAudiencesConfig = config.get(GoogleAuthConfig.ALLOWED_AUDIENCES);
      List<String> allowedAudiences = null;
      if (StringUtils.isNotBlank(allowedAudiencesConfig)) {
        allowedAudiences =
            List.of(allowedAudiencesConfig.split(",")).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        LOG.info("Google authenticator configured with allowed audiences: {}", allowedAudiences);
      }

      // Strategy 1: ID Token (JWT) - try first as it's more efficient (no API call)
      TokenVerifier tokenVerifier = TokenVerifier.newBuilder().setIssuer(GOOGLE_ISSUER).build();
      this.strategies.add(new IdTokenStrategy(tokenVerifier, allowedAudiences, principalFields));

      // Strategy 2: Access Token (opaque) - fallback, requires API call to tokeninfo
      this.strategies.add(
          new AccessTokenStrategy(new NetHttpTransport().createRequestFactory(), principalFields));

      // Optional: restrict allowed service accounts
      String allowedSaConfig = config.get(GoogleAuthConfig.ALLOWED_SERVICE_ACCOUNTS);
      if (StringUtils.isNotBlank(allowedSaConfig)) {
        this.allowedServiceAccounts =
            List.of(allowedSaConfig.split(",")).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        LOG.info(
            "Google authenticator configured with allowed service accounts: {}",
            this.allowedServiceAccounts);
      }

      LOG.info(
          "Google authenticator initialized successfully (supports ID tokens and access tokens)");
    } catch (Exception e) {
      LOG.error("Failed to initialize Google authenticator", e);
      throw new RuntimeException("Failed to initialize Google authenticator", e);
    }
  }

  @Override
  @SuppressWarnings("OrphanedFormatString") // UnauthorizedException uses @FormatMethod
  public Principal authenticateToken(byte[] tokenData) {
    if (tokenData == null) {
      LOG.warn("Empty token authorization header");
      throw new UnauthorizedException("Empty token authorization header");
    }

    String authData = new String(tokenData, StandardCharsets.UTF_8);
    if (StringUtils.isBlank(authData)
        || !authData.startsWith(AuthConstants.AUTHORIZATION_BEARER_HEADER)) {
      LOG.warn("Invalid token authorization header format");
      throw new UnauthorizedException("Invalid token authorization header");
    }

    String token = authData.substring(AuthConstants.AUTHORIZATION_BEARER_HEADER.length());
    if (StringUtils.isBlank(token)) {
      throw new UnauthorizedException("Blank token found");
    }

    try {
      // Try each strategy until one succeeds
      String authClaim = null;
      for (GoogleTokenStrategy strategy : strategies) {
        if (strategy.supports(token)) {
          LOG.debug("Using {} to authenticate token", strategy.getClass().getSimpleName());
          authClaim = strategy.extractClaim(token);
          break;
        }
      }

      if (authClaim == null) {
        throw new UnauthorizedException("No authentication strategy supports this token type");
      }

      // Optional: check if service account is in allowed list
      if (allowedServiceAccounts != null && !allowedServiceAccounts.isEmpty()) {
        if (!allowedServiceAccounts.contains(authClaim)) {
          LOG.warn("Service account {} is not in allowed list: {}", authClaim, allowedServiceAccounts);
          throw new UnauthorizedException(
              "Service account %s is not allowed to access Gravitino", authClaim);
        }
      }

      LOG.debug("Successfully authenticated Google service account: {}", authClaim);

      // Create UserPrincipal with the service account authClaim
      // Keep the raw Authorization header value for downstream services
      return new UserPrincipal(authClaim, authData);

    } catch (UnauthorizedException e) {
      // Re-throw validation errors without wrapping
      throw e;
    } catch (Exception e) {
      LOG.error("Failed to authenticate Google token: {}", e.getMessage(), e);
      throw new UnauthorizedException(e, "Failed to authenticate Google token");
    }
  }

  @Override
  public boolean supportsToken(byte[] tokenData) {
    if (tokenData == null) {
      return false;
    }

    String authData = new String(tokenData, StandardCharsets.UTF_8);
    if (!authData.startsWith(AuthConstants.AUTHORIZATION_BEARER_HEADER)) {
      return false;
    }

    String token = authData.substring(AuthConstants.AUTHORIZATION_BEARER_HEADER.length()).trim();
    if (token.isEmpty()) {
      return false;
    }

    // Check if any strategy supports this token
    for (GoogleTokenStrategy strategy : strategies) {
      if (strategy.supports(token)) {
        return true;
      }
    }

    return false;
  }
}
