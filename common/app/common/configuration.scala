package common

import java.io.{File, FileInputStream}

import com.amazonaws.AmazonClientException
import com.amazonaws.auth._
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.gu.conf.ConfigurationFactory
import conf.{Configuration, Switches}
import org.apache.commons.io.IOUtils
import play.api.Play
import play.api.Play.current
import play.api.{Configuration => PlayConfiguration, Play}

import scala.util.Try

class BadConfigurationException(msg: String) extends RuntimeException(msg)

class GuardianConfiguration(val application: String, val webappConfDirectory: String = "env") extends Logging {

  case class OAuthCredentials(oauthClientId: String, oauthSecret: String, oauthCallback: String)

  protected val configuration = ConfigurationFactory.getConfiguration(application, webappConfDirectory)

  protected val playConfiguration = play.api.Play.configuration

  private val installVars = new File("/etc/gu/facia-tool.properties") match {
    case f if f.exists => IOUtils.toString(new FileInputStream(f))
    case _ => ""
  }

  private val properties = Properties(installVars)
  private val stageFromProperties = properties.getOrElse("STAGE", "CODE")
  private val stsRoleToAssumeFromProperties = properties.getOrElse("STS_ROLE", "unknown")

  private implicit class OptionalString2MandatoryString(conf: com.gu.conf.Configuration) {
    def getMandatoryStringProperty(property: String) = configuration.getStringProperty(property)
      .getOrElse(throw new BadConfigurationException(s"$property not configured"))
  }

  private implicit class OptionalString2MandatoryWithStage(conf: PlayConfiguration) {
    def getStringFromStage(property: String) =
      playConfiguration.getString(stageFromProperties + "." + property)
        .orElse(playConfiguration.getString(property))
    def getMandatoryStringFromStage(property: String) =
      playConfiguration.getString(stageFromProperties + "." + property)
        .orElse(playConfiguration.getString(property))
        .getOrElse(throw new BadConfigurationException(s"$property not configured for stage " + stageFromProperties))
  }

  object business {
    lazy val stocksEndpoint = configuration.getMandatoryStringProperty("business_data.url")
  }

  object weather {
    lazy val apiKey = configuration.getStringProperty("weather.api.key")
  }

  object indexes {
    lazy val tagIndexesBucket =
      configuration.getMandatoryStringProperty("tag_indexes.bucket")

    lazy val adminRebuildIndexRateInMinutes =
      configuration.getIntegerProperty("tag_indexes.rebuild_rate_in_minutes").getOrElse(60)
  }

  object environment {
    val stage = properties.getOrElse("STAGE", "unknown").toLowerCase

    lazy val projectName = Play.application.configuration.getString("guardian.projectName").getOrElse("frontend")
    lazy val secure = Play.application.configuration.getBoolean("guardian.secure").getOrElse(false)

    lazy val isProd = stage == "prod"
    lazy val isNonProd = List("dev", "code", "gudev").contains(stage.toLowerCase)

    lazy val isPreview = projectName == "preview"
  }

  object switches {
    lazy val key = playConfiguration.getMandatoryStringFromStage("switches.key")
  }

  object healthcheck {
    lazy val properties = configuration.getPropertyNames filter {
      _ matches """healthcheck\..*\.url"""
    }

    lazy val urls = properties map { property =>
      configuration.getStringProperty(property).get
    }
  }

  object debug {
    lazy val enabled: Boolean = configuration.getStringProperty("debug.enabled").map(_.toBoolean).getOrElse(true)
    lazy val beaconUrl: String = configuration.getStringProperty("beacon.url").getOrElse("")
  }

  override def toString = configuration.toString

  case class Auth(user: String, password: String)

  object contentApi {
    val contentApiLiveHost: String = playConfiguration.getMandatoryStringFromStage("content.api.host")

    def contentApiDraftHost: String =
        playConfiguration.getStringFromStage("content.api.draft.host")
          .filter(_ => Switches.FaciaToolDraftContent.isSwitchedOn)
          .getOrElse(contentApiLiveHost)

    lazy val key: Option[String] = playConfiguration.getStringFromStage("content.api.key")
    lazy val timeout: Int = playConfiguration.getInt("content.api.timeout.millis").getOrElse(2000)

    lazy val circuitBreakerErrorThreshold =
      configuration.getIntegerProperty("content.api.circuit_breaker.max_failures").getOrElse(5)

    lazy val circuitBreakerResetTimeout =
      configuration.getIntegerProperty("content.api.circuit_breaker.reset_timeout").getOrElse(20000)

    lazy val previewAuth: Option[Auth] = for {
      user <- playConfiguration.getStringFromStage("content.api.preview.user")
      password <- playConfiguration.getStringFromStage("content.api.preview.password")
    } yield Auth(user, password)
  }

  object ophanApi {
    lazy val key = playConfiguration.getStringFromStage("ophan.api.key")
    lazy val host = playConfiguration.getStringFromStage("ophan.api.host")
  }

  object ophan {
    lazy val jsLocation = configuration.getStringProperty("ophan.js.location").getOrElse("//j.ophan.co.uk/ophan.ng")
    lazy val embedJsLocation = configuration.getStringProperty("ophan.embed.js.location").getOrElse("//j.ophan.co.uk/ophan.embed")
  }

  object omniture {
    lazy val account = configuration.getStringProperty("guardian.page.omnitureAccount").getOrElse("guardiangu-frontend,guardiangu-network")
  }

  object googletag {
    lazy val jsLocation = configuration.getStringProperty("googletag.js.location").getOrElse("//www.googletagservices.com/tag/js/gpt.js")
  }

  object frontend {
    lazy val store = configuration.getMandatoryStringProperty("frontend.store")
    lazy val webEngineersEmail = configuration.getStringProperty("email.web.engineers")
  }

  object site {
    lazy val host = configuration.getStringProperty("guardian.page.host").getOrElse("")
  }

  object cookies {
    lazy val lastSeenKey: String = "lastseen"
    lazy val sessionExpiryTime = configuration.getIntegerProperty("auth.timeout").getOrElse(60000)
  }

  object db {
    lazy val sentry_db_driver = configuration.getStringProperty("db.sentry.driver").getOrElse("")
    lazy val sentry_db_url = configuration.getStringProperty("db.sentry.url").getOrElse("")
    lazy val sentry_db_username = configuration.getStringProperty("db.sentry.user").getOrElse("")
    lazy val sentry_db_password = configuration.getStringProperty("db.sentry.password").getOrElse("")
  }

  object proxy {
    lazy val isDefined: Boolean = hostOption.isDefined && portOption.isDefined

    private lazy val hostOption = Option(System.getenv("proxy_host"))
    private lazy val portOption = Option(System.getenv("proxy_port")) flatMap { _.toIntOption }

    lazy val host: String = hostOption getOrElse {
      throw new IllegalStateException("HTTP proxy host not configured")
    }

    lazy val port: Int = portOption getOrElse {
      throw new IllegalStateException("HTTP proxy port not configured")
    }
  }

  object github {
    lazy val token = configuration.getStringProperty("github.token")
  }

  object ajax {
    lazy val url = configuration.getStringProperty("ajax.url").getOrElse("")
    lazy val nonSecureUrl =
      configuration.getStringProperty("ajax.url").getOrElse("")
    lazy val corsOrigins: Seq[String] = configuration.getStringProperty("ajax.cors.origin").map(_.split(",")
      .map(_.trim).toSeq).getOrElse(Nil)
  }

  object id {
    lazy val url = configuration.getStringProperty("id.url").getOrElse("")
    lazy val apiRoot = configuration.getStringProperty("id.apiRoot").getOrElse("")
    lazy val domain = """^https?://(?:profile\.)?([^/:]+)""".r.unapplySeq(url).flatMap(_.headOption).getOrElse("theguardian.com")
    lazy val apiClientToken = configuration.getStringProperty("id.apiClientToken").getOrElse("")
    lazy val webappUrl = configuration.getStringProperty("id.webapp.url").getOrElse("")
    lazy val oauthUrl = configuration.getStringProperty("id.oauth.url").getOrElse("")
    lazy val membershipUrl = configuration.getStringProperty("id.membership.url").getOrElse("membership.theguardian.com")
    lazy val stripePublicToken =  configuration.getStringProperty("id.membership.stripePublicToken").getOrElse("")
  }

  object static {
    lazy val path =
      if (environment.secure) configuration.getMandatoryStringProperty("static.securePath")
      else configuration.getMandatoryStringProperty("static.path")
  }

  object images {
    lazy val path = configuration.getMandatoryStringProperty("images.path")
    object backends {
      lazy val mediaToken: String = configuration.getMandatoryStringProperty("images.media.token")
      lazy val staticToken: String = configuration.getMandatoryStringProperty("images.static.token")
    }
  }

  object headlines {
    lazy val spreadsheet = configuration.getMandatoryStringProperty("headlines.spreadsheet")
  }

  object assets {
    lazy val path = configuration.getMandatoryStringProperty("assets.path")
  }

  object staticSport {
    lazy val path = configuration.getMandatoryStringProperty("staticSport.path")
  }

  object sport {
    lazy val apiUrl = configuration.getStringProperty("sport.apiUrl").getOrElse(ajax.nonSecureUrl)
  }

  object oas {
    lazy val siteIdHost = configuration.getStringProperty("oas.siteId.host").getOrElse(".guardian.co.uk")
    lazy val url = configuration.getStringProperty("oas.url").getOrElse("http://oas.theguardian.com/RealMedia/ads/")
  }

  object facebook {
    lazy val appId = configuration.getMandatoryStringProperty("guardian.page.fbAppId")
    lazy val imageFallback = "http://static.guim.co.uk/icons/social/og/gu-logo-fallback.png"
  }

  object ios {
    lazy val ukAppId = "409128287"
    lazy val usAppId = "411493119"
  }

  object discussion {
    lazy val apiRoot = configuration.getMandatoryStringProperty("discussion.apiRoot")
    lazy val secureApiRoot = configuration.getMandatoryStringProperty("discussion.secureApiRoot")
    lazy val apiTimeout = configuration.getMandatoryStringProperty("discussion.apiTimeout")
    lazy val apiClientHeader = configuration.getMandatoryStringProperty("discussion.apiClientHeader")
    lazy val url = configuration.getMandatoryStringProperty("discussion.url")
  }

  object witness {
    lazy val witnessApiRoot = configuration.getMandatoryStringProperty("witness.apiRoot")
  }

  object open {
    lazy val ctaApiRoot = configuration.getMandatoryStringProperty("open.cta.apiRoot")
  }

  object interactive {
    lazy val url = "http://interactive.guim.co.uk/next-gen/"
  }

  object javascript {
    // This is config that is avaliable to both Javascript and Scala
    // But does not change across environments
    // See https://issues.scala-lang.org/browse/SI-6723 for why we don't always use ->
    lazy val config: Map[String, String] = Map(
      "googleSearchUrl" -> "//www.google.co.uk/cse/cse.js",
      "idWebAppUrl" -> id.webappUrl,
      "idApiUrl" -> id.apiRoot,
      "idOAuthUrl" -> id.oauthUrl,
      "discussionApiRoot" -> discussion.apiRoot,
      ("secureDiscussionApiRoot", discussion.secureApiRoot),
      "discussionApiClientHeader" -> discussion.apiClientHeader,
      ("ophanJsUrl", ophan.jsLocation),
      ("ophanEmbedJsUrl", ophan.embedJsLocation),
      ("googletagJsUrl", googletag.jsLocation),
      ("membershipUrl", id.membershipUrl),
      ("stripePublicToken", id.stripePublicToken)
    )

    lazy val pageData: Map[String, String] = {
      val keys = configuration.getPropertyNames.filter(_.startsWith("guardian.page."))
      keys.foldLeft(Map.empty[String, String]) {
        case (map, key) => map + (key -> configuration.getMandatoryStringProperty(key))
      }
    }
  }

  object front {
    lazy val config = configuration.getMandatoryStringProperty("front.config")
  }

  object facia {
    lazy val stage = playConfiguration.getString("facia.stage").getOrElse(Configuration.environment.stage)
    lazy val collectionCap: Int = 35
  }

  object faciatool {
    lazy val contentApiPostEndpoint = configuration.getStringProperty("contentapi.post.endpoint")
    lazy val frontPressCronQueue = configuration.getStringProperty("frontpress.sqs.cron_queue_url")
    lazy val frontPressToolQueue = playConfiguration.getStringFromStage("frontpress.sqs.tool_queue_url")
    /** When retrieving items from Content API, maximum number of requests to make concurrently */
    lazy val frontPressItemBatchSize = configuration.getIntegerProperty("frontpress.item_batch_size", 30)
    /** When retrieving items from Content API, maximum number of items to request per concurrent request */
    lazy val frontPressItemSearchBatchSize = {
      val size = configuration.getIntegerProperty("frontpress.item_search_batch_size", 20)
      assert(size <= 100, "Best to keep this less then 50 because of pageSize on search queries")
      size
    }

    lazy val pandomainHost = playConfiguration.getMandatoryStringFromStage("faciatool.pandomain.host")
    lazy val pandomainDomain = playConfiguration.getMandatoryStringFromStage("faciatool.pandomain.domain")
    lazy val pandomainService = playConfiguration.getMandatoryStringFromStage("faciatool.pandomain.service")

    lazy val logStream = playConfiguration.getMandatoryStringFromStage("logging.kinesis.stream")
    lazy val logStreamRegion = playConfiguration.getMandatoryStringFromStage("logging.kinesis.region")
    lazy val logStreamRole = playConfiguration.getMandatoryStringFromStage("logging.kinesis.roleArn")
    lazy val logApp = playConfiguration.getMandatoryStringFromStage("logging.fields.app")
    lazy val logEnabled = playConfiguration.getBoolean("logging.enabled").getOrElse(false)

    lazy val permissionsCache = playConfiguration.getMandatoryStringFromStage("permissions.cache")

    lazy val configBeforePressTimeout: Int = 1000

    val oauthCredentials: Option[OAuthCredentials] =
      for {
        oauthClientId <- configuration.getStringProperty("faciatool.oauth.clientid")
        oauthSecret <- configuration.getStringProperty("faciatool.oauth.secret")
        oauthCallback <- configuration.getStringProperty("faciatool.oauth.callback")
      } yield OAuthCredentials(oauthClientId, oauthSecret, oauthCallback)

    val showTestContainers =
      playConfiguration.getStringFromStage("faciatool.show_test_containers").contains("true")

    lazy val adminPressJobStandardPushRateInMinutes: Int =
      Try(playConfiguration.getStringFromStage("admin.pressjob.standard.push.rate.inminutes").get.toInt)
        .getOrElse(5)

    lazy val adminPressJobHighPushRateInMinutes: Int =
      Try(playConfiguration.getStringFromStage("admin.pressjob.high.push.rate.inminutes").get.toInt)
        .getOrElse(1)

    lazy val adminPressJobLowPushRateInMinutes: Int =
      Try(playConfiguration.getStringFromStage("admin.pressjob.low.push.rate.inminutes").get.toInt)
        .getOrElse(60)

    lazy val faciaToolUpdatesStream: Option[String] = playConfiguration.getStringFromStage("faciatool.updates.stream")

    lazy val sentryPublicDSN = playConfiguration.getStringFromStage("faciatool.sentryPublicDSN")

    val stsRoleToAssume = playConfiguration.getStringFromStage("faciatool.sts.role.to.assume").getOrElse(stsRoleToAssumeFromProperties)
  }

  object memcached {
    lazy val host = configuration.getStringProperty("memcached.host")
  }

  object media {
    lazy val baseUrl = playConfiguration.getStringFromStage("media.base.url");
    lazy val apiUrl = playConfiguration.getStringFromStage("media.api.url");
    lazy val key = playConfiguration.getStringFromStage("media.key");
  }

  object switchBoard {
    val bucket = playConfiguration.getMandatoryStringFromStage("switchboard.bucket")
    val key = playConfiguration.getMandatoryStringFromStage("switchboard.object")
  }

  object aws {

    lazy val region = playConfiguration.getMandatoryStringFromStage("aws.region")
    lazy val bucket = playConfiguration.getMandatoryStringFromStage("aws.bucket")
    lazy val notificationSns: String = configuration.getMandatoryStringProperty("sns.notification.topic.arn")
    lazy val videoEncodingsSns: String = configuration.getMandatoryStringProperty("sns.missing_video_encodings.topic.arn")
    lazy val frontPressSns: Option[String] = configuration.getStringProperty("frontpress.sns.topic")


    def mandatoryCredentials: AWSCredentialsProvider = credentials.getOrElse(throw new BadConfigurationException("AWS credentials are not configured"))
    val credentials: Option[AWSCredentialsProvider] = {
      val provider = new AWSCredentialsProviderChain(
        new EnvironmentVariableCredentialsProvider(),
        new SystemPropertiesCredentialsProvider(),
        new ProfileCredentialsProvider("nextgen"),
        new InstanceProfileCredentialsProvider
      )

      // this is a bit of a convoluted way to check whether we actually have credentials.
      // I guess in an ideal world there would be some sort of isConfigued() method...
      try {
        provider.getCredentials
        Some(provider)
      } catch {
        case ex: AmazonClientException =>
          log.error(ex.getMessage, ex)

          // We really, really want to ensure that PROD is configured before saying a box is OK
          if (Play.isProd) throw ex
          // this means that on dev machines you only need to configure keys if you are actually going to use them
          None
      }
    }
  }

  object pingdom {
    lazy val url = configuration.getMandatoryStringProperty("pingdom.url")
    lazy val user = configuration.getMandatoryStringProperty("pingdom.user")
    lazy val password  = configuration.getMandatoryStringProperty("pingdom.password")
    lazy val apiKey = configuration.getMandatoryStringProperty("pingdom.apikey")
  }

  object riffraff {
    lazy val url = configuration.getMandatoryStringProperty("riffraff.url")
    lazy val apiKey = configuration.getMandatoryStringProperty("riffraff.apikey")
  }

  object formstack {
    lazy val url = configuration.getMandatoryStringProperty("formstack.url")
    lazy val oAuthToken = configuration.getMandatoryStringProperty("formstack.oauthToken")
  }

  object standalone {
    lazy val oauthCredentials: Option[OAuthCredentials] = for {
      oauthClientId <- configuration.getStringProperty("standalone.oauth.clientid")
      // TODO needs the orElse fallback till we roll out new properties files
      oauthSecret <- configuration.getStringProperty("standalone.oauth.secret").orElse(configuration.getStringProperty("preview.oauth.secret"))
      oauthCallback <- configuration.getStringProperty("standalone.oauth.callback")
    } yield OAuthCredentials(oauthClientId, oauthSecret, oauthCallback)
  }

  object pngResizer {
    val cacheTimeInSeconds = configuration.getIntegerProperty("png_resizer.image_cache_time").getOrElse(86400)
    val ttlInSeconds = configuration.getIntegerProperty("png_resizer.image_ttl").getOrElse(86400)
  }

  object pushNotifications {
    val host = configuration.getStringProperty("push_notifications.host").getOrElse("//")
  }
}

object ManifestData {
  lazy val build = ManifestFile.asKeyValuePairs.getOrElse("Build", "DEV").dequote.trim
  lazy val revision = ManifestFile.asKeyValuePairs.getOrElse("Revision", "DEV").dequote.trim
}
