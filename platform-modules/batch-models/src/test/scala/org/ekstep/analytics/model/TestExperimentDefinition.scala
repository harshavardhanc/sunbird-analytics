package org.ekstep.analytics.model

import org.apache.spark.SparkContext
import org.ekstep.analytics.framework.util.JSONUtils
import org.ekstep.analytics.framework.{DeviceProfileModel, JobConfig, OutputDispatcher}
import org.scalamock.scalatest.MockFactory

import scala.collection.mutable.Buffer
import scala.io.Source


class TestExperimentDefinition  extends SparkSpec(null) with MockFactory {

  var schema: Buffer[ExperiementDefinitionOutput] = Buffer()

  implicit var util = mock[ExperimentDataUtils]

  it should " map userid to experiement id  with criteria" in {


    val experiments = loadFile[ExperimentDefinition]("src/test/resources/experiment/experiments.json")
    val userdata = JSONUtils.deserialize[UserResponse](Source.fromInputStream
    (getClass.getResourceAsStream("/experiment/userResponse.json")).getLines().mkString)

    val criteria = JSONUtils.deserialize[CriteriaModel](experiments.collect().apply(0).criteria)

    (util.getUserAPIToken _).expects().returns("12345")
    (util.getUserDetails[UserResponse](_: String, _: String)(_: Manifest[UserResponse])).
      expects("12345", JSONUtils.serialize(criteria.filters), *).returns(userdata)
    (util.getDeviceProfile(_: String, _: String)(_: SparkContext)).expects("local_device_db", "device_profile", *).
      returns(loadFile[DeviceProfileModel]("src/test/resources/experiment/device_profile.txt"))


    val out = ExperimentDefinitionModel.algorithmProcess(experiments, schema)
    val result = out.fold(sc.emptyRDD[ExperimentMappingOutput])(_ ++ _)

    result.count() should be(6)

    val userMappedCount = result.filter { x => x.id.equals("U1234") }

    userMappedCount.count() should be(6)


  }


  it should " map deviceid to experiement with experiment id B1534 with criteria firstacccess and state" in {

    val experiments = loadFile[ExperimentDefinition]("src/test/resources/experiment/device-experiment-config.json")

    val userdata = JSONUtils.deserialize[UserResponse](Source.fromInputStream
    (getClass.getResourceAsStream("/experiment/userResponse.json")).getLines().mkString)

    (util.getUserAPIToken _).expects().returns("12345")
    (util.getDeviceProfile(_: String, _: String)(_: SparkContext)).expects("local_device_db", "device_profile", *).
      returns(loadFile[DeviceProfileModel]("src/test/resources/experiment/device_profile.txt"))

    val out = ExperimentDefinitionModel.algorithmProcess(experiments, schema)
    val result = out.fold(sc.emptyRDD[ExperimentMappingOutput])(_ ++ _)

    result.count().shouldBe(41)

    val deviceMappedCount = result.filter { x => x.id.equals("D1234") }
    result.count().shouldBe(41)

  }


  ignore should "save es" in {

    val config = "{\"search\":{\"type\":\"none\"},\"model\":\"org.ekstep.analytics.model.ExperimentDefinitionModel\",\"modelParams\":{\"sparkCassandraConnectionHost\":\"localhost\",\"sparkElasticsearchConnectionHost\":\"localhost\"},\"output\":[{\"to\":\"es\",\"params\":{\"index\":\"experiment\"}}],\"parallelization\":8,\"appName\":\"Experiment-Definition\",\"deviceMapping\":false}"
    val jobconfig = JSONUtils.deserialize[JobConfig](config)
    val experiments = loadFile[ExperimentDefinition]("src/test/resources/experiment/experiments.json")
    val out = ExperimentDefinitionModel.execute(sc.emptyRDD, jobconfig.modelParams)

    OutputDispatcher.dispatch(jobconfig.output, out)
  }


}
