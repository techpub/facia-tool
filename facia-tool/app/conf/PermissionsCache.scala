package conf

import permissions.Permissions
import play.api.{Logger, Application, GlobalSettings}


trait PermissionsCache extends GlobalSettings {
  override def onStart(app: Application) {
    super.onStart(app)
    Logger.info("starting permissions cache")
    val config = Permissions.config
  }

}
