
import play.api.mvc._
import play.api._

import jto.scala.filters._
import jto.scala.csrf._

object Global extends GlobalSettings {
	import CSRF._
	override def onRouteRequest(request: RequestHeader): Option[Handler] = {
		Filters(super.onRouteRequest(request), CSRFFilter)
	}
}