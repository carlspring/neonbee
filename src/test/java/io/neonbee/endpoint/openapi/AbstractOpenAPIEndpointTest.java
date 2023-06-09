package io.neonbee.endpoint.openapi;

import static com.google.common.truth.Truth.assertThat;
import static io.neonbee.test.helper.ResourceHelper.TEST_RESOURCES;
import static io.vertx.core.Future.succeededFuture;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.neonbee.config.EndpointConfig;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.openapi.router.RouterBuilder;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.openapi.contract.OpenAPIContract;
import io.vertx.openapi.contract.OpenAPIContractException;

@ExtendWith(VertxExtension.class)
class AbstractOpenAPIEndpointTest {
    private static final Path CONTRACT_PATH = TEST_RESOURCES.resolveRelated("petstore.json");

    private static final Path INVALID_CONTRACT_PATH = TEST_RESOURCES.resolveRelated("petstore_invalid.json");

    @Test
    @DisplayName("createEndpointRouter should succeed")
    void testCreateEndpointRouter(Vertx vertx, VertxTestContext testContext) {
        Checkpoint methodsCheckpoint = testContext.checkpoint(2);
        Checkpoint succeedingCheckpoint = testContext.checkpoint();
        DummyOpenAPIEndpoint dummyEndpoint = new DummyOpenAPIEndpoint() {
            @Override
            protected Future<OpenAPIContract> getOpenAPIContract(Vertx vertx, JsonObject config) {
                methodsCheckpoint.flag();
                return OpenAPIContract.from(vertx, CONTRACT_PATH.toString());
            }

            @Override
            protected Future<Router> createRouter(Vertx vertx, RouterBuilder routerBuilder) {
                methodsCheckpoint.flag();
                return super.createRouter(vertx, routerBuilder);
            }
        };

        dummyEndpoint.createEndpointRouter(vertx, null, null)
                .onComplete(testContext.succeeding(router -> succeedingCheckpoint.flag()));
    }

    @Test
    @DisplayName("createEndpointRouter should fail")
    void testCreateEndpointRouterFailed(Vertx vertx, VertxTestContext testContext) {
        Checkpoint methodCheckpoint = testContext.checkpoint();
        Checkpoint failingCheckpoint = testContext.checkpoint();
        DummyOpenAPIEndpoint dummyEndpoint = new DummyOpenAPIEndpoint() {
            @Override
            protected Future<OpenAPIContract> getOpenAPIContract(Vertx vertx, JsonObject config) {
                methodCheckpoint.flag();
                return OpenAPIContract.from(vertx, INVALID_CONTRACT_PATH.toString());
            }
        };

        dummyEndpoint.createEndpointRouter(vertx, null, null)
                .onComplete(testContext.failing(t -> testContext.verify(() -> {
                    assertThat(t).isInstanceOf(OpenAPIContractException.class);
                    assertThat(t).hasMessageThat().isEqualTo("The passed OpenAPI contract is invalid.");
                    failingCheckpoint.flag();
                })));
    }

    private abstract static class DummyOpenAPIEndpoint extends AbstractOpenAPIEndpoint {

        @Override
        public EndpointConfig getDefaultConfig() {
            return null;
        }

        @Override
        protected Future<Router> createRouter(Vertx vertx, RouterBuilder routerBuilder) {
            return succeededFuture(routerBuilder.createRouter());
        }
    }

}
