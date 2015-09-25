package permissions

import akka.agent.Agent
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{GetObjectRequest, S3Object}
import java.util.Date
import dispatch.Http
import org.quartz._
import org.quartz.impl.StdSchedulerFactory
import permissions.ScheduledJob.FunctionJob
import play.api.libs.json._

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.io.Source
import scala.util.Try

class ScheduledJob(bucket: String, s3Client: AmazonS3Client, callback: Try[Map[String, String]] => Unit = _ => (), scheduler:Scheduler = StdSchedulerFactory.getDefaultScheduler()) {

  private val job = JobBuilder.newJob(classOf[FunctionJob])
                    .withIdentity(s"refresh")
                    .build

  def start(intervalInSeconds: Int = 60) = {
    //kick off the scheduler
    val schedule = SimpleScheduleBuilder.simpleSchedule
      .withIntervalInSeconds(intervalInSeconds)
      .repeatForever()

    val trigger = TriggerBuilder.newTrigger()
      .withSchedule(schedule)
      .build

    ScheduledJob.jobs.put(job.getKey,() => refresh())

    if (scheduler.checkExists(job.getKey)) {
      scheduler.deleteJob(job.getKey)
    }

    scheduler.scheduleJob(job, trigger)
    scheduler.start()
  }

  def refresh() = {

  }
}

object ScheduledJob {
  // globally accessible state for the scheduler
  private val jobs = mutable.Map[JobKey, () => Unit]()
  class FunctionJob extends Job {
    def execute(context: JobExecutionContext) {
      val f = jobs(context.getJobDetail.getKey)
      f()
    }
  }
}

case class SimplePermission(name: String, app: String, defaultValue: Boolean = true)

object SimplePermission {
  implicit val json = Json.format[SimplePermission]
}
case class PermissionOverrideForUser(userId: String, active: Boolean)

object PermissionOverrideForUser {
  implicit val json = Json.format[PermissionOverrideForUser]
}

case class PermissionCacheEntry(permission: SimplePermission, overrides: List[PermissionOverrideForUser])
object PermissionCacheEntry {
  implicit val jsonFormats = Json.format[PermissionCacheEntry]
}

class PermissionsReader(key: String, bucket: String, s3Client: AmazonS3Client)  {
  import scala.concurrent.ExecutionContext.Implicits.global
  val agent = Agent[List[PermissionCacheEntry]](List[PermissionCacheEntry]())

  private def getObject(key: String, bucketName: String): S3Object = s3Client.getObject(new GetObjectRequest(bucketName, key))
  // Get object contents and ensure stream is closed
  //to do - move the parsing and storing somewhere
  def getObjectAsString(key: String, bucketName: String)(implicit executionContext: ExecutionContext): (String, Date) = {
    val obj = getObject(key, bucketName)
    try {
      val (contents, date) = (Source.fromInputStream(obj.getObjectContent, "UTF-8").mkString, obj.getObjectMetadata.getLastModified)
      val jsValue = Json.parse(contents)
      val permissionCache = jsValue.validate[List[PermissionCacheEntry]]
      permissionCache match {
        case JsSuccess(perm, _) => agent.send(perm)
        case JsError(error) => println(s"could not format ${error}")
      }
      (contents, date)
    } catch {
      case e: Exception => {
        ("Contents", new Date)
      }
    } finally {
      obj.close()
    }
  }
}
