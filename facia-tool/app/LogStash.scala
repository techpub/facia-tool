import ch.qos.logback.classic.{Logger => LogbackLogger, LoggerContext}
import ch.qos.logback.core.util.Duration
import net.logstash.logback.appender.LogstashTcpSocketAppender
import net.logstash.logback.encoder.LogstashEncoder
import org.slf4j.{Logger, LoggerFactory}
import play.api.{Logger => PlayLogger, LoggerLike}

object LogStash {

  lazy val loggingContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
  import play.api.Play.current

  val config = play.api.Play.configuration

  lazy val enabled = config.getBoolean("logging.logstash.enabled").getOrElse(false)
  lazy val logHostOpt = config.getString("logging.logstash.host")
  lazy val logPortOpt = config.getInt("logging.logstash.port")

  lazy val customFields = (
    for {
      app   <- config.getString("logging.fields.app")
      stage <- config.getString("logging.fields.stage")
    } yield Map(
      "stack" -> "fronts",
      "stage" -> stage.toUpperCase,
      "app"   -> app
    )).getOrElse(Map("logging-error" -> "bad-logging-config"))

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

  def makeTcpAppender(context: LoggerContext, host: String, port: Int) = {
    val a = new LogstashTcpSocketAppender()
    a.setContext(context)
    a.setEncoder(makeEncoder(context))
    a.setKeepAliveDuration(Duration.buildBySeconds(30.0))
    a.setRemoteHost(host)
    a.setPort(port)
    a.start()
    a
  }

  def init() = {
    if(enabled) {
      PlayLogger.info("LogConfig initializing")
      (for {
        logHost <- logHostOpt
        logPort <- logPortOpt
        lb <- asLogBack(PlayLogger)
      } yield {
        lb.info("Configuring Logback")
        val context = lb.getLoggerContext
        // remove the default configuration
        lb.addAppender(makeTcpAppender(context, logHost, logPort))
        lb.info("Configured Logback")
      })getOrElse(PlayLogger.info("not running using logback") )
    } else {
      PlayLogger.info("Logging disabled")
    }
  }
}
