/*
 * JBoss, Home of Professional Open Source.
 *
 * Copyright 2018 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.core.test.embedded;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.jboss.logging.Logger;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.wildfly.core.embedded.Configuration;
import org.wildfly.core.embedded.Configuration.LoggerHint;
import org.wildfly.core.embedded.EmbeddedManagedProcess;
import org.wildfly.core.embedded.EmbeddedProcessFactory;
import org.wildfly.core.embedded.EmbeddedProcessStartException;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@SuppressWarnings("UseOfSystemOutOrSystemErr")
public abstract class LoggingTestCase extends AbstractTestCase {
    private static final PrintStream DFT_STDOUT = System.out;
    private static final PrintStream DFT_STDERR = System.err;
    private static final ByteArrayOutputStream STDOUT = new ByteArrayOutputStream();
    private static final ByteArrayOutputStream STDERR = new ByteArrayOutputStream();

    private static final String HANDLER_NAME = "FILE-TEST";
    private static final String ROOT_LOGGER_NAME = "ROOT";

    private static final PathElement HOST_PREFIX_ELEMENT = PathElement.pathElement("profile", "default");
    private static final PathElement LOGGING_SUBSYSTEM_ELEMENT = PathElement.pathElement(ClientConstants.SUBSYSTEM, "logging");
    private static final PathElement ROOT_LOGGER_ELEMENT = PathElement.pathElement("root-logger", ROOT_LOGGER_NAME);
    private static final PathElement FILE_HANDLER_ELEMENT = PathElement.pathElement("file-handler", HANDLER_NAME);

    private static final PathAddress STANDALONE_SUBSYSTEM_PATH = PathAddress.pathAddress(LOGGING_SUBSYSTEM_ELEMENT);
    private static final PathAddress HOST_SUBSYSTEM_PATH = PathAddress.pathAddress(HOST_PREFIX_ELEMENT, LOGGING_SUBSYSTEM_ELEMENT);


    @BeforeClass
    public static void replaceStreams() {
        System.setOut(new PrintStream(STDOUT));
        System.setErr(new PrintStream(STDERR));
    }

    @AfterClass
    public static void restoreStreams() {
        System.setOut(DFT_STDOUT);
        System.setErr(DFT_STDERR);
    }

    @After
    public void clearStreams() throws IOException {
        if (STDOUT.size() > 0) {
            DFT_STDOUT.write(STDOUT.toByteArray());
            DFT_STDOUT.flush();
            STDOUT.reset();
        }
        if (STDERR.size() > 0) {
            DFT_STDERR.write(STDERR.toByteArray());
            DFT_STDERR.flush();
            STDERR.reset();
        }
    }

    protected void testStandalone(final LoggerHint loggerHint, final String filename, final String expectedPrefix) throws Exception {
        testStandalone(loggerHint, filename, expectedPrefix, true);
    }

    protected void testStandalone(final LoggerHint loggerHint, final String filename, final String expectedPrefix,
                                  final boolean validateConsoleOutput) throws Exception {
        // We need to explicitly override the JBoss Logging provider property as it's set in surefire
        if (loggerHint != null) {
            System.setProperty("org.jboss.logging.provider", loggerHint.getProviderCode());
        }
        final Configuration configuration = Environment.createConfigBuilder()
                .setLoggerHint(loggerHint)
                .build();
        test(EmbeddedProcessFactory.createStandaloneServer(configuration), filename, expectedPrefix,
                STANDALONE_CHECK, validateConsoleOutput, STANDALONE_SUBSYSTEM_PATH);
    }

    protected void testHostController(final LoggerHint loggerHint, final String filename, final String expectedPrefix) throws Exception {
        testHostController(loggerHint, filename, expectedPrefix, true);
    }

    protected void testHostController(final LoggerHint loggerHint, final String filename, final String expectedPrefix,
                                      final boolean validateConsoleOutput) throws Exception {
        // We need to explicitly override the JBoss Logging provider property as it's set in surefire
        if (loggerHint != null) {
            System.setProperty("org.jboss.logging.provider", loggerHint.getProviderCode());
        }
        final Configuration configuration = Environment.createConfigBuilder()
                .setLoggerHint(loggerHint)
                .build();
        test(EmbeddedProcessFactory.createHostController(configuration), filename, expectedPrefix,
                HOST_CONTROLLER_CHECK, validateConsoleOutput, HOST_SUBSYSTEM_PATH);
    }

    private void test(final EmbeddedManagedProcess server, final String filename, final String expectedPrefix,
                      final Function<EmbeddedManagedProcess, Boolean> check, final boolean validateConsoleOutput,
                      final PathAddress loggingSubsystemPath) throws EmbeddedProcessStartException,
            IOException, TimeoutException, InterruptedException {
        final Path logFile = Environment.LOG_DIR.resolve(filename);
        try {
            startAndWaitFor(server, check);
            // Check for existence of the log file
            Assert.assertTrue(String.format("Expected file \"%s\" to exist", logFile), Files.exists(logFile));
            // Validate lines of log file according to expected message prefix
            checkLinesOfLogFile(logFile, expectedPrefix);
            // Check that stdout and stderr are empty if console output validation is on
            checkStdoutStderrStreams(validateConsoleOutput);
            // Try to configure logging subsystem and verify user configuration is not overridden.
            configureFileHandler(server, loggingSubsystemPath, logFile);
            Logger.getLogger(this.getClass()).info("Test message");
            checkLinesOfLogFile(logFile, expectedPrefix);
            checkStdoutStderrStreams(validateConsoleOutput);
        } finally {
            removeFileHandler(server, loggingSubsystemPath);
            server.stop();
        }
    }

    private void checkLinesOfLogFile(final Path logFile, final String expectedPrefix) throws IOException {
        final List<String> invalidLines = new ArrayList<>();
        if (expectedPrefix == null) {
            // Since no prefix is expected just check if the file is empty
            try (BufferedReader reader = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
                Assert.assertNotNull("Log file should have at least one line: " + logFile, reader.readLine());
            }
        } else {
            final List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            Assert.assertFalse("No lines found in file " + logFile, lines.isEmpty());
            for (String line : lines) {
                if (!line.startsWith(expectedPrefix)) {
                    invalidLines.add(line);
                }
            }
            if (!invalidLines.isEmpty()) {
                final StringBuilder msg = new StringBuilder(64);
                msg.append("The following lines did not contain the prefix \"")
                        .append(expectedPrefix)
                        .append('"')
                        .append(System.lineSeparator());
                for (String line : invalidLines) {
                    msg.append('\t').append(line).append(System.lineSeparator());
                }
                Assert.fail(msg.toString());
            }
        }
    }

    private void checkStdoutStderrStreams(boolean validateConsoleOutput) {
        if (validateConsoleOutput) {
            System.out.flush();
            System.err.flush();
            Assert.assertEquals(String.format("The following messages were found on the console: %n%s", STDOUT.toString()), 0, STDOUT.size());
            Assert.assertEquals(String.format("The following messages were found on the error console: %n%s", STDERR.toString()), 0, STDERR.size());
        }
    }

    private void configureFileHandler(final EmbeddedManagedProcess server, final PathAddress loggingSubsystemAddress,
                                      final Path logFile) throws IOException {
        ModelControllerClient client = server.getModelControllerClient();

        ModelNode address = loggingSubsystemAddress.append(FILE_HANDLER_ELEMENT).toModelNode();
        ModelNode op = Operations.createAddOperation(address);
        op.get("level").set("INFO");
        op.get("formatter").set("[test-prefix] %d{HH:mm:ss,SSS} %-5p [%c] (%t) %s%e%n");
        final ModelNode fileNode = op.get(ClientConstants.FILE).setEmptyObject();
        fileNode.get(ClientConstants.PATH).set(logFile.toString());
        executeOperation(client, op);

        address = loggingSubsystemAddress.append(ROOT_LOGGER_ELEMENT).toModelNode();
        op = Operations.createOperation("add-handler", address);
        op.get(ClientConstants.NAME).set(HANDLER_NAME);
        executeOperation(client, op);
    }

    private void removeFileHandler(final EmbeddedManagedProcess server, final PathAddress loggingSubsystemPath)
            throws IOException {
        final ModelControllerClient client = server.getModelControllerClient();

        ModelNode address = loggingSubsystemPath.append(ROOT_LOGGER_ELEMENT).toModelNode();
        ModelNode op = Operations.createOperation("remove-handler", address);
        op.get(ClientConstants.NAME).set(HANDLER_NAME);
        executeOperation(client, op);

        address = loggingSubsystemPath.append(FILE_HANDLER_ELEMENT).toModelNode();
        op = Operations.createRemoveOperation(address);
        executeOperation(client, op);
    }

    protected static boolean isIbmJdk() {
        return System.getProperty("java.vendor").startsWith("IBM");
    }
}
