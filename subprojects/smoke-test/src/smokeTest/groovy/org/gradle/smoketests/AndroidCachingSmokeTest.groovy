/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.smoketests

import org.eclipse.jgit.api.Git
import org.gradle.integtests.fixtures.executer.IntegrationTestBuildContext

class AndroidCachingSmokeTest extends AbstractSmokeTest {

    def "can cache Santa Tracker Android application"() {
        def testRepoUri = "https://github.com/gradle/android-relocation-test"
        def testRepoTarget = "9bf38c590e4b08dc65cddc47ca9989b5531f7b55"

        def projectDir = testProjectDir.root

        println "> Cloning $testRepoUri"

        def git = Git.cloneRepository()
            .setURI(testRepoUri)
            .setDirectory(projectDir)
            .setCloneSubmodules(true)
            .call()

        println "> Checking out $testRepoTarget"
        git.checkout()
            .setName(testRepoTarget)
            .call()

        def commitId = git.repository.findRef("HEAD").objectId.name()
        println "> Building commit $commitId"

        def buildDir = file("checkout/santa-tracker")
        def buildFile = buildDir.file("build.gradle")
        buildFile.text -= """plugins {
    id 'com.gradle.build-scan' version '2.1' apply false
}

if (!hasProperty("disableBuildScan")) {
    apply plugin: "com.gradle.build-scan"
    buildScan {
        termsOfServiceUrl = 'https://gradle.com/terms-of-service'
        termsOfServiceAgree = 'yes'
        captureTaskInputFiles = true
    }
}
"""

        expect:
        runner(
                "check",
                "-Dorg.gradle.android.test.gradle-installation=" + IntegrationTestBuildContext.INSTANCE.gradleHomeDir.absolutePath,
                "-Dorg.gradle.android.test.show-output=true",
                "-Dorg.gradle.android.test.scan-url=https://e.grdev.net/"
            )
            .withProjectDir(projectDir)
            .forwardOutput()
            .build()
    }
}
