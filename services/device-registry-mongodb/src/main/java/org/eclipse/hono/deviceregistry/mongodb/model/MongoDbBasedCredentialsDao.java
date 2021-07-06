/**
 * Copyright (c) 2021 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */


package org.eclipse.hono.deviceregistry.mongodb.model;

import java.net.HttpURLConnection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.hono.client.ClientErrorException;
import org.eclipse.hono.deviceregistry.mongodb.config.MongoDbConfigProperties;
import org.eclipse.hono.deviceregistry.mongodb.utils.MongoDbDocumentBuilder;
import org.eclipse.hono.service.HealthCheckProvider;
import org.eclipse.hono.service.management.credentials.CredentialsDto;
import org.eclipse.hono.tracing.TracingHelper;
import org.eclipse.hono.util.RegistryManagementConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.opentracing.References;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.healthchecks.HealthCheckHandler;
import io.vertx.ext.healthchecks.Status;
import io.vertx.ext.mongo.FindOptions;
import io.vertx.ext.mongo.IndexOptions;
import io.vertx.ext.mongo.UpdateOptions;

/**
 * A data access object for persisting credentials to a Mongo DB collection.
 *
 */
public final class MongoDbBasedCredentialsDao extends MongoDbBasedDao implements CredentialsDao, HealthCheckProvider {

    private static final Logger LOG = LoggerFactory.getLogger(MongoDbBasedCredentialsDao.class);

    private static final String KEY_AUTH_ID = String.format("%s.%s", CredentialsDto.FIELD_CREDENTIALS,
            RegistryManagementConstants.FIELD_AUTH_ID);
    private static final String KEY_CREDENTIALS_TYPE = String.format("%s.%s", CredentialsDto.FIELD_CREDENTIALS,
            RegistryManagementConstants.FIELD_TYPE);
    private static final String CREDENTIALS_FILTERED_POSITIONAL_OPERATOR = String.format("%s.$",
            CredentialsDto.FIELD_CREDENTIALS);


    private final AtomicBoolean creatingIndices = new AtomicBoolean(false);
    private final AtomicBoolean indicesCreated = new AtomicBoolean(false);

    /**
     * Creates a new DAO.
     *
     * @param dbConfig The Mongo DB configuration properties.
     * @param collectionName The name of the collection that contains the data.
     * @param vertx The vert.x instance to run on.
     * @throws NullPointerException if any of the parameters are {@code null}.
     */
    public MongoDbBasedCredentialsDao(
            final MongoDbConfigProperties dbConfig,
            final String collectionName,
            final Vertx vertx) {
        super(dbConfig, collectionName, vertx, null);
    }

    /**
     * Creates a new DAO.
     *
     * @param dbConfig The Mongo DB configuration properties.
     * @param collectionName The name of the collection that contains the data.
     * @param vertx The vert.x instance to run on.
     * @param tracer The tracer to use for tracking the processing of requests.
     * @throws NullPointerException if any of the parameters other than tracer are {@code null}.
     */
    public MongoDbBasedCredentialsDao(
            final MongoDbConfigProperties dbConfig,
            final String collectionName,
            final Vertx vertx,
            final Tracer tracer) {
        super(dbConfig, collectionName, vertx, tracer);
    }

    /**
     * Creates the indices in the MongoDB that can be used to make querying of data more efficient.
     *
     * @return A succeeded future if the indices have been created. Otherwise, a failed future.
     */
    public Future<Void> createIndices() {

        final Promise<Void> result = Promise.promise();

        if (creatingIndices.compareAndSet(false, true)) {

            // create unique index on tenant and device ID
            createIndex(
                    new JsonObject()
                            .put(RegistryManagementConstants.FIELD_PAYLOAD_TENANT_ID, 1)
                            .put(RegistryManagementConstants.FIELD_PAYLOAD_DEVICE_ID, 1),
                    new IndexOptions().unique(true))
                // create unique index on tenant, auth ID and type
                .compose(ok -> createIndex(
                        new JsonObject()
                                .put(RegistryManagementConstants.FIELD_PAYLOAD_TENANT_ID, 1)
                                .put(KEY_AUTH_ID, 1)
                                .put(KEY_CREDENTIALS_TYPE, 1),
                        new IndexOptions().unique(true)
                                .partialFilterExpression(new JsonObject()
                                        .put(KEY_AUTH_ID, new JsonObject().put("$exists", true))
                                        .put(KEY_CREDENTIALS_TYPE, new JsonObject().put("$exists", true)))))
                .onSuccess(ok -> indicesCreated.set(true))
                .onComplete(r -> {
                    creatingIndices.set(false);
                    result.handle(r);
                });
        } else {
            LOG.debug("already trying to create indices");
        }
        return result.future();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Registers a check that, when invoked, verifies that Tenant collection related indices have been
     * created and, if not, triggers the creation of the indices (again).
     */
    @Override
    public void registerReadinessChecks(final HealthCheckHandler readinessHandler) {
        readinessHandler.register(
                "credentials-indices-created-" + UUID.randomUUID(),
                status -> {
                    if (indicesCreated.get()) {
                        status.tryComplete(Status.OK());
                    } else {
                        LOG.debug("credentials-indices not (yet) created");
                        status.tryComplete(Status.KO());
                        createIndices();
                    }
                });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerLivenessChecks(final HealthCheckHandler livenessHandler) {
        // nothing to register
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<String> create(final CredentialsDto credentials, final SpanContext tracingContext) {

        Objects.requireNonNull(credentials);

        final Span span = tracer.buildSpan("add Credentials")
                .addReference(References.CHILD_OF, tracingContext)
                .withTag(TracingHelper.TAG_TENANT_ID, credentials.getTenantId())
                .withTag(TracingHelper.TAG_DEVICE_ID, credentials.getDeviceId())
                .start();

        final Promise<String> addCredentialsPromise = Promise.promise();

        mongoClient.insert(
                collectionName,
                JsonObject.mapFrom(credentials),
                addCredentialsPromise);

        return addCredentialsPromise.future()
                .map(added -> {
                    span.log("successfully added credentials");
                    LOG.debug("successfully added credentials for device [tenant: {}, device-id: {}, resource-version: {}]",
                            credentials.getTenantId(), credentials.getDeviceId(), credentials.getVersion());
                    return credentials.getVersion();
                })
                .recover(this::mapError)
                .onFailure(t -> {
                    LOG.debug("error adding credentials for device [tenant: {}, device-id: {}]",
                            credentials.getTenantId(), credentials.getDeviceId(), t);
                    TracingHelper.logError(span, "error adding credentials", t);
                })
                .onComplete(r -> span.finish());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<CredentialsDto> getByDeviceId(
            final String tenantId,
            final String deviceId,
            final SpanContext tracingContext) {

        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(deviceId);

        final Span span = tracer.buildSpan("get Credentials by device ID")
                .addReference(References.CHILD_OF, tracingContext)
                .withTag(TracingHelper.TAG_TENANT_ID, tenantId)
                .withTag(TracingHelper.TAG_DEVICE_ID, deviceId)
                .start();

        return getByDeviceId(tenantId, deviceId)
                .recover(this::mapError)
                .onFailure(t -> {
                    LOG.debug("error retrieving credentials by device ID", t);
                    TracingHelper.logError(span, "error retrieving credentials by device ID", t);
                })
                .onComplete(r -> span.finish());
    }

    private Future<CredentialsDto> getByDeviceId(
            final String tenantId,
            final String deviceId) {

        LOG.trace("retrieving credentials for device [tenant-id: {}, device-id: {}]", tenantId, deviceId);

        final JsonObject findCredentialsQuery = MongoDbDocumentBuilder.builder()
                .withTenantId(tenantId)
                .withDeviceId(deviceId)
                .document();
        final Promise<JsonObject> findCredentialsPromise = Promise.promise();

        mongoClient.findOne(collectionName, findCredentialsQuery, null, findCredentialsPromise);

        return findCredentialsPromise.future()
                .map(result -> {
                    if (result == null) {
                        throw new ClientErrorException(
                                tenantId,
                                HttpURLConnection.HTTP_NOT_FOUND,
                                "no matching credentials on record");
                    } else {
                        if (LOG.isTraceEnabled()) {
                            LOG.trace("credentials data from collection:{}{}",
                                    System.lineSeparator(), result.encodePrettily());
                        }
                        return result.mapTo(CredentialsDto.class);
                    }
                });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<CredentialsDto> getByAuthIdAndType(
            final String tenantId,
            final String authId,
            final String type,
            final SpanContext tracingContext) {

        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(authId);
        Objects.requireNonNull(type);

        final Span span = tracer.buildSpan("get Credentials by auth ID and type")
                .addReference(References.CHILD_OF, tracingContext)
                .withTag(TracingHelper.TAG_TENANT_ID, tenantId)
                .withTag(TracingHelper.TAG_AUTH_ID, authId)
                .withTag(TracingHelper.TAG_CREDENTIALS_TYPE, type)
                .start();

        final JsonObject findCredentialsQuery = MongoDbDocumentBuilder.builder()
                .withTenantId(tenantId)
                .withAuthId(authId)
                .withType(type)
                .document();

        if (LOG.isTraceEnabled()) {
            LOG.trace("retrieving credentials using search criteria: {}", findCredentialsQuery.encodePrettily());
        }

        final Promise<JsonObject> findCredentialsPromise = Promise.promise();

        mongoClient.findOne(
                collectionName,
                findCredentialsQuery,
                new JsonObject()
                    .put(RegistryManagementConstants.FIELD_PAYLOAD_DEVICE_ID, 1)
                    .put(CREDENTIALS_FILTERED_POSITIONAL_OPERATOR, 1)
                    .put("_id", 0),
                findCredentialsPromise);

        return findCredentialsPromise.future()
                .map(result -> {
                    if (result == null) {
                        throw new ClientErrorException(
                                tenantId,
                                HttpURLConnection.HTTP_NOT_FOUND,
                                "no matching credentials on record");
                    } else {
                        if (LOG.isTraceEnabled()) {
                            LOG.trace("credentials data from collection:{}{}",
                                    System.lineSeparator(), result.encodePrettily());
                        }
                        return result.mapTo(CredentialsDto.class);
                    }
                })
                .recover(this::mapError)
                .onFailure(t -> {
                    LOG.debug("error retrieving credentials by auth-id and type", t);
                    TracingHelper.logError(span, "error retrieving credentials by auth-id and type", t);
                })
                .onComplete(r -> span.finish());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<String> update(
            final String tenantId,
            final String deviceId,
            final CredentialsDto credentials,
            final Optional<String> resourceVersion,
            final SpanContext tracingContext) {

        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(credentials);
        Objects.requireNonNull(resourceVersion);

        final Span span = tracer.buildSpan("update Credentials")
                .addReference(References.CHILD_OF, tracingContext)
                .withTag(TracingHelper.TAG_TENANT_ID, tenantId)
                .withTag(TracingHelper.TAG_DEVICE_ID, deviceId)
                .start();
        resourceVersion.ifPresent(v -> span.setTag("resource_version", v));

        final JsonObject replaceCredentialsQuery = MongoDbDocumentBuilder.builder()
                .withVersion(resourceVersion)
                .withTenantId(tenantId)
                .withDeviceId(deviceId)
                .document();

        final var credentialsDtoJson = JsonObject.mapFrom(credentials);
        final Promise<JsonObject> replaceCredentialsPromise = Promise.promise();

        if (LOG.isTraceEnabled()) {
            LOG.trace("updating credentials of device [tenant: {}, device-id: {}, resource-version; {}]:{}{}",
                    tenantId, deviceId, resourceVersion.orElse(null), System.lineSeparator(), credentialsDtoJson.encodePrettily());
        }

        mongoClient.findOneAndReplaceWithOptions(
                collectionName,
                replaceCredentialsQuery,
                credentialsDtoJson,
                new FindOptions(),
                new UpdateOptions().setReturningNewDocument(true),
                replaceCredentialsPromise);

        return replaceCredentialsPromise.future()
                .compose(result -> {
                    if (result == null) {
                        return MongoDbBasedDao.checkForVersionMismatchAndFail(
                                String.format("credentials [tenant-id: %s, device-id: %s]", tenantId, deviceId),
                                resourceVersion,
                                getByDeviceId(tenantId, deviceId));
                    } else {
                        LOG.debug("successfully updated credentials for device [tenant: {}, device-id: {}]",
                                tenantId, deviceId);
                        span.log("successfully updated credentials");
                        if (LOG.isTraceEnabled()) {
                            LOG.trace("new document in DB:{}{}", System.lineSeparator(), result.encodePrettily());
                        }
                        return Future.succeededFuture(result.getString(CredentialsDto.FIELD_VERSION));
                    }
                })
                .recover(error -> {
                    if (MongoDbBasedDao.isDuplicateKeyError(error)) {
                        return Future.failedFuture(new ClientErrorException(
                                tenantId,
                                HttpURLConnection.HTTP_CONFLICT,
                                "credentials (type, auth-id) must be unique for device"));
                    } else {
                        return Future.failedFuture(error);
                    }
                })
                .recover(this::mapError)
                .onFailure(error -> {
                    LOG.debug("error updating credentials", error);
                    TracingHelper.logError(span, "error updating credentials", error);
                })
                .onComplete(r -> span.finish());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<Void> delete(
            final String tenantId,
            final String deviceId,
            final Optional<String> resourceVersion,
            final SpanContext tracingContext) {

        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(resourceVersion);

        final Span span = tracer.buildSpan("delete Credentials")
                .addReference(References.CHILD_OF, tracingContext)
                .withTag(TracingHelper.TAG_TENANT_ID, tenantId)
                .withTag(TracingHelper.TAG_DEVICE_ID, deviceId)
                .start();
        resourceVersion.ifPresent(v -> span.setTag("resource_version", v));

        final JsonObject removeCredentialsQuery = MongoDbDocumentBuilder.builder()
                .withVersion(resourceVersion)
                .withTenantId(tenantId)
                .withDeviceId(deviceId)
                .document();
        final Promise<JsonObject> removeCredentialsPromise = Promise.promise();

        mongoClient.findOneAndDelete(collectionName, removeCredentialsQuery, removeCredentialsPromise);

        return removeCredentialsPromise.future()
                .compose(result -> {
                    if (result == null) {
                        return MongoDbBasedDao.checkForVersionMismatchAndFail(
                                String.format("credentials [tenant-id: %s, device-id: %s]", tenantId, deviceId),
                                resourceVersion,
                                getByDeviceId(tenantId, deviceId));
                    } else {
                        span.log("successfully deleted credentials");
                        LOG.debug("successfully deleted credentials for device [tenant: {}, device-id: {}]",
                                tenantId, deviceId);
                        return Future.succeededFuture((Void) null);
                    }
                })
                .recover(this::mapError)
                .onFailure(error -> {
                    LOG.debug("error deleting credentials", error);
                    TracingHelper.logError(span, "error deleting credentials", error);
                })
                .onComplete(r -> span.finish());
    }
}