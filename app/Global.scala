package jto.scala.csrf

import jto.scala.filters._

import play.api.mvc._
import Results._
import play.api._
import play.api.data._
import validation._
import Forms._
import java.security.SecureRandom
import org.apache.commons.codec.binary._

object CSRF {
	type Token = String
	
	val TOKEN_NAME = "csrfToken"
	val encoder = new Hex
	val random = new SecureRandom

	val INVALID_TOKEN: PlainResult  = BadRequest("Invalid CSRF Token")
	var UNSAFE_METHOD = "PUT|POST|DELETE".r

	def generate: Token = {
		val bytes = new Array[Byte](64)
		random.nextBytes(bytes)
		new String(encoder.encode(bytes), "UTF8")
	}

	def checkTokens(paramToken: String, sessionToken: String) = paramToken == sessionToken

	// -
	def checkRequest[A](request: Request[A]): Either[PlainResult, Request[A]] = {
		request.method match {
			case UNSAFE_METHOD() => {
				(for{ maybeTokens <- request.queryString.get(TOKEN_NAME);
					token <- maybeTokens.headOption;
					sessionToken <- request.session.get(TOKEN_NAME)
				} yield if(checkTokens(token, sessionToken)) Right(request) else Left(INVALID_TOKEN)) getOrElse Left(INVALID_TOKEN)
			}
			case _ => Right(request)
		}
	}

	/**
	* Add the session token to the Response if it's not already in the request
	*/
	def addSessionToken(req: RequestHeader, res: Result, token: Token): Result = res match {
		case r: PlainResult => addSessionToken(req, r, token)
		case r => r
	}
	def addSessionToken(req: RequestHeader, r: PlainResult, token: Token): PlainResult = {
		if(req.session.get(TOKEN_NAME).isDefined){
			r
		}
		else {
			val session = Cookies(r.header.headers.get("Set-Cookie"))
				.get(Session.COOKIE_NAME).map(_.value).map(Session.decode)
				.getOrElse(Map.empty)
			val newSession = if(session.contains(TOKEN_NAME)) session else (session + (TOKEN_NAME -> token))
			r.withSession(Session.deserialize(newSession))
		}
	}

	/**
	* Add token to the request if necessary
	*/
	def addToken[A](request: Request[A], token: Token): Request[A] = request.session.get(TOKEN_NAME)
		.map(_ => request)
		.getOrElse(new WrappedRequest(request) {
			override lazy val session = request.session + (TOKEN_NAME -> token) // TODO
		})

	private[csrf] case class EmptyRequest(r: RequestHeader) extends Request[Unit]{
		override def uri = r.uri
		override def path = r.path
		override def method = r.method
		override def queryString = r.queryString
		override def headers = r.headers
		override def remoteAddress = r.remoteAddress
		override def body = ()
	}

}

object CSRFFilter extends Filter {
	import CSRF._
	override def apply[A](next: Request[A] => Result)(request: Request[A]): Result = {
		lazy val token = CSRF.generate
		checkRequest(request)
			.right.map { r =>
				val requestWithToken = addToken(r, token)
				next(requestWithToken) match {
					case ar: AsyncResult => AsyncResult(ar.result.map(addSessionToken(request, _, token)))
					case result => addSessionToken(request, result, token)
				}
			}
			.fold(identity, identity)
	}
}

/**
* Default global, use this if CSRF is your only Filter
*/
object Global extends GlobalSettings {
	import CSRF._
	override def onRouteRequest(request: RequestHeader): Option[Handler] = {
		Filters(super.onRouteRequest(request), CSRFFilter)
	}
}