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

import org.apache.gravitino.exceptions.UnauthorizedException;

/**
 * Strategy interface for authenticating different types of Google tokens.
 *
 * <p>Implementations of this interface handle specific Google token types (ID tokens, access
 * tokens, etc.) and extract the authenticated user's email address.
 */
public interface GoogleTokenStrategy {

  /**
   * Checks if this strategy can handle the given token.
   *
   * @param token The token to check
   * @return true if this strategy supports the token format, false otherwise
   */
  boolean supports(String token);

  /**
   * Authenticates the token and extracts the email address.
   *
   * @param token The token to authenticate
   * @return The authenticated user's email address
   * @throws UnauthorizedException if authentication fails
   */
  String extractClaim(String token) throws UnauthorizedException;
}
