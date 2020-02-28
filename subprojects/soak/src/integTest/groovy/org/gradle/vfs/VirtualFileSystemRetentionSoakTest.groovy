/*
 * Copyright 2020 the original author or authors.
 *
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
 */

package org.gradle.vfs

import org.gradle.integtests.fixtures.VfsRetentionFixture
import org.gradle.integtests.fixtures.daemon.DaemonIntegrationSpec
import org.gradle.internal.os.OperatingSystem
import org.gradle.internal.vfs.watch.FileWatcherRegistry
import org.gradle.soak.categories.SoakTest
import org.gradle.test.fixtures.file.TestFile
import org.junit.experimental.categories.Category
import spock.lang.Ignore
import spock.lang.Unroll

@Category(SoakTest)
class VirtualFileSystemRetentionSoakTest extends DaemonIntegrationSpec implements VfsRetentionFixture {

    private static final int NUMBER_OF_SUBPROJECTS = 50
    private static final int NUMBER_OF_SOURCES_PER_SUBPROJECT = 100
    private static final double LOST_EVENTS_RATIO_MAC_OS = 0.6
    private static final double LOST_EVENTS_RATIO_WINDOWS = 0.1

    List<TestFile> sourceFiles

    def setup() {
        def subprojects = (1..NUMBER_OF_SUBPROJECTS).collect { "project$it" }
        def rootProject = multiProjectBuild("javaProject", subprojects) {
            buildFile << """
                allprojects {
                    apply plugin: 'java-library'

                    tasks.withType(JavaCompile).configureEach {
                        options.fork = true
                    }
                }
            """
        }
        sourceFiles = subprojects.collectMany { projectDir ->
            (1..NUMBER_OF_SOURCES_PER_SUBPROJECT).collect {
                def sourceFile = rootProject.file("${projectDir}/src/main/java/my/domain/Dummy${it}.java")
                modifySourceFile(sourceFile, 0)
                return sourceFile
            }
        }

        executer.beforeExecute {
            withRetention()
            // running in parallel, so the soak test doesn't take this long.
            withArgument("--parallel")
        }
    }

    @Ignore
    def "file watching works with multiple builds on the same daemon"() {
        def numberOfChangesBetweenBuilds = maxFileChangesWithoutOverflow

        when:
        succeeds("assemble")
        def daemon = daemons.daemon
        then:
        daemon.assertIdle()

        expect:
        50.times { iteration ->
            changeSourceFiles(iteration, numberOfChangesBetweenBuilds)
            waitForChangesToBePickedUp()
            succeeds("assemble")
            assert daemons.daemon.logFile == daemon.logFile
            daemon.assertIdle()
            assertWatchingSucceeded()
            retainedFilesInCurrentBuild - numberOfChangesBetweenBuilds == retainedFilesSinceLastBuild
            assert receivedFileSystemEvents >= minimumExpectedFileSystemEvents(numberOfChangesBetweenBuilds, 1)
        }
    }

    @Unroll
    def "file watching works with many changes between two builds (#waitTime ms between changes, #numberOfChangedSourcesFilesPerBatch changes per batch"() {
        // Use 20 minutes idle timeout since the test may be running longer with an idle daemon
        executer.withDaemonIdleTimeoutSecs(2400)
        def numberOfChangeBatches = 250

        when:
        succeeds("assemble")
        def daemon = daemons.daemon
        then:
        daemon.assertIdle()

        when:
        numberOfChangeBatches.times { iteration ->
            changeSourceFiles(iteration, numberOfChangedSourcesFilesPerBatch)
            Thread.sleep(waitTime)
            assert daemons.getDaemons().size() == 1
            assert !daemon.logContains(FileWatcherRegistry.Type.INVALIDATE.toString()) : "Overflow in file watcher after ${iteration} iterations"
        }
        then:
        succeeds("assemble")
        daemons.daemon.logFile == daemon.logFile
        daemon.assertIdle()
        assertWatchingSucceeded()
        receivedFileSystemEvents >= minimumExpectedFileSystemEvents(numberOfChangedSourcesFilesPerBatch, numberOfChangeBatches)
        retainedFilesInCurrentBuild - numberOfChangedSourcesFilesPerBatch == retainedFilesSinceLastBuild
        where:
        waitTimeAndNumber << [[200, 300, 500, 800], [800, 1000]].combinations()
        waitTime = waitTimeAndNumber[0]
        numberOfChangedSourcesFilesPerBatch = waitTimeAndNumber[1]
    }

    private static int getMaxFileChangesWithoutOverflow() {
        if (OperatingSystem.current().windows) {
            800
        } else {
            1000
        }
    }

    private static void waitBetweenChangesToAvoidOverflow() {
        if (OperatingSystem.current().windows) {
            Thread.sleep(200)
        } else {
            Thread.sleep(150)
        }
    }

    private static int minimumExpectedFileSystemEvents(int numberOfChangedFiles, int numberOfChangesPerFile) {
        def currentOs = OperatingSystem.current()
        if (currentOs.macOsX) {
            // macOS coalesces the changes if the are in short succession
            return numberOfChangedFiles * numberOfChangesPerFile * LOST_EVENTS_RATIO_MAC_OS
        } else if (currentOs.linux) {
            // the JDK watchers only capture one event per watched path
            return numberOfChangedFiles
        } else if (currentOs.windows) {
            return numberOfChangedFiles * numberOfChangesPerFile * LOST_EVENTS_RATIO_WINDOWS
        }
        throw new AssertionError("Test not supported on OS ${currentOs}")
    }

    private void changeSourceFiles(int iteration, int number) {
        sourceFiles.take(number).each { sourceFile ->
            modifySourceFile(sourceFile, iteration + 1)
        }
    }

    private int getReceivedFileSystemEvents() {
        String eventsSinceLastBuild = result.getOutputLineThatContains("file system events since last build")
        def numberMatcher = eventsSinceLastBuild =~ /Received (\d+) file system events since last build/
        return numberMatcher[0][1] as int
    }

    private int getRetainedFilesSinceLastBuild() {
        String retainedInformation = result.getOutputLineThatContains("Virtual file system retained information about ")
        def numberMatcher = retainedInformation =~ /Virtual file system retained information about (\d+) files, (\d+) directories and (\d+) missing files since last build/
        return numberMatcher[0][1] as int
    }

    private int getRetainedFilesInCurrentBuild() {
        // Can't use `getOutputLineThatContains` here, since that only matches the output before the build finished message
        def retainedInformation = result.normalizedOutput.readLines().stream().filter { line -> line.contains("Virtual file system retains information about ") }.findFirst().get()
        def numberMatcher = retainedInformation =~ /Virtual file system retains information about (\d+) files, (\d+) directories and (\d+) missing files till next build/
        return numberMatcher[0][1] as int
    }

    private void assertWatchingSucceeded() {
        outputDoesNotContain("Couldn't create watch service")
        outputDoesNotContain("Couldn't fetch file changes, dropping VFS state")
        outputDoesNotContain("Dropped VFS state due to lost state")
    }

    private static void modifySourceFile(TestFile sourceFile, int numberOfMethods) {
        String className = sourceFile.name - ".java"
        sourceFile.text = """
                    package my.domain;

                    public class ${className} {
                        ${ (1..numberOfMethods).collect { "public void doNothing${it}() {}" }.join("\n")}
                    }
                """
    }
}
