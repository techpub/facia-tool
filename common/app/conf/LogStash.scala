package conf

import ch.qos.logback.classic.{Logger => LogbackLogger, LoggerContext}
import com.gu.logback.appender.kinesis.KinesisAppender
import net.logstash.logback.encoder.LogstashEncoder
import net.logstash.logback.layout.LogstashLayout
import org.slf4j.LoggerFactory
import play.api.{Logger => PlayLogger, LoggerLike}

object LogStash {

  lazy val loggingContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]

  case class KinesisAppenderConfig(stream: String, region: String, roleArn: String, bufferSize: Int)

  lazy val enabled = Configuration.faciatool.logEnabled

  lazy val customFields = Map(
      "stack" -> "fronts",
      "stage" ->Configuration.environment.stage.toUpperCase,
      "app"   -> Configuration.faciatool.logApp
    )
  def makeCustomFields: String = {
    "{" + (for((k, v) <- customFields) yield(s""""${k}":"${v}"""")).mkString(",") + "}"
  }

  def asLogBack(l: LoggerLike): Option[LogbackLogger] = l.logger match {
    case l: LogbackLogger => Some(l)
    case _ => None
  }

  def makeEncoder(context: LoggerContext) = {
    val e = new LogstashEncoder()
    e.setContext(context)
    e.setCustomFields(makeCustomFields)
    e.start()
    e
  }

  def makeLayout(customFields: String) = {
    val l = new LogstashLayout()
    l.setCustomFields(customFields)
    l
  }

  def makeKinesisAppender(layout: LogstashLayout, context: LoggerContext, appenderConfig: KinesisAppenderConfig) = {
    val a = new KinesisAppender()
    a.setStreamName(appenderConfig.stream)
    a.setRegion(appenderConfig.region)
    a.setRoleToAssumeArn(appenderConfig.roleArn)
    a.setBufferSize(appenderConfig.bufferSize)

    a.setContext(context)
    a.setLayout(layout)

    layout.start()
    a.start()
    a
  }

  def init() = {
    if(enabled) {
      PlayLogger.info("LogConfig initializing")
      (for {
        lb <- asLogBack(PlayLogger)
      } yield {
        lb.info("Configuring Logback")
        val context = lb.getLoggerContext
        val layout = makeLayout(makeCustomFields)
        val bufferSize = 1000
        // remove the default configuration
        val appender  = makeKinesisAppender(layout, context,
          KinesisAppenderConfig(
            Configuration.faciatool.logStream,
            Configuration.faciatool.logStreamRegion,
            Configuration.faciatool.logStreamRole,
            bufferSize
          )
        )
        lb.addAppender(appender)
        lb.info("Configured Logback")
      })getOrElse(PlayLogger.info("not running using logback"))
    } else {
      PlayLogger.info("Logging disabled")
    }
  }
}
