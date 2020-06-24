/*******************************************************************************
 * Copyright (c) 2019, 2020 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.hono.service.management.credentials;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.hono.util.RegistryManagementConstants;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.annotation.JsonTypeIdResolver;
import com.google.common.base.Strings;

/**
 * This class encapsulates information that is common across all credential types supported in Hono.
 */
@JsonInclude(value = JsonInclude.Include.NON_NULL)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type", visible = true)
@JsonTypeIdResolver(CredentialTypeResolver.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class CommonCredential {

    @JsonProperty(RegistryManagementConstants.FIELD_AUTH_ID)
    private String authId;
    @JsonProperty(RegistryManagementConstants.FIELD_ENABLED)
    private Boolean enabled;
    @JsonProperty(RegistryManagementConstants.FIELD_COMMENT)
    private String comment;

    @JsonProperty(RegistryManagementConstants.FIELD_EXT)
    @JsonInclude(value = JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> extensions = new HashMap<>();

    /**
     * Get a list of secrets for this credential.
     *
     * @return The list of credentials, must not be {@code null}.
     */
    public abstract List<? extends CommonSecret> getSecrets();

    /**
     * Gets the type of secrets these credentials contain.
     *
     * @return The type name.
     */
    public abstract String getType();

    public String getAuthId() {
        return authId;
    }

    /**
     * Sets the authentication identifier that the device uses for authenticating to protocol adapters.
     *
     * @param authId The authentication identifier use for authentication.
     * @return A reference to this for fluent use.
     */
    public CommonCredential setAuthId(final String authId) {
        this.authId = authId;
        return this;
    }

    @JsonIgnore
    public boolean isEnabled() {
        return Optional.ofNullable(enabled).orElse(true);
    }

    /**
     * Sets whether protocol adapters may use these credentials to authenticate devices.
     * <p>
     * The default value of this property is {@code true}.
     *
     * @param enabled {@code true} if these credentials may be used to authenticate devices.
     * @return A reference to this for fluent use.
     */
    @JsonIgnore
    public CommonCredential setEnabled(final boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public String getComment() {
        return comment;
    }

    /**
     * Sets a human readable comment describing this credential type.
     *
     * @param comment   The comment to set for this credential.
     * @return          a reference to this for fluent use.
     */
    public CommonCredential setComment(final String comment) {
        this.comment = comment;
        return this;
    }

    public Map<String, Object> getExtensions() {
        return extensions;
    }

    /**
     * Sets the additional extension properties to use with this credential type.
     *
     * @param extensions    The additional properties to set for this credential.
     * @return              a reference to this for fluent use.
     */
    public CommonCredential setExtensions(final Map<String, Object> extensions) {
        this.extensions = extensions;
        return this;
    }

    /**
     * Adds a new extension entry to the device.
     * <p>
     * If an extension entry already exists for the specified key, the old value is replaced by the specified value.
     *
     * @param key The key of the entry.
     * @param value The value of the entry.
     * @return This instance, to allowed chained invocations.
     */
    public CommonCredential putExtension(final String key, final Object value) {
        if (this.extensions == null) {
            this.extensions = new HashMap<>();
        }
        this.extensions.put(key, value);
        return this;
    }

    /**
     * Checks if these credentials' properties represent a consisten state.
     * <p>
     * The check succeeds if the authId and type properties are neither {@code null} nor empty.
     *
     * @throws IllegalStateException if the check fails.
     */
    public void checkValidity() {
        if (Strings.isNullOrEmpty(authId)) {
            throw new IllegalStateException("missing auth ID");
        } else if (Strings.isNullOrEmpty(getType())) {
            throw new IllegalStateException("missing type");
        }
    }

    /**
     * Removes non-public information from these credentials.
     * <p>
     * This can be used for example to prevent secrets from being exposed to
     * external systems.
     * <p>
     * This default implementation does nothing.
     *
     * @return This credentials object with all non-public information removed.
     */
    public CommonCredential stripPrivateInfo() {
        return this;
    }

    /**
     * Merges another set of credentials into this one.
     * <p>
     * The secrets of the other credentials are merged into this one's
     * by means of the {@link CommonSecret#merge(CommonSecret)} method.
     *
     * @param other The credentials to be merged.
     * @return A reference to this for fluent use.
     * @throws NullPointerException if the given credentials are {@code null}.
     * @throws IllegalArgumentException if the other credentials are of a different type than this one's
     *                                  or if the other credentials do not contain secrets with the
     *                                  same identifiers as this one's.
     */
    @JsonIgnore
    public CommonCredential merge(final CommonCredential other) {

        Objects.requireNonNull(other);

        if (!getType().equals(other.getType())) {
            throw new IllegalArgumentException("credentials to be merged must be of the same type");
        }

        getSecrets().forEach(secret -> {
            Optional.ofNullable(secret.getId())
                .ifPresent(id -> {
                    other.findSecretById(id)
                        .ifPresentOrElse(
                                secret::merge,
                                () -> {
                                    throw new IllegalArgumentException(
                                            "other credential has no secret with id: " + secret.getId());
                                });
                });
        });

        return this;
    }

    private Optional<? extends CommonSecret> findSecretById(final String secretId) {

        if (secretId == null) {
            return Optional.empty();
        } else {
            return getSecrets()
                    .stream()
                    .filter(secret -> secretId.equals(secret.getId()))
                    .findFirst();
        }
    }
}
