
import play.api.mvc._
import play.api._

import jto.scala.filters._
import jto.scala.csrf._

object Global extends WithFilters(CSRFFilter()) with GlobalSettings /*{
  override def onRouteRequest(request: RequestHeader): Option[Handler] = {
    Some(Action{Results.BadRequest.withSession("test" -> "bordel")})
  }
}*/