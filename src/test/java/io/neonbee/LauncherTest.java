package io.neonbee;

import static com.google.common.truth.Truth.assertThat;
import static io.neonbee.Launcher.parseCommandLine;
import static io.neonbee.Launcher.startNeonBee;
import static io.neonbee.cluster.ClusterManagerFactory.HAZELCAST_FACTORY;
import static io.neonbee.cluster.ClusterManagerFactory.INFINISPAN_FACTORY;
import static io.neonbee.test.helper.FileSystemHelper.createTempDirectory;
import static io.neonbee.test.helper.SystemHelper.withEnvironment;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.MockedStatic;

import io.neonbee.Launcher.EnvironmentAwareCommandLine;
import io.neonbee.cluster.ClusterManagerFactory;
import io.neonbee.config.NeonBeeConfig;
import io.neonbee.test.helper.FileSystemHelper;
import io.vertx.core.Future;
import io.vertx.core.cli.Argument;
import io.vertx.core.cli.CLI;
import io.vertx.core.cli.CLIException;
import io.vertx.core.cli.CommandLine;
import io.vertx.core.cli.MissingValueException;
import io.vertx.core.cli.Option;
import io.vertx.core.cli.annotations.CLIConfigurator;
import io.vertx.core.cli.impl.DefaultCommandLine;

@Isolated("some tests modify the global ProcessEnvironment using SystemHelper.withEnvironment")
class LauncherTest {
    private static Path tempDirPath;

    private String[] args;

    private static String workDir;

    @BeforeAll
    static void setUp() throws IOException {
        tempDirPath = createTempDirectory();
        workDir = tempDirPath.toAbsolutePath().toString();
    }

    @AfterAll
    static void tearDown() {
        FileSystemHelper.deleteRecursiveBlocking(tempDirPath);
    }

    @Test
    @DisplayName("should throw an error, if working directory value is not passed")
    void throwErrorIfWorkingDirValueIsEmpty() {
        args = new String[] { "-cwd" };
        MissingValueException exception = assertThrows(MissingValueException.class, this::parseArgs);
        assertThat(exception.getMessage()).isEqualTo("The option 'working-directory' requires a value");
    }

    @Test
    @DisplayName("should have NeonBeeProfile ALL by default")
    void testDefaultActiveProfiles() {
        args = new String[] {};
        assertThat(parseArgs().getActiveProfiles()).containsExactly(NeonBeeProfile.ALL);
    }

    @Test
    @DisplayName("should have WEB as active profile")
    void testSingleActiveProfiles() {
        args = new String[] { "-ap", "WEB" };
        assertThat(parseArgs().getActiveProfiles()).containsExactly(NeonBeeProfile.WEB);
    }

    @Test
    @DisplayName("should have ALL and WEB as active profiles")
    void testMultiStringActiveProfiles() {
        args = new String[] { "-ap", "WEB,ALL" };
        assertThat(parseArgs().getActiveProfiles()).containsExactly(NeonBeeProfile.WEB, NeonBeeProfile.ALL);
    }

    @Test
    @DisplayName("should have no NeonBeeProfile if empty")
    void testMultiValueActiveProfiles() {
        args = new String[] { "-ap", "WEB", "ALL" };
        assertThat(parseArgs().getActiveProfiles()).containsExactly(NeonBeeProfile.WEB, NeonBeeProfile.ALL);
    }

    @Test
    @DisplayName("should have no NeonBeeProfile if empty")
    void testEmptyActiveProfiles() {
        args = new String[] { "-ap", "" };
        assertThat(parseArgs().getActiveProfiles()).isEmpty();
    }

    @Test
    @DisplayName("should throw an error, if instance-name is empty")
    void throwErrorIfInstanceNameIsEmpty() {
        args = new String[] { "-cwd", workDir, "-name", "" };
        CLIException exception = assertThrows(CLIException.class, this::parseArgs);
        assertThat(exception.getMessage()).isEqualTo("Cannot inject value for option 'instance-name'");
    }

    @Test
    @DisplayName("should throw error, if the passed value is other than integer for worker pool size")
    void validateWorkerPoolSizeValue() {
        args = new String[] { "-cwd", workDir, "-name", "Hodor", "-wps", "hodor" };
        CLIException exception = assertThrows(CLIException.class, this::parseArgs);
        assertThat(exception.getMessage()).isEqualTo("Cannot inject value for option 'worker-pool-size'");
    }

    @Test
    @DisplayName("should throw error, if the passed value is other than integer for event loop pool size")
    void validateEventLoopPoolSizeValue() {
        args = new String[] { "-cwd", workDir, "-name", "Hodor", "-elps", "hodor" };
        CLIException exception = assertThrows(CLIException.class, this::parseArgs);
        assertThat(exception.getMessage()).isEqualTo("Cannot inject value for option 'event-loop-pool-size'");
    }

    @Test
    @DisplayName("should generate expected neonbee options")
    void testExpectedNeonBeeOptions() throws Exception {
        args = new String[] { "-cwd", workDir, "-name", "Hodor", "-wps", "2", "-elps", "2", "-no-cp", "-no-jobs",
                "-port", "9000", "-mjp", "path1", "path2", "path3" + File.pathSeparator + "path4" };
        assertNeonBeeOptions();

        args = new String[] {};
        assertThat(parseArgs().getServerPort()).isNull();
    }

    @Test
    @DisplayName("should generate expected neonbee options")
    void testExpectedNeonBeeEnvironmentOptions() throws Exception {
        args = new String[] {};
        Map<String, String> envMap = Map.of("NEONBEE_WORKING_DIR", workDir, "NEONBEE_INSTANCE_NAME", "Hodor",
                "NEONBEE_WORKER_POOL_SIZE", "2", "NEONBEE_EVENT_LOOP_POOL_SIZE", "2", "NEONBEE_IGNORE_CLASS_PATH",
                "true", "NEONBEE_DISABLE_JOB_SCHEDULING", "true", "NEONBEE_SERVER_PORT", "9000",
                "NEONBEE_MODULE_JAR_PATHS",
                "path1" + File.pathSeparator + "path2" + File.pathSeparator + "path3" + File.pathSeparator + "path4");
        withEnvironment(envMap, this::assertNeonBeeOptions);
    }

    @Test
    @DisplayName("should generate expected clustered neonbee options")
    void testExpectedClusterNeonBeeOptions() throws Exception {
        BiConsumer<String, ClusterManagerFactory> assertClusteredOptions = (config, cmf) -> {
            NeonBeeOptions neonBeeOptions = parseArgs();
            assertThat(neonBeeOptions.getClusterPort()).isEqualTo(10000);
            assertThat(neonBeeOptions.isClustered()).isTrue();
            assertThat(neonBeeOptions.getClusterConfig()).isEqualTo(config);
            assertThat(neonBeeOptions.getClusterManager()).isEqualTo(cmf);

        };

        // default
        args = new String[] { "-cwd", workDir, "-cl", "-clp", "10000" };
        assertClusteredOptions.accept(null, HAZELCAST_FACTORY);

        args = new String[] {};
        Map<String, String> envMapDefault =
                Map.of("NEONBEE_WORKING_DIR", workDir, "NEONBEE_CLUSTERED", "true", "NEONBEE_CLUSTER_PORT", "10000");
        withEnvironment(envMapDefault, () -> assertClusteredOptions.accept(null, HAZELCAST_FACTORY));

        // Hazelcast
        args = new String[] { "-cwd", workDir, "-cl", "-cm", "hazelcast", "-cc", "hazelcast-local.xml", "-clp",
                "10000" };
        assertClusteredOptions.accept("hazelcast-local.xml", HAZELCAST_FACTORY);

        args = new String[] {};
        Map<String, String> envMapHazelcast =
                Map.of("NEONBEE_WORKING_DIR", workDir, "NEONBEE_CLUSTERED", "true", "NEONBEE_CLUSTER_MANAGER",
                        "hazelcast", "NEONBEE_CLUSTER_CONFIG", "hazelcast-local.xml", "NEONBEE_CLUSTER_PORT", "10000");
        withEnvironment(envMapHazelcast, () -> assertClusteredOptions.accept("hazelcast-local.xml", HAZELCAST_FACTORY));

        // Infinispan
        args = new String[] { "-cwd", workDir, "-cl", "-cm", "infinispan", "-cc", "infinispan-local.xml", "-clp",
                "10000" };
        assertClusteredOptions.accept("infinispan-local.xml", INFINISPAN_FACTORY);

        args = new String[] {};
        Map<String, String> envMapInfinispan = Map.of("NEONBEE_WORKING_DIR", workDir, "NEONBEE_CLUSTERED", "true",
                "NEONBEE_CLUSTER_MANAGER", "infinispan", "NEONBEE_CLUSTER_CONFIG", "infinispan-local.xml",
                "NEONBEE_CLUSTER_PORT", "10000");
        withEnvironment(envMapInfinispan,
                () -> assertClusteredOptions.accept("infinispan-local.xml", INFINISPAN_FACTORY));
    }

    @Test
    @DisplayName("test EnvironmentAwareCommandLine")
    void testEnvironmentAwareCommandLine() {
        CLI cliMock = mock(CLI.class);
        Option option = new Option().setLongName("option");
        when(cliMock.getOption(any())).thenReturn(option);
        Option flag = new Option().setLongName("flag").setFlag(true);
        when(cliMock.getOption("flag")).thenReturn(flag);
        Argument argument = new Argument();
        when(cliMock.getArgument(any())).thenReturn(argument);
        when(cliMock.getArgument(anyInt())).thenReturn(argument);

        CommandLine commandLineMock = mock(DefaultCommandLine.class);
        when(commandLineMock.cli()).thenReturn(cliMock);

        EnvironmentAwareCommandLine commandLine = spy(new EnvironmentAwareCommandLine(commandLineMock));

        clearInvocations(commandLine);
        commandLine.isFlagEnabled("flag");
        verify(commandLine).hasEnvArg(any());

        clearInvocations(commandLine);
        commandLine.isSeenInCommandLine(option);
        verify(commandLine).hasEnvArg(any());

        clearInvocations(commandLine);
        commandLine.getRawValueForOption(option);
        verify(commandLine).hasEnvArg(option);

        clearInvocations(commandLine);
        commandLine.getRawValuesForOption(option);
        verify(commandLine).hasEnvArg(option);
    }

    @Test
    void testStartNeonBeeConfigPath() {
        NeonBeeOptions.Mutable options = new NeonBeeOptions.Mutable();
        Path tempDirectory = Path.of("some", "test", "path").toAbsolutePath();
        options.setWorkingDirectory(tempDirectory);

        try (MockedStatic<NeonBeeConfig> staticNbc = mockStatic(NeonBeeConfig.class)) {
            staticNbc.when(() -> NeonBeeConfig.load(any(), any()))
                    .thenReturn(Future.failedFuture("fail starting NeonBee"));
            startNeonBee(options);
            staticNbc.verify(() -> NeonBeeConfig.load(any(), eq(tempDirectory.resolve("config"))));
        }
    }

    private void assertNeonBeeOptions() {
        NeonBeeOptions neonBeeOptions = parseArgs();
        assertThat(neonBeeOptions.getInstanceName()).isEqualTo("Hodor");
        assertThat(neonBeeOptions.getWorkerPoolSize()).isEqualTo(2);
        assertThat(neonBeeOptions.getEventLoopPoolSize()).isEqualTo(2);
        assertThat(neonBeeOptions.shouldIgnoreClassPath()).isTrue();
        assertThat(neonBeeOptions.shouldDisableJobScheduling()).isTrue();
        assertThat(neonBeeOptions.getServerPort()).isEqualTo(9000);
        assertThat(neonBeeOptions.getModuleJarPaths()).containsExactly(Path.of("path1"), Path.of("path2"),
                Path.of("path3"), Path.of("path4"));
    }

    private NeonBeeOptions parseArgs() {
        NeonBeeOptions.Mutable options = new NeonBeeOptions.Mutable();
        CLIConfigurator.inject(parseCommandLine(args), options);
        return options;
    }
}
