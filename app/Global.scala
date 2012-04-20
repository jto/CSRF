package csrf

import play.api.mvc._
import Results._
import play.api._
import play.api.data._
import validation._
import Forms._
import java.security.SecureRandom

object CSRF {
	val TOKEN_NAME = "csrfToken"
	val random = new SecureRandom()

	def generate = {
		val bytes = new Array[Byte](64)
		random.nextBytes(bytes)
		new String(bytes)
	}

	def checkTokens(paramToken: String, sessionToken: String) = paramToken == sessionToken
}

case class ResponseWrapper[A](action: Action[A]) extends Action[A] {
	import CSRF._

	def addSessionToken(req: RequestHeader, res: Result) = res match {
		case r: PlainResult => {
			if(req.session.get(TOKEN_NAME).isDefined){
				r
			}
			else {
				val session = Cookies(r.header.headers.get("Set-Cookie"))
					.get(Session.COOKIE_NAME).map(_.value).map(Session.decode)
					.getOrElse(Map.empty)
				val newSession = if(session.contains(TOKEN_NAME)) session else (session + (TOKEN_NAME -> generate))
				r.withSession(Session.deserialize(newSession))
			}
		}
		case r => r
	}

	def apply(request: Request[A]): Result = {
		action(request) match {
			case ar: AsyncResult => AsyncResult(ar.result.map(addSessionToken(request, _)))
			case result => addSessionToken(request, result)
		}
	}

	lazy val parser = action.parser
}

object Global extends GlobalSettings {
	import CSRF._

	val f = Form(single(TOKEN_NAME -> nonEmptyText))
	val INVALID_TOKEN  = BadRequest("Invalid CSRF Token")
	var UNSAFE_METHOD = "PUT|POST|DELETE".r

	override def onRouteRequest(request: RequestHeader): Option[Handler] = {
		var parent = super.onRouteRequest(request)

		def delegate = parent.map { h =>
			h match {
				case a: Action[_] => ResponseWrapper(a)
				case _ => h
			}
		}

		request.method match {
			case UNSAFE_METHOD() => Some(Action { req:  Request[AnyContent] =>
				f.bindFromRequest()(req).fold(
					_ => INVALID_TOKEN,
					{ paramToken =>
						req.session.get(TOKEN_NAME).map { sessionToken => 
							if(checkTokens(paramToken, sessionToken)){
								delegate.map{
									case a: Action[_] => a(req)
									case _ => h
								}
							}
							else
								INVALID_TOKEN
						} getOrElse INVALID_TOKEN
					}
				)
			})
			case _ => delegate
		}
	}
}