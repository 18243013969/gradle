/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.gradlebuild.test.integrationtests

import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.*
import org.gradle.util.GradleVersion
import java.io.StringReader
import java.util.Properties


interface BuildBucketProvider {
    fun configureTest(testTask: Test, sourceSet: SourceSet, testType: TestType)

    companion object {
        private
        var instance: BuildBucketProvider? = null

        fun getInstance(project: Project): BuildBucketProvider {
            if (instance == null) {
                println("include: " + project.stringPropertyOrEmpty("includeTestClasses"))
                println("exclude: " + project.stringPropertyOrEmpty("excludeTestClasses"))
                instance = when {
                    project.stringPropertyOrEmpty("includeTestClasses").isNotBlank() -> {
                        val content = project.rootProject.buildDir.resolve("include-test-classes.properties").readText()
                        println("Tests to be included:\n$content")
                        IncludeTestClassProvider(readTestClasses(content))
                    }
                    project.stringPropertyOrEmpty("excludeTestClasses").isNotBlank() -> {
                        val content = project.rootProject.buildDir.resolve("exclude-test-classes.properties").readText()
                        println("Tests to be excluded:\n$content")
                        ExcludeTestClassProvider(readTestClasses(content))
                    }
                    project.stringPropertyOrEmpty("onlyTestGradleMajorVersion").isNotBlank() -> {
                        CrossVersionBucketProvider(project.stringPropertyOrEmpty("onlyTestGradleMajorVersion"))
                    }
                    else -> {
                        NoOpTestClassProvider()
                    }
                }
            }
            println("fuck: " + instance!!)

            return instance!!
        }

        private
        fun readTestClasses(content: String): Map<String, List<String>> {
            val properties = Properties()
            val ret = mutableMapOf<String, MutableList<String>>()
            properties.load(StringReader(content))
            properties.forEach { key, value ->
                val list = ret.getOrDefault(value, mutableListOf())
                list.add(key!!.toString())
                ret[value!!.toString()] = list
            }
            return ret
        }
    }
}


class CrossVersionBucketProvider(private val onlyTestGradleMajorVersion: String) : BuildBucketProvider {
    override fun configureTest(testTask: Test, sourceSet: SourceSet, testType: TestType) {
        val currentVersionUnderTest = extractTestTaskGradleVersion(testTask.name)
        currentVersionUnderTest?.apply {
            testTask.enabled = currentVersionEnabled(currentVersionUnderTest)
        }
    }

    private
    fun currentVersionEnabled(currentVersionUnderTest: String): Boolean {
        val versionUnderTest = GradleVersion.version(currentVersionUnderTest)
        // if onlyTestGradleMajorVersion=1, we test Gradle 0.x and Gradle 1.x
        val majorVersion = GradleVersion.version("${if (onlyTestGradleMajorVersion == "1") "0" else onlyTestGradleMajorVersion}.0")
        val nextMajorVersion = GradleVersion.version("${onlyTestGradleMajorVersion.toInt() + 1}.0")
        return majorVersion <= versionUnderTest && versionUnderTest < nextMajorVersion
    }

    private
    fun extractTestTaskGradleVersion(name: String): String? = "gradle(.+)CrossVersionTest".toRegex().find(name)?.groupValues?.get(1)
}


class IncludeTestClassProvider(private val includeTestClasses: Map<String, List<String>>) : BuildBucketProvider {
    override fun configureTest(testTask: Test, sourceSet: SourceSet, testType: TestType) {
        if (testTask.name == "integMultiVersionTest") {
            // Run integMultiVersionTest in last split
            testTask.enabled = false
        } else {
            testTask.filter.isFailOnNoMatchingTests = false
            includeTestClasses[sourceSet.name]?.apply { testTask.filter.includePatterns.addAll(this) }
        }
    }
}


class ExcludeTestClassProvider(private val excludeTestClasses: Map<String, List<String>>) : BuildBucketProvider {
    override fun configureTest(testTask: Test, sourceSet: SourceSet, testType: TestType) {
        if (testTask.name != "integMultiVersionTest") {
            testTask.filter.isFailOnNoMatchingTests = false
            excludeTestClasses[sourceSet.name]?.apply { testTask.filter.excludePatterns.addAll(this) }
        }
    }
}


class NoOpTestClassProvider : BuildBucketProvider {
    override fun configureTest(testTask: Test, sourceSet: SourceSet, testType: TestType) {
    }
}
