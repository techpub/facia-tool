package permissions

import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{GetObjectRequest, S3Object}
import java.util.Date
import dispatch.Http
import org.quartz._
import org.quartz.impl.StdSchedulerFactory
import permissions.ScheduledJob.FunctionJob

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
    println("calling refresh")
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


class PermissionsReader(key: String, bucket: String, s3Client: AmazonS3Client)  {

  private def getObject(key: String, bucketName: String): S3Object = s3Client.getObject(new GetObjectRequest(bucketName, key))

  // Get object contents and ensure stream is closed
  def getObjectAsString(key: String, bucketName: String): (String, Date) = {
    val obj = getObject(key, bucketName)
    try {
      (Source.fromInputStream(obj.getObjectContent, "UTF-8").mkString, obj.getObjectMetadata.getLastModified)
    } catch {
      case e: Exception => {
        ("Contents", new Date)
      }
    } finally {
      obj.close()
    }
  }

}
