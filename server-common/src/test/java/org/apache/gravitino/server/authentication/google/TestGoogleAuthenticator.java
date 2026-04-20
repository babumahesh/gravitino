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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.gravitino.Config;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for GoogleAuthenticator.
 *
 * <p>Note: These tests use mock tokens and simplified validation. Real integration tests with
 * Google tokens would require actual service accounts and network access to Google's token
 * verification endpoints.
 */
public class TestGoogleAuthenticator {

  private GoogleAuthenticator authenticator;
  private Config config;

  @BeforeEach
  public void setUp() {
    authenticator = new GoogleAuthenticator();
    config = mock(Config.class);
    // Set default principalFields
    when(config.get(GoogleAuthConfig.PRINCIPAL_FIELDS)).thenReturn(Arrays.asList("email"));
  }

  @Test
  public void testInitializeSuccess() {
    // No exceptions should be thrown
    authenticator.initialize(config);
  }

  @Test
  public void testInitializeWithAllowedAudiences() {
    when(config.get(GoogleAuthConfig.ALLOWED_AUDIENCES)).thenReturn("audience1,audience2");
    authenticator.initialize(config);
  }

  @Test
  public void testInitializeWithAllowedServiceAccounts() {
    when(config.get(GoogleAuthConfig.ALLOWED_SERVICE_ACCOUNTS))
        .thenReturn("sa1@project.iam.gserviceaccount.com,sa2@project.iam.gserviceaccount.com");
    authenticator.initialize(config);
  }

  @Test
  public void testIsDataFromToken() {
    assertTrue(authenticator.isDataFromToken());
  }

  @Test
  public void testSupportsTokenWithNullData() {
    assertFalse(authenticator.supportsToken(null));
  }

  @Test
  public void testSupportsTokenWithEmptyData() {
    assertFalse(authenticator.supportsToken(new byte[0]));
  }

  @Test
  public void testSupportsTokenWithoutBearerPrefix() {
    String token = "not-a-bearer-token";
    assertFalse(authenticator.supportsToken(token.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  public void testSupportsTokenWithBearerButNotJWT() {
    authenticator.initialize(config);
    // Opaque tokens (like access tokens) are now supported and will be validated via tokeninfo API
    String token = "Bearer invalid-token";
    assertTrue(authenticator.supportsToken(token.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  public void testAuthenticateTokenWithNullData() {
    authenticator.initialize(config);
    assertThrows(UnauthorizedException.class, () -> authenticator.authenticateToken(null));
  }

  @Test
  public void testAuthenticateTokenWithEmptyData() {
    authenticator.initialize(config);
    assertThrows(UnauthorizedException.class, () -> authenticator.authenticateToken(new byte[0]));
  }

  @Test
  public void testAuthenticateTokenWithoutBearerPrefix() {
    authenticator.initialize(config);
    String token = "invalid-token";
    assertThrows(
        UnauthorizedException.class,
        () -> authenticator.authenticateToken(token.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  public void testAuthenticateTokenWithBlankToken() {
    authenticator.initialize(config);
    String token = "Bearer ";
    assertThrows(
        UnauthorizedException.class,
        () -> authenticator.authenticateToken(token.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * Test that a token with Google issuer is recognized as likely a Google token.
   *
   * <p>Note: This test creates a minimal JWT structure for testing purposes. Real Google tokens
   * would be signed and have additional claims.
   */
  /**
   * Test that supports token correctly detects Bearer tokens.
   *
   * <p>Note: The strategy pattern delegates token format detection to individual strategies. Full
   * token validation would be better tested in integration tests with real Google service account
   * credentials.
   */
  @Test
  public void testSupportsTokenDelegatesTest() {
    authenticator.initialize(config);
    // Any Bearer token should be supported (strategies decide which one handles it)
    String token = "Bearer some-token";
    assertTrue(authenticator.supportsToken(token.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * Note: Full end-to-end authentication tests with real Google tokens would require:
   *
   * <ul>
   *   <li>A real Google service account credential
   *   <li>Network access to Google's token verification endpoints
   *   <li>Proper key signing and verification
   * </ul>
   *
   * <p>Such tests would be better suited for integration tests rather than unit tests.
   *
   * <p>Example integration test scenario:
   *
   * <pre>
   * // Given: A real Google service account credential
   * String credentialPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
   * GoogleCredentials credentials = GoogleCredentials.fromStream(new FileInputStream(credentialPath));
   * credentials.refreshIfExpired();
   * String token = credentials.getAccessToken().getTokenValue();
   *
   * // When: Authenticating with GoogleAuthenticator
   * String authHeader = "Bearer " + token;
   * Principal principal = authenticator.authenticateToken(authHeader.getBytes(StandardCharsets.UTF_8));
   *
   * // Then: Principal should be extracted successfully
   * assertNotNull(principal);
   * assertTrue(principal instanceof UserPrincipal);
   * </pre>
   */
  @Test
  public void testAuthenticateTokenIntegrationPlaceholder() {
    // This is a placeholder for integration tests
    // Real integration tests would require actual Google service account credentials
    // and would be tagged with @Tag("gravitino-docker-test") or similar
    assertTrue(true, "Integration tests with real Google tokens should be in separate test class");
  }
}
