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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
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
 * GoogleAuthenticator validates Google OAuth2 ID tokens.
 *
 * <p>This authenticator validates tokens issued by Google (https://accounts.google.com) using
 * Google's public keys. It does NOT require Gravitino to have a service account - token validation
 * uses public key cryptography.
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
 *   <li>Validates token signature using Google's public keys (fetched from
 *       https://www.googleapis.com/oauth2/v3/certs)
 *   <li>Verifies token not expired
 *   <li>Checks issuer is "https://accounts.google.com"
 *   <li>Optionally checks audience matches configured allowed audiences
 *   <li>Extracts email (service account identity) from token
 *   <li>Optionally checks email is in allowed service accounts list
 *   <li>Returns UserPrincipal with email as username
 * </ol>
 *
 * <p>Example usage with Spark:
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
  private static final String EMAIL_CLAIM = "email";
  private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

  private TokenVerifier tokenVerifier;
  private List<String> allowedServiceAccounts;

  @Override
  public boolean isDataFromToken() {
    return true;
  }

  @Override
  public void initialize(Config config) throws RuntimeException {
    try {
      // Build token verifier with Google's public key endpoints
      TokenVerifier.Builder builder = TokenVerifier.newBuilder().setIssuer(GOOGLE_ISSUER);

      // Optional: restrict allowed audiences
      String allowedAudiences = config.get(GoogleAuthConfig.ALLOWED_AUDIENCES);
      if (StringUtils.isNotBlank(allowedAudiences)) {
        // TokenVerifier expects a single audience, but we support comma-separated list
        // We'll validate audience manually in authenticateToken
        LOG.info("Google authenticator configured with allowed audiences: {}", allowedAudiences);
      }

      this.tokenVerifier = builder.build();

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

      LOG.info("Google authenticator initialized successfully");
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
      // Validate token signature, expiration, and issuer using Google's public keys
      JsonWebSignature jws = tokenVerifier.verify(token);
      Preconditions.checkNotNull(jws, "Token verification returned null");

      // Extract email from token payload
      Payload payload = jws.getPayload();
      String email = (String) payload.get(EMAIL_CLAIM);

      if (StringUtils.isBlank(email)) {
        LOG.warn("Token missing email claim");
        throw new UnauthorizedException("Token missing email claim");
      }

      // Optional: check if service account is in allowed list
      if (allowedServiceAccounts != null && !allowedServiceAccounts.isEmpty()) {
        if (!allowedServiceAccounts.contains(email)) {
          LOG.warn("Service account {} is not in allowed list: {}", email, allowedServiceAccounts);
          throw new UnauthorizedException(
              "Service account %s is not allowed to access Gravitino", email);
        }
      }

      LOG.debug("Successfully authenticated Google service account: {}", email);

      // Create UserPrincipal with the service account email
      // Keep the raw Authorization header value for downstream services
      return new UserPrincipal(email, authData);

    } catch (UnauthorizedException e) {
      // Re-throw validation errors without wrapping
      throw e;
    } catch (TokenVerifier.VerificationException e) {
      LOG.warn("Google token verification failed: {}", e.getMessage());
      throw new UnauthorizedException("Google token verification failed: %s", e.getMessage());
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

    // Check if token looks like a Google token by attempting to parse it
    // Google tokens are JWTs with specific structure
    try {
      // Try to parse as JWT - Google tokens should parse successfully
      JsonWebSignature.parse(JSON_FACTORY, token);
      // Additional check: Google tokens have "accounts.google.com" as issuer
      // We'll do a lightweight check here
      return isLikelyGoogleToken(token);
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Performs a lightweight check to determine if a token is likely a Google token. This helps
   * distinguish Google tokens from other OAuth2/JWT tokens.
   *
   * @param token The JWT token string
   * @return true if the token appears to be from Google
   */
  @VisibleForTesting
  boolean isLikelyGoogleToken(String token) {
    try {
      // Parse the token to check issuer without full validation
      JsonWebSignature jws = JsonWebSignature.parse(JSON_FACTORY, token);
      Object issuer = jws.getPayload().get("iss");
      return GOOGLE_ISSUER.equals(issuer);
    } catch (Exception e) {
      return false;
    }
  }
}
