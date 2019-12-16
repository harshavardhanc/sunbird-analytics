package org.ekstep.analytics.updater

import com.datastax.spark.connector._
import org.apache.spark.{HashPartitioner, SparkContext}
import org.apache.spark.rdd.RDD
import org.ekstep.analytics.framework._
import org.ekstep.analytics.framework.util.{CommonUtil, JSONUtils}
import org.ekstep.analytics.util.Constants

import scala.collection.mutable.Buffer
case class DeviceProfileKey(device_id: String)
case class DeviceProfileInput(index: DeviceProfileKey, currentData: Buffer[DerivedEvent], previousData: Option[DeviceProfileOutput]) extends AlgoInput
case class DeviceProfileOutput(device_id: String, first_access: Option[Long], last_access: Option[Long], total_ts: Option[Double], total_launches: Option[Long], avg_ts: Option[Double], device_spec: Option[Map[String, AnyRef]], uaspec: Option[Map[String, String]], state: Option[String], city: Option[String], country: Option[String], country_code:Option[String], state_code:Option[String], state_custom: Option[String], state_code_custom: Option[String], district_custom: Option[String], fcm_token: Option[String], producer_id: Option[String], user_declared_state: Option[String], user_declared_district: Option[String], api_last_updated_on:Option[Long], updated_date: Option[Long] = Option(System.currentTimeMillis())) extends AlgoOutput

object UpdateDeviceProfileDB extends IBatchModelTemplate[DerivedEvent, DeviceProfileInput, DeviceProfileOutput, Empty] with Serializable {

    val className = "org.ekstep.analytics.model.UpdateDeviceProfileDB"
    override def name: String = "UpdateDeviceProfileDB"

    override def preProcess(data: RDD[DerivedEvent], config: Map[String, AnyRef])(implicit sc: SparkContext, fc: FrameworkContext): RDD[DeviceProfileInput] = {
        val filteredEvents = DataFilter.filter(data, Filter("eid", "EQ", Option("ME_DEVICE_SUMMARY")))
        val newGroupedEvents = filteredEvents.map { event =>
            (DeviceProfileKey(event.dimensions.did.get), Buffer(event))
        }.partitionBy(new HashPartitioner(JobContext.parallelization)).reduceByKey((a, b) => a ++ b);
        val prevDeviceProfile = newGroupedEvents.map(f => f._1).joinWithCassandraTable[DeviceProfileOutput](Constants.DEVICE_KEY_SPACE_NAME, Constants.DEVICE_PROFILE_TABLE)
        val deviceData = newGroupedEvents.leftOuterJoin(prevDeviceProfile)
        deviceData.map { x => DeviceProfileInput(x._1, x._2._1, x._2._2) }
    }

    override def algorithm(data: RDD[DeviceProfileInput], config: Map[String, AnyRef])(implicit sc: SparkContext, fc: FrameworkContext): RDD[DeviceProfileOutput] = {

        data.map { events =>
            val eventsSortedByFromDate = events.currentData.sortBy { x => x.context.date_range.from };
            val eventsSortedByToDate = events.currentData.sortBy { x => x.context.date_range.to };
            val prevProfileData = events.previousData.getOrElse(DeviceProfileOutput(events.index.device_id, None, None, None, None, None, None, None, None, None, None, None, None, None, None, None, None, None, None, None, None));
            val eventStartTime = eventsSortedByFromDate.head.context.date_range.from
            val first_access = if (prevProfileData.first_access.isEmpty) eventStartTime else if (eventStartTime > prevProfileData.first_access.get) prevProfileData.first_access.get else eventStartTime;
            val eventEndTime = eventsSortedByToDate.last.context.date_range.to
            val last_access = if (prevProfileData.last_access.isEmpty) eventEndTime else if (eventEndTime < prevProfileData.last_access.get) prevProfileData.last_access.get else eventEndTime;
            val current_ts = events.currentData.map { x =>
                (x.edata.eks.asInstanceOf[Map[String, AnyRef]].get("total_ts").get.asInstanceOf[Double])
            }.sum
            val total_ts = if (prevProfileData.total_ts.isEmpty) current_ts else current_ts + prevProfileData.total_ts.get 
            val current_launches = events.currentData.map { x =>
                (x.edata.eks.asInstanceOf[Map[String, AnyRef]].get("total_launches").get.asInstanceOf[Number].longValue())
            }.sum
            val total_launches = if (prevProfileData.total_launches.isEmpty) current_launches else current_launches + prevProfileData.total_launches.get
            val avg_ts = if (total_launches == 0) total_ts else CommonUtil.roundDouble(total_ts / total_launches, 2)
            DeviceProfileOutput(events.index.device_id, Option(first_access), Option(last_access), Option(total_ts), Option(total_launches), Option(avg_ts), prevProfileData.device_spec, prevProfileData.uaspec, prevProfileData.state, prevProfileData.city, prevProfileData.country, prevProfileData.country_code, prevProfileData.state_code, prevProfileData.state_custom, prevProfileData.state_code_custom, prevProfileData.district_custom, prevProfileData.fcm_token, prevProfileData.producer_id, prevProfileData.user_declared_state, prevProfileData.user_declared_district, prevProfileData.api_last_updated_on)
        }
    }

    override def postProcess(data: RDD[DeviceProfileOutput], config: Map[String, AnyRef])(implicit sc: SparkContext, fc: FrameworkContext): RDD[Empty] = {
        data.saveToCassandra(Constants.DEVICE_KEY_SPACE_NAME, Constants.DEVICE_PROFILE_TABLE);
        sc.makeRDD(List(Empty()));
    }
}