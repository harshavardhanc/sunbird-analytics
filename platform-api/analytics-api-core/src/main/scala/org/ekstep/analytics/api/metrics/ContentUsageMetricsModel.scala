package org.ekstep.analytics.api.metrics

import com.datastax.driver.core.querybuilder.QueryBuilder
import org.ekstep.analytics.api.{Constants, ContentUsageMetrics, IMetricsModel}
import com.typesafe.config.Config
import org.ekstep.analytics.api.util.{CommonUtil, DBUtil}
import org.ekstep.analytics.framework.util.JSONUtils
import com.weather.scalacass.syntax._
import scala.collection.JavaConverters.iterableAsScalaIterableConverter

case class ContentUsageTable(d_period: Int, d_content_id: String, d_tag: String, m_total_ts: Option[Double] = Option(0.0), m_total_sessions: Option[Long] = Option(0), m_avg_ts_session: Option[Double] = Option(0.0), m_total_interactions: Option[Long] = Option(0), m_avg_interactions_min: Option[Double] = Option(0.0), m_total_devices: Option[Long] = Option(0), m_avg_sess_device: Option[Double] = Option(0.0))

object CotentUsageMetricsModel extends IMetricsModel[ContentUsageMetrics, ContentUsageMetrics] with Serializable {

    override def metric: String = "cus";

    override def getMetrics(records: Array[ContentUsageMetrics], period: String, fields: Array[String] = Array())(implicit config: Config): Array[ContentUsageMetrics] = {
        val periodEnum = periodMap.get(period).get._1;
        val periods = _getPeriods(period);

        val recordsArray = records.map { x => (x.d_period.get, x) };
        val periodsArray = periods.map { period => (period, ContentUsageMetrics(Option(period), Option(CommonUtil.getPeriodLabel(periodEnum, period)))) };

        periodsArray.map { tup1 =>
            val tmp = recordsArray.filter(tup2 => tup1._1 == tup2._1)
            if (tmp.isEmpty) (tup1._1, (tup1._2, None)) else (tup1._1, (tup1._2, tmp.apply(0)._2))
        }.sortBy(-_._1).map { f => if (None != f._2._2 ) _merge(f._2._2.asInstanceOf[ContentUsageMetrics], f._2._1) else f._2._1 }
    }

    private def _merge(obj: ContentUsageMetrics, dummy: ContentUsageMetrics): ContentUsageMetrics = {
        ContentUsageMetrics(dummy.d_period, dummy.label, obj.m_total_ts, obj.m_total_sessions, obj.m_avg_ts_session, obj.m_total_interactions,
            obj.m_avg_interactions_min, obj.m_total_devices, obj.m_avg_sess_device)
    }

    override def reduce(fact1: ContentUsageMetrics, fact2: ContentUsageMetrics, fields: Array[String] = Array()): ContentUsageMetrics = {
        val total_ts = CommonUtil.roundDouble(fact2.m_total_ts.get + fact1.m_total_ts.get, 2);
        val total_sessions = fact2.m_total_sessions.getOrElse(0l).asInstanceOf[Number].longValue() + fact1.m_total_sessions.getOrElse(0l).asInstanceOf[Number].longValue()
        val avg_ts_session = if (total_sessions > 0) CommonUtil.roundDouble((total_ts / total_sessions), 2) else 0.0;
        val total_interactions = fact2.m_total_interactions.getOrElse(0l).asInstanceOf[Number].longValue() + fact1.m_total_interactions.getOrElse(0l).asInstanceOf[Number].longValue()
        val avg_interactions_min = if (total_interactions == 0 || total_ts == 0) 0d else CommonUtil.roundDouble(BigDecimal(total_interactions / (total_ts / 60)).toDouble, 2);
        val total_devices = fact2.m_total_devices.getOrElse(0l).asInstanceOf[Number].longValue() + fact1.m_total_devices.getOrElse(0l).asInstanceOf[Number].longValue()
        val avg_sess_device = if (total_devices > 0) CommonUtil.roundDouble(total_sessions.toDouble / total_devices, 2) else 0.0;
        ContentUsageMetrics(fact1.d_period, None, Option(total_ts), Option(total_sessions), Option(avg_ts_session), Option(total_interactions), Option(avg_interactions_min), Option(total_devices), Option(avg_sess_device));
    }

    override def getSummary(summary: ContentUsageMetrics): ContentUsageMetrics = {
        ContentUsageMetrics(None, None, summary.m_total_ts, summary.m_total_sessions, summary.m_avg_ts_session, summary.m_total_interactions, summary.m_avg_interactions_min, summary.m_total_devices, summary.m_avg_sess_device);
    }

    override def getData(contentId: String, tags: Array[String], period: String, channel: String, userId: String = "all", deviceId: String = "all", metricsType: String = "app", mode: String = "")(implicit mf: Manifest[ContentUsageMetrics], config: Config): Array[ContentUsageMetrics] = {
        val periods = _getPeriods(period);

        val queries = tags.map { tag =>
            periods.map { p =>
                QueryBuilder.select().all().from(Constants.CONTENT_DB, Constants.CONTENT_SUMMARY_FACT_TABLE).allowFiltering().where(QueryBuilder.eq("d_period", p)).and(QueryBuilder.eq("d_tag", tag)).and(QueryBuilder.eq("d_content_id", contentId)).and(QueryBuilder.eq("d_channel", channel)).toString();
            }
        }.flatMap(x => x)

        queries.map { q =>
            val res = DBUtil.session.execute(q)
            res.all().asScala.map(x => x.as[ContentUsageTable])
        }.flatMap(x => x).map(f => getSummaryFromCass(f))
    }

    private def getSummaryFromCass(summary: ContentUsageTable): ContentUsageMetrics = {
        ContentUsageMetrics(Option(summary.d_period), None, summary.m_total_ts, summary.m_total_sessions, summary.m_avg_ts_session, summary.m_total_interactions, summary.m_avg_interactions_min, summary.m_total_devices, summary.m_avg_sess_device);
    }
}