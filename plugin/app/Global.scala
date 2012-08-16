// Copyright 2012 Julien Tournay
// 
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// 
//    http://www.apache.org/licenses/LICENSE-2.0
// 
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

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
	def checkRequest(request: RequestHeader): Either[PlainResult, RequestHeader] = {
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
		case r: AsyncResult => r.transform(addSessionToken(req, _, token))
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
	* Add token to the request if necessary (token not yet in session)
	*/
	def addToken(request: RequestHeader, token: Token): RequestHeader = request.session.get(TOKEN_NAME)
		.map(_ => request)
		.getOrElse(new WrappedRequest(Request[AnyContent](request, null)) { // XXX: ouuucchhhh
			override lazy val session = request.session + (TOKEN_NAME -> token)
		})
}

object CSRFFilter extends Filter {
	import CSRF._
	override def apply(next: RequestHeader => Result)(request: RequestHeader): Result = {
		lazy val token = CSRF.generate
		checkRequest(request)
			.right.map { r =>
			  import scala.concurrent.ExecutionContext.Implicits.global // I have no idea why this is required, and what it's doing
				val requestWithToken = addToken(r, token)
				println(requestWithToken.session)
				addSessionToken(request, next(requestWithToken), token)
			}
			.fold(identity, identity)
	}
}

/**
* Default global, use this if CSRF is your only Filter
*/
object Global extends WithFilters(CSRFFilter) with GlobalSettings {
	override def doFilter(a:EssentialAction): EssentialAction = {
		Filters(a, CSRFFilter)
	}
}