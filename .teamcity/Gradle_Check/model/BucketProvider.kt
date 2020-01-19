package Gradle_Check.model

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import common.Os
import configurations.FunctionalTest
import jetbrains.buildServer.configs.kotlin.v2018_2.BuildStep
import jetbrains.buildServer.configs.kotlin.v2018_2.BuildSteps
import jetbrains.buildServer.configs.kotlin.v2018_2.buildSteps.script
import model.BuildTypeBucket
import model.CIBuildModel
import model.GradleSubproject
import model.Stage
import model.TestCoverage
import model.TestType
import java.io.File
import java.util.*

const val BUCKET_NUMBER_PER_BUILD_TYPE = 50

const val MAX_PROJECT_NUMBER_IN_BUCKET = 10

typealias BuildProjectToSubprojectTestClassTimes = Map<String, Map<String, List<TestClassTime>>>

interface GradleBuildBucketProvider {
    fun createFunctionalTestsFor(stage: Stage, testCoverage: TestCoverage): List<FunctionalTest>

    fun createDeferredFunctionalTestsFor(stage: Stage): List<FunctionalTest>
}

class StatisticBasedGradleBuildBucketProvider(private val model: CIBuildModel, testTimeDataJson: File) : GradleBuildBucketProvider {
    private val buckets: Map<TestCoverage, List<BuildTypeBucket>> = buildBuckets(testTimeDataJson, model)

    override fun createFunctionalTestsFor(stage: Stage, testCoverage: TestCoverage): List<FunctionalTest> {
        return buckets.getValue(testCoverage).mapIndexed { bucketIndex: Int, bucket: BuildTypeBucket ->
            bucket.createFunctionalTestsFor(model, stage, testCoverage, bucketIndex)
        }
    }

    override fun createDeferredFunctionalTestsFor(stage: Stage): List<FunctionalTest> {
        // The first stage which doesn't omit slow projects
        val deferredStage = model.stages.find { !it.omitsSlowProjects }!!
        val deferredStageIndex = model.stages.indexOfFirst { !it.omitsSlowProjects }
        return if (stage.stageName != deferredStage.stageName) {
            emptyList()
        } else {
            val stages = model.stages.subList(0, deferredStageIndex)
            val deferredTests = mutableListOf<FunctionalTest>()
            stages.forEach { eachStage ->
                eachStage.functionalTests.forEach { testConfig ->
                    deferredTests.addAll(model.subprojects.getSlowSubprojects().map { it.createFunctionalTestsFor(model, eachStage, testConfig, -1) })
                }
            }
            deferredTests
        }
    }

    private
    fun buildBuckets(buildClassTimeJson: File, model: CIBuildModel): Map<TestCoverage, List<BuildTypeBucket>> {
        val jsonObj = JSON.parseObject(buildClassTimeJson.readText()) as JSONObject
        val buildProjectClassTimes: BuildProjectToSubprojectTestClassTimes = jsonObj.map { buildProjectToSubprojectTestClassTime ->
            buildProjectToSubprojectTestClassTime.key to (buildProjectToSubprojectTestClassTime.value as JSONObject).map { subProjectToTestClassTime ->
                subProjectToTestClassTime.key to (subProjectToTestClassTime.value as JSONArray).map { TestClassTime(it as JSONObject) }
            }.toMap()
        }.toMap()

        val result = mutableMapOf<TestCoverage, List<BuildTypeBucket>>()
        for (stage in model.stages) {
            for (testCoverage in stage.functionalTests) {
                when (testCoverage.testType) {
                    TestType.allVersionsIntegMultiVersion -> {
                        result[testCoverage] = listOf(AllSubprojectsIntegMultiVersionTest.INSTANCE)
                    }
                    in listOf(TestType.allVersionsCrossVersion, TestType.quickFeedbackCrossVersion) -> {
                        result[testCoverage] = splitBucketsByGradleVersionForBuildProject(6)
                    }
                    else -> {
                        result[testCoverage] = splitBucketsByTestClassesForBuildProject(testCoverage, stage, buildProjectClassTimes)
                    }
                }
            }
        }
        return result
    }

    // For quickFeedbackCrossVersion and allVersionsCrossVersion, the buckets are split by Gradle version
    // By default, split them into [gradle1, gradle2, gradle3, gradle4, gradle5, gradle6]
    private fun splitBucketsByGradleVersionForBuildProject(maxGradleMajorVersion: Int) = (1..maxGradleMajorVersion).map { GradleVersionXCrossVersionTestBucket(it) }

    private
    fun splitBucketsByTestClassesForBuildProject(testCoverage: TestCoverage, stage: Stage, buildProjectClassTimes: BuildProjectToSubprojectTestClassTimes): List<BuildTypeBucket> {
        val validSubprojects = model.subprojects.getSubprojectsFor(testCoverage, stage)

        // Build project not found, don't split into buckets
        val subProjectToClassTimes: Map<String, List<TestClassTime>> = buildProjectClassTimes[testCoverage.asId(model)] ?: return validSubprojects

        val subProjectTestClassTimes: List<SubprojectTestClassTime> = subProjectToClassTimes
            .entries
            .filter { "UNKNOWN" != it.key }
            .filter { model.subprojects.getSubprojectByName(it.key) != null }
            .map { SubprojectTestClassTime(model.subprojects.getSubprojectByName(it.key)!!, it.value.filter { it.sourceSet != "test" }) }
            .sortedBy { -it.totalTime }

        return split(
            LinkedList(subProjectTestClassTimes),
            SubprojectTestClassTime::totalTime,
            { largeElement: SubprojectTestClassTime, size: Int -> largeElement.split(size) },
            { list: List<SubprojectTestClassTime> -> SmallSubprojectBucket(list) },
            BUCKET_NUMBER_PER_BUILD_TYPE,
            MAX_PROJECT_NUMBER_IN_BUCKET
        )
    }
}

/**
 * Split a list of elements into nearly even sublist. If an element is too large, largeElementSplitFunction will be used to split the large element into several smaller pieces;
 * if some elements are too small, they will be aggregated by smallElementAggregateFunction.
 *
 * @param list the list to split, must be ordered by size desc
 * @param toIntFunction the function used to map the element to its "size"
 * @param largeElementSplitFunction the function used to further split the large element into smaller pieces
 * @param smallElementAggregateFunction the function used to aggregate tiny elements into a large bucket
 * @param expectedBucketNumber the return value's size should be expectedBucketNumber
 */
fun <T, R> split(list: LinkedList<T>, toIntFunction: (T) -> Int, largeElementSplitFunction: (T, Int) -> List<R>, smallElementAggregateFunction: (List<T>) -> R, expectedBucketNumber: Int, maxNumberInBucket: Int): List<R> {
    if (expectedBucketNumber == 1) {
        return listOf(smallElementAggregateFunction(list))
    }

    val expectedBucketSize = list.sumBy(toIntFunction) / expectedBucketNumber

    val largestElement = list.removeFirst()!!

    val largestElementSize = toIntFunction(largestElement)

    return if (largestElementSize >= expectedBucketSize) {
        val bucketsOfFirstElement = largeElementSplitFunction(largestElement, if (largestElementSize % expectedBucketSize == 0) largestElementSize / expectedBucketSize else largestElementSize / expectedBucketSize + 1)
        val bucketsOfRestElements = split(list, toIntFunction, largeElementSplitFunction, smallElementAggregateFunction, expectedBucketNumber - bucketsOfFirstElement.size, maxNumberInBucket)
        bucketsOfFirstElement + bucketsOfRestElements
    } else {
        val buckets = arrayListOf(largestElement)
        var restCapacity = expectedBucketSize - toIntFunction(largestElement)
        while (restCapacity > 0 && list.isNotEmpty() && buckets.size < maxNumberInBucket) {
            val smallestElement = list.removeLast()
            buckets.add(smallestElement)
            restCapacity -= toIntFunction(smallestElement)
        }
        listOf(smallElementAggregateFunction(buckets)) + split(list, toIntFunction, largeElementSplitFunction, smallElementAggregateFunction, expectedBucketNumber - 1, maxNumberInBucket)
    }
}

enum class AllSubprojectsIntegMultiVersionTest : BuildTypeBucket {
    INSTANCE;

    override fun createFunctionalTestsFor(model: CIBuildModel, stage: Stage, testCoverage: TestCoverage, bucketIndex: Int) =
        FunctionalTest(model,
            testCoverage.asConfigurationId(model, "all"),
            testCoverage.asName(),
            "${testCoverage.asName()} for all subprojects",
            testCoverage,
            stage,
            emptyList()
        )
}

class GradleVersionXCrossVersionTestBucket(private val gradleMajorVersion: Int) : BuildTypeBucket {
    override fun createFunctionalTestsFor(model: CIBuildModel, stage: Stage, testCoverage: TestCoverage, bucketIndex: Int) =
        FunctionalTest(model,
            testCoverage.asConfigurationId(model, "gradle$gradleMajorVersion"),
            "${testCoverage.asName()} (gradle $gradleMajorVersion)",
            "${testCoverage.asName()} for gradle $gradleMajorVersion",
            testCoverage,
            stage,
            emptyList(),
            "-PonlyTestGradleMajorVersion=$gradleMajorVersion"
        )
}

class LargeSubprojectSplitBucket(val subProject: GradleSubproject, val number: Int, val include: Boolean, val classes: List<TestClassTime>) : BuildTypeBucket by subProject {
    val name = "${subProject.name}_$number"
    val totalTime = classes.sumBy { it.buildTimeMs }

    override fun getName(testCoverage: TestCoverage) = "${testCoverage.asName()} ($name)"

    override fun createFunctionalTestsFor(model: CIBuildModel, stage: Stage, testCoverage: TestCoverage, bucketIndex: Int): FunctionalTest =
        FunctionalTest(model,
            getUuid(model, testCoverage, bucketIndex),
            getName(testCoverage),
            getDescription(testCoverage),
            testCoverage,
            stage,
            subprojects = listOf(subProject.name),
            extraParameters = if (include) "-PincludeTestClasses=true -x ${subProject.name}:test" else "-PexcludeTestClasses=true", // Only run unit test in last bucket
            preBuildSteps = prepareTestClassesStep(testCoverage.os)
        )

    private fun prepareTestClassesStep(os: Os): BuildSteps.() -> Unit {
        val testClasses = classes.map { it.toPropertiesLine() }
        val action = if (include) "include" else "exclude"
        val unixScript = """
mkdir -p build
rm -rf build/*-test-classes.properties
cat > build/$action-test-classes.properties << EOL
${testClasses.joinToString("\n")}
EOL

echo "Tests to be ${action}d in this build"
cat build/$action-test-classes.properties
"""

        val linesWithEcho = testClasses.joinToString("\n") { "echo $it" }

        val windowsScript = """
mkdir build
del /f /q build\include-test-classes.properties
del /f /q build\exclude-test-classes.properties
(
$linesWithEcho
) > build\$action-test-classes.properties

echo "Tests to be ${action}d in this build"
type build\$action-test-classes.properties
"""

        return {
            script {
                name = "PREPARE_TEST_CLASSES"
                executionMode = BuildStep.ExecutionMode.ALWAYS
                scriptContent = if (os == Os.windows) windowsScript else unixScript
            }
        }
    }
}

class SmallSubprojectBucket(val subprojectsBuildTime: List<SubprojectTestClassTime>) : BuildTypeBucket {
    val subprojects = subprojectsBuildTime.map { it.subProject }
    val name = subprojects.joinToString(",") { it.name }
    val totalTime = subprojectsBuildTime.sumBy { it.totalTime }
    override fun createFunctionalTestsFor(model: CIBuildModel, stage: Stage, testCoverage: TestCoverage, bucketIndex: Int): FunctionalTest =
        FunctionalTest(model,
            getUuid(model, testCoverage, bucketIndex),
            getName(testCoverage),
            getDescription(testCoverage),
            testCoverage,
            stage,
            subprojects.map { it.name }
        )

    override fun getName(testCoverage: TestCoverage) = "${testCoverage.asName()} (${subprojects.joinToString(",") { it.name }})"

    override fun getDescription(testCoverage: TestCoverage) = "${testCoverage.asName()} for ${subprojects.joinToString(", ") { it.name }}"
}

class TestClassTime(var testClass: String, val sourceSet: String, var buildTimeMs: Int) {
    constructor(jsonObject: JSONObject) : this(
        jsonObject.getString("testClass"),
        jsonObject.getString("sourceSet"),
        jsonObject.getIntValue("buildTimeMs")
    )

    fun toPropertiesLine() = "$testClass=$sourceSet"
}

class SubprojectTestClassTime(val subProject: GradleSubproject, private val testClassTimes: List<TestClassTime>) {
    val totalTime: Int = testClassTimes.sumBy { it.buildTimeMs }

    fun split(expectedBucketNumber: Int): List<BuildTypeBucket> {
        return if (expectedBucketNumber == 1) {
            listOf(subProject)
        } else {
            // fun <T, R> split(list: LinkedList<T>, toIntFunction: (T) -> Int, largeElementSplitFunction: (T, Int) -> List<R>, smallElementAggregateFunction: (List<T>) -> R, expectedBucketNumber: Int, maxNumberInBucket: Int): List<R> {
            // T TestClassTime
            // R List<TestClassTime>
            val list = LinkedList(testClassTimes.sortedBy { -it.buildTimeMs })
            val toIntFunction = TestClassTime::buildTimeMs
            val largeElementSplitFunction: (TestClassTime, Int) -> List<List<TestClassTime>> = { testClassTime: TestClassTime, number: Int -> listOf(listOf(testClassTime)) }
            val smallElementAggregateFunction: (List<TestClassTime>) -> List<TestClassTime> = { it }

            val buckets: List<List<TestClassTime>> = split(list, toIntFunction, largeElementSplitFunction, smallElementAggregateFunction, expectedBucketNumber, Integer.MAX_VALUE)

            buckets.mapIndexed { index: Int, classesInBucket: List<TestClassTime> ->
                val include = index != buckets.size - 1
                val classes = if (include) classesInBucket else buckets.subList(0, buckets.size - 1).flatten()
                LargeSubprojectSplitBucket(subProject, index + 1, include, classes)
            }
        }
    }

    override fun toString(): String {
        return "SubprojectTestClassTime(subProject=${subProject.name}, totalTime=$totalTime)"
    }
}
