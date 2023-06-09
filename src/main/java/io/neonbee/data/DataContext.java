package io.neonbee.data;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.vertx.core.json.JsonObject;

@SuppressWarnings("TypeParameterUnusedInFormals")
public interface DataContext {
    /**
     * Returns the correlation id of this request.
     *
     * @return the correlation id
     */
    String correlationId();

    /**
     * Returns the bearer token of the user, in case authentication succeeded, null otherwise.
     *
     * @return the bearer token
     */
    String bearerToken();

    /**
     * Get the authenticated user principal, if any, or null otherwise. What this actually returns depends on which
     * authentication stack was used. For a simple user/password based authentication, it's likely to contain a JSON
     * object with the following structure:
     *
     * <code>
     *   {
     *     "username", "kristian"
     *   }
     * </code>
     *
     * @return the JSON representation user principal, or null if no user is authenticated
     */
    JsonObject userPrincipal();

    /**
     * Arbitrary data of this context, which is passed through each processing step of the verticle.
     * <p>
     * Important consideration: The data of this context might behave non-deterministic, in case the same attributes are
     * set in multiple dependent data verticle. As soon as the processing returns to a given data verticle, the data
     * returned by that verticle will be merged into the data map. This means if multiple data verticle set the same
     * attribute only the attribute of the last verticle returned will be set to the context map.
     * <p>
     * Might be changed in future, with a more sophisticated merging logic of {@link #mergeData(Map)}.
     *
     * @return all the context data as a map, as expected from {@link JsonObject#getMap()}
     */
    Map<String, Object> data();

    /**
     * Sets all the context data.
     *
     * @param data the data, must be compatible to {@link JsonObject#JsonObject(Map)}
     * @return a reference to this DataContext for chaining
     */
    DataContext setData(Map<String, Object> data);

    /**
     * Merges the data of a given map into the existing context data map.
     * <p>
     * Note: This method does not perform a deep merge operation, but overrides already existing elements w/o merging
     *
     * @param data the data to merge, must be compatible to {@link JsonObject#JsonObject(Map)}
     * @return a reference to this DataContext for chaining
     */
    DataContext mergeData(Map<String, Object> data);

    /**
     * Put some arbitrary data in the context. This will be available in any data verticle that receive the context.
     *
     * @param key   the key for the data
     * @param value the data, must be compatible to {@link JsonObject#put(String, Object)} and should be primitive,
     *              otherwise it can't be serialized via the event bus by default.
     * @return a reference to this DataContext for chaining
     */
    DataContext put(String key, Object value);

    /**
     * Get some data from the context. The data is available in any data verticle that receive the context.
     *
     * @param key the key for the data
     * @param <T> the type of the parameter to be returned
     * @return the data, as expected from {@link JsonObject#getValue(String)}
     */
    <T> T get(String key);

    /**
     * Remove some data from the context. The data is available in any data verticle that receive the context.
     *
     * @param key the key for the data
     * @param <T> the type of the parameter to be returned
     * @return the previous data associated with the key, as expected from {@link JsonObject#remove(String)}
     */
    <T> T remove(String key);

    /**
     * Arbitrary response data of this context, which is passed backwards from callee to caller.
     *
     * @return all the context response data as a map.
     */
    Map<String, Object> responseData();

    /**
     * Merges a given map from a response into the current response data map.
     * <p>
     * Note: This method does not perform a deep merge operation, but overrides already existing elements w/o merging
     *
     * @param data the response data to merge, must be compatible to {@link JsonObject#JsonObject(Map)}
     * @return a reference to this DataContext for chaining
     */
    DataContext mergeResponseData(Map<String, Object> data);

    /**
     * Returns a map between {@link DataRequest} and received data map. Received data are response data returned by the
     * verticles, which are called by the current data verticle.
     *
     * @return a map between {@link DataRequest} and received data map
     */
    Map<DataRequest, Map<String, Object>> receivedData();

    /**
     * Sets the response data map for all {@link DataRequest}s.
     *
     * @param map a map between {@link DataRequest} and received response data for the request.
     * @return a reference to this DataContext for chaining
     */
    DataContext setReceivedData(Map<DataRequest, Map<String, Object>> map);

    /**
     * Retrieves the received data map for one {@link DataRequest}.
     *
     * @param dataRequest a data request
     * @return response data map for this request
     */
    Map<String, Object> findReceivedData(DataRequest dataRequest);

    /**
     * Retrieves the received data map for one verticle identified by the verticle name.
     *
     * @param qualifiedName qualified name of a Received verticle
     * @return first received data for the request verticle name, if available
     */
    Optional<Map<String, Object>> findFirstReceivedData(String qualifiedName);

    /**
     * Retrieves all received data maps for all verticles identified by the name.
     *
     * @param qualifiedName qualified name of a verticle
     * @return all received data maps from verticles with the name
     */
    List<Map<String, Object>> findAllReceivedData(String qualifiedName);

    /**
     * Propagate the received data from all invoked verticles into the current verticle's context.
     */
    void propagateReceivedData();

    /**
     * Returns the path of verticle this context was involved in.
     *
     * @return the path of the verticle called
     */
    Iterator<DataVerticleCoordinate> path();

    /**
     * Returns the complete path of verticle this context was involved in as a concatenated string.
     *
     * @return the path representation as a string
     */
    String pathAsString();

    /**
     * Returns the value of the session Id as a string.
     *
     * @return the session Id as String
     */
    String sessionId();

    /**
     * Copy the current {@link DataContext}. This is necessary, since one data verticle might branch to multiple other
     * verticle in parallel. The {@link DataContext}es of the parallel branches must be isolated from each other.
     *
     * @return A new object of the data context
     */
    DataContext copy();

    /**
     * Updates the timestamp on response.
     */
    void updateResponseTimestamp();

    interface DataVerticleCoordinate {

        /**
         * Returns the timestamp when the request is received.
         *
         * @return the timestamp
         */
        String getRequestTimestamp();

        /**
         * Returns the timestamp when the response is received.
         *
         * @return the timestamp
         */
        String getResponseTimestamp();

        /**
         * Returns the qualified name of the verticle coordinate.
         *
         * @return the qualified name
         */
        String getQualifiedName();

        /**
         * Returns the ip address of the cluster node where the verticle coordinate was created.
         *
         * @return the ip address
         */
        String getIpAddress();

        /**
         * Returns the deployment id of the verticle or null if unknown.
         *
         * @return the deployment id
         */
        String getDeploymentId();
    }
}
