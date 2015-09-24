package permissions

import com.amazonaws.services.s3.AmazonS3Client
import dispatch.Http
import org.quartz._
import org.quartz.impl.StdSchedulerFactory

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.util.Try

class PermissionsReader(bucket: String, callback: Try[Map[String, String]] => Unit = _ => (), scheduler:Scheduler = StdSchedulerFactory.getDefaultScheduler()) {

  private val job = JobBuilder.newJob(classOf[FunctionJob])
                    .withIdentity(s"refresh")
                    .build

  def start(intervalInSeconds: Int = 60) = {
    //kick off the scheduler
    println("STARTING JOB")
    val schedule = SimpleScheduleBuilder.simpleSchedule
      .withIntervalInSeconds(intervalInSeconds)
      .repeatForever()

    val trigger = TriggerBuilder.newTrigger()
      .withSchedule(schedule)
      .build

    if (scheduler.checkExists(job.getKey)) {
      scheduler.deleteJob(job.getKey)
    }

    scheduler.scheduleJob(job, trigger)
  }

  // globally accessible state for the scheduler
  private val jobs = mutable.Map[JobKey, () => Unit]()
  class FunctionJob extends Job {
    def execute(context: JobExecutionContext) {
      val f = jobs(context.getJobDetail.getKey)
      f()
    }
  }
}


