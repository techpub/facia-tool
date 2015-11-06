package switchboard

import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model._
import play.api.libs.json.Json
import scala.io.{Codec, Source}
import services.AwsEndpoints
import play.api.Logger
import com.fasterxml.jackson.core.JsonParseException

class S3client (conf: SwitchboardConfiguration) {

  lazy val bucket = conf.bucket
  lazy val objectKey = conf.objectKey

  lazy val client: AmazonS3Client = {
    val client = new AmazonS3Client(conf.credentials)
    client.setEndpoint(AwsEndpoints.s3)
    client
  }

  def get(): Option[Map[String, Boolean]] = {
    try {
      val request = new GetObjectRequest(bucket, objectKey)
      val result = client.getObject(request)

      // http://stackoverflow.com/questions/17782937/connectionpooltimeoutexception-when-iterating-objects-in-s3
      try {
        val resultAsString = Source.fromInputStream(result.getObjectContent).mkString
        val switches = Json.parse(resultAsString).as[Map[String, Boolean]]
        Some(switches)
      }
      catch {
        case e: JsonParseException => {
          Logger.warn("invalid json format at %s - %s" format(bucket, objectKey))
          None
        }
        case e: Exception => {
          throw e
        }
      }
      finally {
        result.close()
      }
    } catch {
      case e: AmazonS3Exception if e.getStatusCode == 404 => {
        Logger.warn("switches status not found at %s - %s" format(bucket, objectKey))
        None
      }
      case e: Exception => {
        throw e
      }
    }
  }
}
