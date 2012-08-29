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

  object Conf {
    import play.api.Play.current
    import scala.collection.JavaConverters._

    def c = Play.configuration

    def TOKEN_NAME: String = c.getString("csrf.token.name").getOrElse("csrfToken")
    def COOKIE_NAME: Option[String] = c.getString("csrf.cookie.name") // If None, we search for TOKEN_NAME in play session
    def POST_LOOKUP: Boolean = c.getBoolean("csrf.tokenInBody").getOrElse(true)
    def CREATE_IF_NOT_FOUND: Boolean = c.getBoolean("csrf.cookie.createIfNotFound").getOrElse(true)
    def UNSAFE_METHOD = c.getStringList("csrf.unsafe.methods").map(_.asScala).getOrElse(List("PUT","POST","DELETE")).mkString("|").r
  }

  import Conf._

  val encoder = new Hex
  val random = new SecureRandom

  val INVALID_TOKEN: PlainResult  = BadRequest("Invalid CSRF Token")

  def generate: Token = {
    val bytes = new Array[Byte](64)
    random.nextBytes(bytes)
    new String(encoder.encode(bytes), "UTF8")
  }

  def checkTokens(paramToken: String, sessionToken: String) = paramToken == sessionToken

  // -
  def checkRequest(request: RequestHeader, body: Option[Map[String, Seq[String]]] = None): Either[PlainResult, RequestHeader] = {
    val maybeToken: Option[Token] = (
      if(POST_LOOKUP)
        body.flatMap(_.get(TOKEN_NAME)).orElse(request.queryString.get(TOKEN_NAME))
      else
        request.queryString.get(TOKEN_NAME)
    ).flatMap(_.headOption)

    request.method match {
      case UNSAFE_METHOD() => {
        (for{ token <- maybeToken;
          cookieToken <- COOKIE_NAME.flatMap(request.cookies.get).map(_.value).orElse(request.session.get(TOKEN_NAME))
        } yield if(checkTokens(token, cookieToken)) Right(request) else Left(INVALID_TOKEN)) getOrElse Left(INVALID_TOKEN)
      }
      case _ => Right(request)
    }
  }

  /**
  * Add the token to the Response (session|cookie) if it's not already in the request
  */
  def addResponseToken(req: RequestHeader, res: Result, token: Token): Result = res match {
    case r: PlainResult => addResponseToken(req, r, token)
    case r: AsyncResult => r.transform(addResponseToken(req, _, token))
  }
  def addResponseToken(req: RequestHeader, r: PlainResult, token: Token): PlainResult = {

    /**
     * Add Token to the Response session if necessary
     */
     def addSessionToken: PlainResult = {
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
     * Add Token to the Response cookies if necessary
     */
     def addCookieToken(c: String): PlainResult = {
       if(req.cookies.get(c).isDefined){
         r
       }
       else {
         val cookies = Cookies(r.header.headers.get("Set-Cookie"))
         cookies.get(c).map(_ => r).getOrElse(r.withCookies(Cookie(c, token)))
       }
     }

     if(CREATE_IF_NOT_FOUND)
       COOKIE_NAME.map(addCookieToken).getOrElse(addSessionToken)
     else
       r
  }

  /**
  * Extract token fron current request
  */
  def getToken(request: RequestHeader): Option[Token] = COOKIE_NAME
    .flatMap(n => request.cookies.get(n).map(_.value))
    .orElse(request.session.get(TOKEN_NAME))

  /**
  * Add token to the request if necessary (token not yet in session)
  */
  def addRequestToken(request: RequestHeader, token: Token): RequestHeader = {
    def addSessionToken = request.session.get(TOKEN_NAME)
      .map(_ => request)
      .getOrElse(new RequestHeader { // XXX: ouuucchhhh

        val d = Cookies(Some(Cookies.encode(Seq(Cookie(Session.COOKIE_NAME, Session.encode(newSession.data))))))

        def uri = request.uri
        def path = request.path
        def method = request.method
        def queryString = request.queryString
        def remoteAddress = request.remoteAddress

        // Fix Jim's "first request has no token in session" bug
        // when play is copying request object, it's not copying lazy vals
        // session is actually extracted *again* from cookies each time the request is copied
        // We need to reencode session into cookies, into headers, that's painful
        import play.api.http._
        override def headers: Headers = new Headers {
          override def getAll(key: String): Seq[String] = toMap.get(key).flatten.toSeq
          override def keys: Set[String] = toMap.keys.toSet
          override lazy val toMap: Map[String,Seq[String]] = request.headers.toMap - HeaderNames.COOKIE + (HeaderNames.COOKIE -> Seq(cookiesHeader))
          override def data = toMap.toSeq
        }

        lazy val newSession = request.session + (TOKEN_NAME -> token)
        lazy val sc = Cookies.encode(Seq(Cookie(Session.COOKIE_NAME, Session.encode(newSession.data))))
        lazy val cookiesHeader = request.headers.get(HeaderNames.COOKIE).map { c =>
          Cookies.merge(c, Seq(Cookie(Session.COOKIE_NAME, Session.encode(newSession.data))))
        }.getOrElse(sc)
      })

    def addCookieToken(c: String) = request.cookies.get(c)
      .map(_ => request)
      .getOrElse(new RequestHeader { // XXX: ouuucchhhh

        def uri = request.uri
        def path = request.path
        def method = request.method
        def queryString = request.queryString
        def remoteAddress = request.remoteAddress

        import play.api.http._
        override def headers: Headers = new Headers {
          override def getAll(key: String): Seq[String] = toMap.get(key).flatten.toSeq
          override def keys: Set[String] = toMap.keys.toSet
          override lazy val toMap: Map[String,Seq[String]] = request.headers.toMap - HeaderNames.COOKIE + (HeaderNames.COOKIE -> Seq(cookiesHeader))
          override def data = toMap.toSeq
        }

        lazy val sc = Cookies.encode(Seq(Cookie(c, token)))
        lazy val cookiesHeader = request.headers.get(HeaderNames.COOKIE).map { c =>
          Cookies.merge(c, Seq(Cookie(c, token)))
        }.getOrElse(sc)
      })

      if(CREATE_IF_NOT_FOUND)
        COOKIE_NAME.map(addCookieToken).getOrElse(addSessionToken)
      else
        request
  }
}

object CSRFFilter extends EssentialFilter {
  import play.api.libs.iteratee._
  import CSRF._

  def apply(next: EssentialAction): EssentialAction = new EssentialAction {
    def apply(request: RequestHeader): Iteratee[Array[Byte],Result] = {
      lazy val token = CSRF.generate
      import play.api.libs.concurrent.execution.defaultContext
      (Traversable.take[Array[Byte]](102400) &>> Iteratee.consume[Array[Byte]]()).flatMap{ b: Array[Byte] =>
          val eventuallyEither = Enumerator(b).run(BodyParsers.parse.tolerantFormUrlEncoded(request))
          eventuallyEither.map(println)
          Iteratee.flatten(
            eventuallyEither.map{
              _.fold(_ => checkRequest(request), body => checkRequest(request, Some(body)))
                .fold(
                  result => Done(result, Input.Empty: Input[Array[Byte]]),
                  r => Iteratee.flatten(Enumerator(b).apply(next(addRequestToken(r, token)))).map(result => addResponseToken(request, result, token))
                )})
        }

    }
  }
}


/**
* Default global, use this if CSRF is your only Filter
*/
object Global extends WithFilters(CSRFFilter) with GlobalSettings