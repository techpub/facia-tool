import java.io.File

import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import common._
import conf.{Configuration => GuardianConfiguration, aws, SwitchboardLifecycle, Gzipper}
import metrics.FrontendMetric
import permissions.{PermissionsReader, ScheduledJob}
import play.api._
import play.api.mvc.WithFilters
import services.ConfigAgentLifecycle

object Global extends WithFilters(Gzipper)
  with GlobalSettings
  with CloudWatchApplicationMetrics
  with ConfigAgentLifecycle
  with SwitchboardLifecycle {

  override lazy val applicationName = "frontend-facia-tool"

  override def applicationMetrics: List[FrontendMetric] = super.applicationMetrics ::: List(
    FaciaToolMetrics.ApiUsageCount,
    FaciaToolMetrics.ProxyCount,
    FaciaToolMetrics.ContentApiPutFailure,
    FaciaToolMetrics.ContentApiPutSuccess,
    FaciaToolMetrics.DraftPublishCount,
    FaciaToolMetrics.ExpiredRequestCount,
    ContentApiMetrics.ElasticHttpTimingMetric,
    ContentApiMetrics.ElasticHttpTimeoutCountMetric,
    ContentApiMetrics.ContentApiErrorMetric,
    S3Metrics.S3ClientExceptionsMetric
  )

  override def onStart(app: Application) = {
    val s3Client = new AmazonS3Client(aws.mandatoryCredentials)
    s3Client.setRegion(Regions.fromName("eu-west-1"))
    val permissionsReader = new PermissionsReader("permissions.json", "permissions-cache/CODE", s3Client)
    val (contents, date) = permissionsReader.getObjectAsString("permissions.json", "permissions-cache/CODE")
  }
}
