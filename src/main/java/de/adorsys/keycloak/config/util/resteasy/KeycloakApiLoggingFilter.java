/*-
 * ---license-start
 * keycloak-config-cli
 * ---
 * Copyright (C) 2017 - 2021 adorsys GmbH & Co. KG @ https://adorsys.com
 * ---
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ---license-end
 */

package de.adorsys.keycloak.config.util.resteasy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;


/**
 * JAX-RS client filter that logs every outbound Keycloak Admin API request and its response.
 * <p>
 * Log format:
 * <pre>
 *   INFO  --> GET  https://keycloak/admin/realms/myrealm/users
 *   INFO  <-- 200 GET  https://keycloak/admin/realms/myrealm/users  (42 ms)
 *   DEBUG --> GET  https://keycloak/admin/realms/myrealm/users
 *         body: {"search":"foo",...}
 *   DEBUG <-- 200 GET  https://keycloak/admin/realms/myrealm/users  (42 ms)
 *         body: [{"id":"..."}]
 * </pre>
 * Control with {@code logging.level.keycloak-api=info} (no body) or {@code =debug} (with body).
 * Registered globally in {@link de.adorsys.keycloak.config.util.ResteasyUtil}.
 */
public class KeycloakApiLoggingFilter implements ClientRequestFilter, ClientResponseFilter {

    private static final Logger log = LoggerFactory.getLogger(KeycloakApiLoggingFilter.class);

    private static final String START_TIME_PROPERTY = KeycloakApiLoggingFilter.class.getName() + ".startTime";

    /** Max characters printed for any single body to avoid flooding the log. */
    private static final int MAX_BODY_LENGTH = 4096;

    private static final String REDACTED = "***REDACTED***";

    /** Masks JSON string values for sensitive fields: "password":"secret" */
    private static final Pattern JSON_STRING_SENSITIVE = Pattern.compile(
            "(\"(?:password|client_secret|secret)\"\\s*:\\s*\")([^\"]*)(\")",
            Pattern.CASE_INSENSITIVE
    );

    /** Masks JSON array values for sensitive fields: "password":["secret"] (Resteasy form serialization) */
    private static final Pattern JSON_ARRAY_SENSITIVE = Pattern.compile(
            "(\"(?:password|client_secret|secret)\"\\s*:\\s*\\[)([^\\]]*)(\\])",
            Pattern.CASE_INSENSITIVE
    );

    /** Masks token fields in JSON responses: "access_token":"eyJ..." */
    private static final Pattern JSON_TOKEN_PATTERN = Pattern.compile(
            "(\"(?:access_token|refresh_token|id_token)\"\\s*:\\s*\")([^\"]+)(\")",
            Pattern.CASE_INSENSITIVE
    );

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void filter(ClientRequestContext requestContext) {
        if (!log.isInfoEnabled()) {
            return;
        }
        requestContext.setProperty(START_TIME_PROPERTY, System.currentTimeMillis());
        URI uri = requestContext.getUri();
        String method = requestContext.getMethod();

        if (log.isDebugEnabled()) {
            String body = serializeRequestBody(requestContext.getEntity());
            if (body != null) {
                log.debug("--> {} {}\n        request-body: {}", method, uri, sanitize(body));
            } else {
                log.debug("--> {} {}", method, uri);
            }
        } else {
            log.info("--> {} {}", method, uri);
        }
    }

    @Override
    public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) throws IOException {
        if (!log.isInfoEnabled()) {
            return;
        }
        Long startTime = (Long) requestContext.getProperty(START_TIME_PROPERTY);
        long elapsed = startTime != null ? System.currentTimeMillis() - startTime : -1;
        URI uri = requestContext.getUri();
        String method = requestContext.getMethod();
        int status = responseContext.getStatus();

        if (log.isDebugEnabled() && responseContext.hasEntity()) {
            String body = sanitize(bufferAndReadBody(responseContext));
            if (elapsed >= 0) {
                log.debug("<-- {} {} {}  ({} ms)\n        response-body: {}", status, method, uri, elapsed, body);
            } else {
                log.debug("<-- {} {} {}\n        response-body: {}", status, method, uri, body);
            }
        } else {
            if (elapsed >= 0) {
                log.info("<-- {} {} {}  ({} ms)", status, method, uri, elapsed);
            } else {
                log.info("<-- {} {} {}", status, method, uri);
            }
        }
    }

    /**
     * Reads the full response body, resets the stream so downstream processing is unaffected,
     * and returns a truncated UTF-8 string for logging.
     */
    private static String bufferAndReadBody(ClientResponseContext responseContext) throws IOException {
        InputStream stream = responseContext.getEntityStream();
        byte[] bytes = stream.readAllBytes();
        responseContext.setEntityStream(new ByteArrayInputStream(bytes));
        return truncate(new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * Serializes the outbound request entity (a Java object) to a compact JSON string.
     * Falls back to {@code toString()} if Jackson cannot handle the type.
     * Returns {@code null} when there is no entity.
     */
    private static String serializeRequestBody(Object entity) {
        if (entity == null) {
            return null;
        }
        try {
            return truncate(MAPPER.writeValueAsString(entity));
        } catch (JsonProcessingException e) {
            return truncate(entity.toString());
        }
    }

    private static String sanitize(String body) {
        if (body == null) {
            return null;
        }
        String s = JSON_STRING_SENSITIVE.matcher(body).replaceAll("$1" + REDACTED + "$3");
        s = JSON_ARRAY_SENSITIVE.matcher(s).replaceAll("$1\"" + REDACTED + "\"$3");
        s = JSON_TOKEN_PATTERN.matcher(s).replaceAll("$1" + REDACTED + "$3");
        return s;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > MAX_BODY_LENGTH ? s.substring(0, MAX_BODY_LENGTH) + "... [truncated]" : s;
    }
}
