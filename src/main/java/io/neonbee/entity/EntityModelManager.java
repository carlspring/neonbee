package io.neonbee.entity;

import static io.vertx.core.Future.failedFuture;
import static io.vertx.core.Future.succeededFuture;

import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.olingo.server.api.OData;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;

import io.neonbee.NeonBee;
import io.neonbee.internal.SharedDataAccessor;
import io.neonbee.logging.LoggingFacade;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;

/**
 * The {@link EntityModelManager} is used to manage shared model files across a given NeonBee instance.
 *
 * It loads and manages a shared instance of any number of {@link EntityModel EntityModels}, that are loaded from the
 * class path / the NeonBee models folder and which might be extended by external {@link EntityModelDefinition
 * EntityModelDefinitions}.
 */
public class EntityModelManager {
    /**
     * Every time new models are loaded a message will be published to this event bus address.
     */
    public static final String EVENT_BUS_MODELS_LOADED_ADDRESS = EntityModelManager.class.getSimpleName() + "Loaded";

    private static final ThreadLocal<OData> THREAD_LOCAL_ODATA = ThreadLocal.withInitial(OData::newInstance);

    private static final DeliveryOptions LOCAL_DELIVERY = new DeliveryOptions().setLocalOnly(true);

    private static final LoggingFacade LOGGER = LoggingFacade.create();

    /**
     * @deprecated remove with {@link #unregisterModels(Vertx, String)}
     */
    @Deprecated(forRemoval = true)
    private static final Map<NeonBee, Map<String, EntityModelDefinition>> EXTERNAL_MODEL_IDENTIFIERS =
            new WeakHashMap<>();

    /**
     * A map of all buffered NeonBee models.
     *
     * Do not use a {@link ConcurrentHashMap} here, as the references to Vert.x need to stay weak for Vert.x to properly
     * garbage collected, at the end of its lifetime. This means the {@link #bufferedModels} map must never be iterated
     * over, in order to not cause any {@link ConcurrentModificationException}. The inner maps are unmodifiable in any
     * case.
     */
    @VisibleForTesting
    Map<String, EntityModel> bufferedModels;

    /**
     * A set of externally managed model definition files. External models definitions are added to the model loading
     * step and can be added / removed from any client of the {@link EntityModelManager}.
     *
     * Same as for the {@link #bufferedModels} the NeonBee reference needs to stay weak, in order to allow for garbage
     * collection to occur. As also the internal set is written to, the set needs to be a concurrent one.
     */
    @VisibleForTesting
    final Set<EntityModelDefinition> externalModelDefinitions = ConcurrentHashMap.newKeySet();

    @VisibleForTesting
    final NeonBee neonBee;

    /**
     * Create a new instance of an {@link EntityModelManager} for a given {@link NeonBee} instance.
     *
     * Note that during the boot of NeonBee a {@link EntityModelManager} is created and assigned to the {@link NeonBee}
     * instance permanently. It is not possible to create another {@link EntityModelManager} and an
     * {@link IllegalArgumentException} will be thrown. Use {@code neonBee.getModelManager()} instead.
     *
     * @param neonBee the NeonBee instance this {@link EntityModelManager} is associated to
     */
    public EntityModelManager(NeonBee neonBee) {
        if (neonBee.getModelManager() != null) {
            throw new IllegalArgumentException(
                    "The passed NeonBee instance already has a EntityModelManager. Use neonBee.getModelManager() instead of creating a new EntityModelManager");
        }

        this.neonBee = neonBee;
    }

    /**
     * Get a buffered instance to OData.
     *
     * @return a thread local buffered instance of OData
     */
    public static OData getBufferedOData() {
        return THREAD_LOCAL_ODATA.get();
    }

    /**
     * Synchronously returns the buffered models, which could be null, in case {@link #getSharedModels(Vertx)} or
     * {@link #reloadModels(Vertx)} was never called or returned no valid metadata so far.
     * <p>
     * This is a convenience function, as for some instances (e.g. in EventBus Message Codec) the current metadata
     * definition needs to be received synchronously and without the involvement of futures. Whenever possible, use
     * {@link #getSharedModels(Vertx)} instead.
     *
     * @see #getBufferedModels(NeonBee)
     * @deprecated use {@link #getBufferedModels(NeonBee)} instead
     * @param vertx the {@link Vertx} instance
     * @return The buffered models for all schema namespaces or null in case no models have been loaded so far
     */
    @Deprecated(forRemoval = true)
    public static Map<String, EntityModel> getBufferedModels(Vertx vertx) {
        return getBufferedModels(NeonBee.get(vertx));
    }

    /**
     * Synchronously returns the buffered models, which could be null, in case {@link #getSharedModels(NeonBee)} or
     * {@link #reloadModels(NeonBee)} was never called or returned no valid metadata so far.
     * <p>
     * This is a convenience function, as for some instances (e.g. in EventBus Message Codec) the current metadata
     * definition needs to be received synchronously and without the involvement of futures. Whenever possible, use
     * {@link #getSharedModels(NeonBee)} instead.
     *
     * @see #getBufferedModels()
     * @deprecated use {@code neonBee.getModelManager().getBufferedModels()} instead
     * @param neonBee the {@link NeonBee} instance
     * @return The buffered models for all schema namespaces or null in case no models have been loaded so far
     */
    @Deprecated(forRemoval = true)
    public static Map<String, EntityModel> getBufferedModels(NeonBee neonBee) {
        return neonBee.getModelManager().getBufferedModels();
    }

    /**
     * Synchronously returns the buffered models, which could be null, in case {@link #getSharedModels()} or
     * {@link #reloadModels()} was never called or returned no valid metadata so far.
     * <p>
     * This is a convenience function, as for some instances (e.g. in EventBus Message Codec) the current metadata
     * definition needs to be received synchronously and without the involvement of futures. Whenever possible, use
     * {@link #getSharedModels()} instead.
     *
     * @return The buffered models for all schema namespaces or null in case no models have been loaded so far
     */
    public Map<String, EntityModel> getBufferedModels() {
        return bufferedModels;
    }

    /**
     * Synchronously returns the buffered model for a specific schema namespace, which could be null, in case
     * {@link #getSharedModels(Vertx)} or {@link #reloadModels(Vertx)} was never called or returned no valid metadata so
     * far.
     * <p>
     * This is a convenience function, as for some instances (e.g. in EventBus Message Codec) the current metadata
     * definition needs to be received synchronously and without the involvement of futures. Whenever possible, use
     * {@link #getSharedModel(Vertx, String)} instead.
     *
     * @see #getBufferedModel(NeonBee, String)
     * @deprecated use {@link #getBufferedModel(NeonBee, String)} instead
     * @param vertx           the {@link Vertx} instance
     * @param schemaNamespace the namespace of the service
     * @return the buffered model for a specific schema namespace or null in case no models have been loaded so far, or
     *         no model with the given schemaNamespace is found
     * @see #getBufferedModels(Vertx)
     */
    @Deprecated(forRemoval = true)
    public static EntityModel getBufferedModel(Vertx vertx, String schemaNamespace) {
        return getBufferedModel(NeonBee.get(vertx), schemaNamespace);
    }

    /**
     * Synchronously returns the buffered model for a specific schema namespace, which could be null, in case
     * {@link #getSharedModels(NeonBee)} or {@link #reloadModels(NeonBee)} was never called or returned no valid
     * metadata so far.
     * <p>
     * This is a convenience function, as for some instances (e.g. in EventBus Message Codec) the current metadata
     * definition needs to be received synchronously and without the involvement of futures. Whenever possible, use
     * {@link #getSharedModel(NeonBee, String)} instead.
     *
     * @see #getBufferedModel(String)
     * @deprecated use {@code neonBee.getModelManager().getBufferedModel(schemaNamespace)} instead
     * @param neonBee         the {@link NeonBee} instance
     * @param schemaNamespace the namespace of the service
     * @return the buffered model for a specific schema namespace or null in case no models have been loaded so far, or
     *         no model with the given schemaNamespace is found
     * @see #getBufferedModels(NeonBee)
     */
    @Deprecated(forRemoval = true)
    public static EntityModel getBufferedModel(NeonBee neonBee, String schemaNamespace) {
        return neonBee.getModelManager().getBufferedModel(schemaNamespace);
    }

    /**
     * Synchronously returns the buffered model for a specific schema namespace, which could be null, in case
     * {@link #getSharedModels()} or {@link #reloadModels()} was never called or returned no valid metadata so far.
     * <p>
     * This is a convenience function, as for some instances (e.g. in EventBus Message Codec) the current metadata
     * definition needs to be received synchronously and without the involvement of futures. Whenever possible, use
     * {@link #getSharedModel(String)} instead.
     *
     * @param schemaNamespace the namespace of the service
     * @return the buffered model for a specific schema namespace or null in case no models have been loaded so far, or
     *         no model with the given schemaNamespace is found
     * @see #getBufferedModels()
     */
    public EntityModel getBufferedModel(String schemaNamespace) {
        return bufferedModels != null ? bufferedModels.get(schemaNamespace) : null;
    }

    /**
     * Either returns a future to the buffered models, or tries to load / build the model definition files (from file
     * system and / or from the class path).
     *
     * @see #getSharedModels(NeonBee)
     * @deprecated use {@link #getSharedModels(NeonBee)} instead
     * @param vertx the {@link Vertx} instance
     * @return a {@link Future} to a map from schema namespace to EntityModel
     */
    @Deprecated(forRemoval = true)
    public static Future<Map<String, EntityModel>> getSharedModels(Vertx vertx) {
        return getSharedModels(NeonBee.get(vertx));
    }

    /**
     * Either returns a future to the buffered models, or tries to load / build the model definition files (from file
     * system and / or from the class path).
     *
     * @see #getSharedModels()
     * @deprecated use {@code neonBee.getModelManager().getSharedModels()} instead
     * @param neonBee the {@link NeonBee} instance
     * @return a {@link Future} to a map from schema namespace to EntityModel
     */
    @Deprecated(forRemoval = true)
    public static Future<Map<String, EntityModel>> getSharedModels(NeonBee neonBee) {
        return neonBee.getModelManager().getSharedModels();
    }

    /**
     * Either returns a future to the buffered models, or tries to load / build the model definition files (from file
     * system and / or from the class path).
     *
     * @return a {@link Future} to a map from schema namespace to EntityModel
     */
    public Future<Map<String, EntityModel>> getSharedModels() {
        // ignore the race condition here, in case serviceMetadata changes from if to return command, the new version
        // can be returned, in case it is null, it'll be checked once again inside of the synchronized block
        Map<String, EntityModel> models = getBufferedModels();
        if (models != null) {
            return succeededFuture(models);
        }

        // if not try to reload the models and return the loaded data model
        return new SharedDataAccessor(neonBee.getVertx(), EntityModelManager.class).getLocalLock()
                .transform(asyncLocalLock -> {
                    Map<String, EntityModel> retryModels = getBufferedModels();
                    if (retryModels != null) {
                        if (asyncLocalLock.succeeded()) {
                            asyncLocalLock.result().release();
                        }
                        return succeededFuture(retryModels);
                    } else {
                        // ignore the lockResult, worst case, we are reading the serviceMetadata twice
                        return reloadModels().onComplete(loadedModels -> {
                            if (asyncLocalLock.succeeded()) {
                                asyncLocalLock.result().release();
                            }
                        });
                    }
                });
    }

    /**
     * Either returns a future to the buffered model instance for one schema namespace, or tries to load / build the
     * model definition files (from file system and / or from the class path) first, before returning the metadata.
     *
     * @see #getSharedModel(NeonBee, String)
     * @deprecated use {@link #getSharedModel(NeonBee, String)} instead
     * @param vertx           the {@link Vertx} instance
     * @param schemaNamespace The name of the schema namespace
     * @return a succeeded {@link Future} to a specific EntityModel with a given schema namespace, or a failed future in
     *         case no models could be loaded or no model matching the schema namespace could be found
     */
    @Deprecated(forRemoval = true)
    public static Future<EntityModel> getSharedModel(Vertx vertx, String schemaNamespace) {
        return getSharedModel(NeonBee.get(vertx), schemaNamespace);
    }

    /**
     * Either returns a future to the buffered model instance for one schema namespace, or tries to load / build the
     * model definition files (from file system and / or from the class path) first, before returning the metadata.
     *
     * @see #getSharedModel(String)
     * @deprecated use {@code neonBee.getModelManager().getSharedModel(schemaNamespace)} instead
     * @param neonBee         the {@link NeonBee} instance
     * @param schemaNamespace The name of the schema namespace
     * @return a succeeded {@link Future} to a specific EntityModel with a given schema namespace, or a failed future in
     *         case no models could be loaded or no model matching the schema namespace could be found
     */
    @Deprecated(forRemoval = true)
    public static Future<EntityModel> getSharedModel(NeonBee neonBee, String schemaNamespace) {
        return neonBee.getModelManager().getSharedModel(schemaNamespace);
    }

    /**
     * Either returns a future to the buffered model instance for one schema namespace, or tries to load / build the
     * model definition files (from file system and / or from the class path) first, before returning the metadata.
     *
     * @param schemaNamespace The name of the schema namespace
     * @return a succeeded {@link Future} to a specific EntityModel with a given schema namespace, or a failed future in
     *         case no models could be loaded or no model matching the schema namespace could be found
     */
    public Future<EntityModel> getSharedModel(String schemaNamespace) {
        return getSharedModels().compose(models -> {
            EntityModel model = models.get(schemaNamespace);
            return model != null ? succeededFuture(model)
                    : failedFuture(new NoSuchElementException(
                            "Cannot find data model for schema namespace " + schemaNamespace));
        });
    }

    /**
     * Returns a future to a freshly loaded EntityModel instance and updates the globally shared instance. Please note
     * that all models files will be reloaded (from file system and / or class path). This method will also update the
     * buffered models.
     *
     * @see #reloadModels(NeonBee)
     * @deprecated use {@link #reloadModels(NeonBee)} instead
     * @param vertx the {@link Vertx} instance
     * @return a {@link Future} to a map from schema namespace to EntityModel
     */
    @Deprecated(forRemoval = true)
    public static Future<Map<String, EntityModel>> reloadModels(Vertx vertx) {
        return reloadModels(NeonBee.get(vertx));
    }

    /**
     * Returns a future to a freshly loaded EntityModel instance and updates the globally shared instance. Please note
     * that all models files will be reloaded (from file system and / or class path). This method will also update the
     * buffered models.
     *
     * @see #reloadModels()
     * @deprecated use {@code neonBee.getModelManager().reloadModels()} instead
     * @param neonBee the {@link NeonBee} instance
     * @return a {@link Future} to a map from schema namespace to EntityModel
     */
    @Deprecated(forRemoval = true)
    public static Future<Map<String, EntityModel>> reloadModels(NeonBee neonBee) {
        return neonBee.getModelManager().reloadModels();
    }

    /**
     * Returns a future to a freshly loaded EntityModel instance and updates the globally shared instance. Please note
     * that all models files will be reloaded (from file system and / or class path). This method will also update the
     * buffered models.
     *
     * @return a {@link Future} to a map from schema namespace to EntityModel
     */
    public Future<Map<String, EntityModel>> reloadModels() {
        LOGGER.info("Reload models");
        return EntityModelLoader.load(neonBee.getVertx(), externalModelDefinitions).onSuccess(models -> {
            bufferedModels = Collections.unmodifiableMap(models);

            // publish the event local only! models must be present locally on very instance in a cluster!
            neonBee.getVertx().eventBus().publish(EVENT_BUS_MODELS_LOADED_ADDRESS, null, LOCAL_DELIVERY);

            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Reloading models succeeded, size of new buffered models map {}", models.size());
            }
        }).onFailure(throwable -> {
            LOGGER.error("Failed to reload models", throwable);
        }).map(Functions.forSupplier(() -> bufferedModels));
    }

    /**
     * Register an external model. External models will be loaded alongside any models that are in the class path or in
     * the models directory.
     *
     * @see #registerModels(NeonBee, EntityModelDefinition)
     * @deprecated use {@link EntityModelDefinition#EntityModelDefinition(Map, Map)} to initialize a
     *             {@link EntityModelDefinition} and use {@link #registerModels(NeonBee, EntityModelDefinition)} instead
     * @param vertx                      the {@link Vertx} instance
     * @param modelIdentifier            the definition identifier of this model
     * @param csnModelDefinitions        the CSN model definitions
     * @param associatedModelDefinitions the associated model definitions, such as EDMX files
     * @return a {@link Future} to a map from schema namespace to EntityModel
     */
    @Deprecated(forRemoval = true)
    public static Future<Map<String, EntityModel>> registerModels(Vertx vertx, String modelIdentifier,
            Map<String, byte[]> csnModelDefinitions, Map<String, byte[]> associatedModelDefinitions) {
        return registerModels(NeonBee.get(vertx), EXTERNAL_MODEL_IDENTIFIERS
                .computeIfAbsent(NeonBee.get(vertx), Functions.forSupplier(ConcurrentHashMap::new))
                .computeIfAbsent(modelIdentifier,
                        newIdentifier -> new EntityModelDefinition(csnModelDefinitions, associatedModelDefinitions)));
    }

    /**
     * Register an external model. External models will be loaded alongside any models that are in the class path or in
     * the models directory.
     *
     * @see #registerModels(EntityModelDefinition)
     * @deprecated use {@code neonBee.getModelManager().registerModels(modelDefinition)} instead
     * @param neonBee         the {@link NeonBee} instance
     * @param modelDefinition the entity model definition
     * @return a {@link Future} to a map from schema namespace to EntityModel
     */
    @Deprecated(forRemoval = true)
    public static Future<Map<String, EntityModel>> registerModels(NeonBee neonBee,
            EntityModelDefinition modelDefinition) {
        return neonBee.getModelManager().registerModels(modelDefinition);
    }

    /**
     * Register an external model. External models will be loaded alongside any models that are in the class path or in
     * the models directory.
     *
     * @param modelDefinition the entity model definition
     * @return a {@link Future} to a map from schema namespace to EntityModel
     */
    public Future<Map<String, EntityModel>> registerModels(EntityModelDefinition modelDefinition) {
        return modelDefinition != null && !modelDefinition.getCSNModelDefinitions().isEmpty()
                && externalModelDefinitions.add(modelDefinition) ? reloadModels() : getSharedModels();
    }

    /**
     * Unregisters a given external model and reload the models.
     *
     * @see #unregisterModels(NeonBee, EntityModelDefinition)
     * @deprecated use {@link #unregisterModels(NeonBee, EntityModelDefinition)} instead
     * @param vertx           the {@link Vertx} instance
     * @param modelIdentifier the model definition identifier to remove
     * @return a {@link Future} to a map from schema namespace to EntityModel
     */
    @Deprecated(forRemoval = true)
    public static Future<Map<String, EntityModel>> unregisterModels(Vertx vertx, String modelIdentifier) {
        NeonBee neonBee = NeonBee.get(vertx);
        Map<String, EntityModelDefinition> identifiers = EXTERNAL_MODEL_IDENTIFIERS.get(neonBee);
        return identifiers != null ? unregisterModels(neonBee, identifiers.get(modelIdentifier))
                : getSharedModels(neonBee);
    }

    /**
     * Unregisters a given external model and reload the models.
     *
     * @see #unregisterModels(EntityModelDefinition)
     * @deprecated use {@code neonBee.getModelManager().unregisterModels(modelDefinition)}
     * @param neonBee         the {@link NeonBee} instance
     * @param modelDefinition the model definition to remove
     * @return a {@link Future} to a map from schema namespace to EntityModel
     */
    @Deprecated(forRemoval = true)
    public static Future<Map<String, EntityModel>> unregisterModels(NeonBee neonBee,
            EntityModelDefinition modelDefinition) {
        return neonBee.getModelManager().unregisterModels(modelDefinition);
    }

    /**
     * Unregisters a given external model and reload the models.
     *
     * @param modelDefinition the model definition to remove
     * @return a {@link Future} to a map from schema namespace to EntityModel
     */
    public Future<Map<String, EntityModel>> unregisterModels(EntityModelDefinition modelDefinition) {
        return externalModelDefinitions.remove(modelDefinition) ? reloadModels() : getSharedModels();
    }
}
