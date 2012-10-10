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
    def IGNORED_URLS = c.getStringList("csrf.ignoreIfContains").map(_.asScala).getOrElse(Nil)
  }

  import Conf._

  val encoder = new Hex
  val random = new SecureRandom

  val INVALID_TOKEN: PlainResult  = BadRequest("Invalid CSRF Token")

  def generate: Token = {
    val bytes = new Array[Byte](10)
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

    // Don't apply CSRF checks if the URL is marked as 'ignored'.
    val ignoreCsrfCheck = IGNORED_URLS.exists(ignored => request.path.indexOf(ignored) > 0)

    if(ignoreCsrfCheck) {
      Right(request)
    } else {
      request.method match {
        case UNSAFE_METHOD() => {
          (for{ token <- maybeToken;
            cookieToken <- COOKIE_NAME.flatMap(request.cookies.get).map(_.value).orElse(request.session.get(TOKEN_NAME))
          } yield if(checkTokens(token, cookieToken)) Right(request) else Left(INVALID_TOKEN)) getOrElse Left(INVALID_TOKEN)
        }
        case _ => Right(request)
      }
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

    play.Logger.trace("[CSRF] Adding token to result: " + r)

    /**
     * Add Token to the Response session if necessary
     */
     def addSessionToken: PlainResult = {
       if(req.session.get(TOKEN_NAME).isDefined){
         play.Logger.trace("[CSRF] session already contains token")
         r
       }
       else {
         val session = Cookies(r.header.headers.get("Set-Cookie"))
           .get(Session.COOKIE_NAME).map(_.value).map(Session.decode)
           .getOrElse(Map.empty)
         val newSession = if(session.contains(TOKEN_NAME)) session else (session + (TOKEN_NAME -> token))
         play.Logger.trace("[CSRF] Adding session token to response")
         play.Logger.trace("[CSRF] response was: " + r)
         val resp = r.withSession(Session.deserialize(newSession))
         play.Logger.trace("[CSRF] response is now: " + resp)
         resp
       }
     }

     /**
     * Add Token to the Response cookies if necessary
     */
     def addCookieToken(c: String): PlainResult = {
       if(req.cookies.get(c).isDefined){
         play.Logger.trace("[CSRF] cookie already contains token")
         r
       }
       else {
         val cookies = Cookies(r.header.headers.get("Set-Cookie"))
         play.Logger.trace("[CSRF] Adding cookie token to response")
         play.Logger.trace("[CSRF] response was: " + r)
         val resp = cookies.get(c).map(_ => r).getOrElse(r.withCookies(Cookie(c, token)))
         play.Logger.trace("[CSRF] response is now: " + resp)
         resp
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

    play.Logger.trace("[CSRF] Adding request token to request: " + request)

    def addSessionToken = request.session.get(TOKEN_NAME)
      .map(_ => request)
      .getOrElse(new RequestHeader { // XXX: ouuucchhhh

        def uri = request.uri
        def path = request.path
        def method = request.method
        def queryString = request.queryString
        def remoteAddress = request.remoteAddress
        override def version = request.version
        override def tags = request.tags
        override def id = request.id

        // Fix Jim's "first request has no token in session" bug
        // when play is copying request object, it's not copying lazy vals
        // session is actually extracted *again* from cookies each time the request is copied
        // We need to reencode session into cookies, into headers, that's painful
        import play.api.http._
        override def headers: Headers = new Headers {
          override def getAll(key: String): Seq[String] = toMap.get(key).flatten.toSeq
          override def keys: Set[String] = toMap.keys.toSet
          override lazy val toMap: Map[String,Seq[String]] = request.headers.toMap - HeaderNames.COOKIE + (HeaderNames.COOKIE -> Seq(cookiesHeader))
          def data = toMap.toSeq
        }

        lazy val newSession = request.session + (TOKEN_NAME -> token)
        lazy val sc = Cookies.encode(Seq(Cookie(Session.COOKIE_NAME, Session.encode(newSession.data))))

        play.Logger.trace("[CSRF] adding session token to request: " + newSession)

        lazy val cookiesHeader = request.headers.get(HeaderNames.COOKIE).map { cookies =>
          Cookies.merge(cookies, Seq(Cookie(Session.COOKIE_NAME, Session.encode(newSession.data))))
        }.getOrElse(sc)

        play.Logger.trace("[CSRF] cookies header value in request is now: " + cookiesHeader)
      })

    def addCookieToken(c: String) = request.cookies.get(c)
      .map(_ => request)
      .getOrElse(new RequestHeader { // XXX: ouuucchhhh

        def uri = request.uri
        def path = request.path
        def method = request.method
        def queryString = request.queryString
        def remoteAddress = request.remoteAddress
        override def version = request.version
        override def tags = request.tags
        override def id = request.id

        import play.api.http._
        override def headers: Headers = new Headers {
          override def getAll(key: String): Seq[String] = toMap.get(key).flatten.toSeq
          override def keys: Set[String] = toMap.keys.toSet
          override lazy val toMap: Map[String,Seq[String]] = request.headers.toMap - HeaderNames.COOKIE + (HeaderNames.COOKIE -> Seq(cookiesHeader))
          def data = toMap.toSeq
        }

        play.Logger.trace("[CSRF] adding cookie %s token to request: %s".format(c, token))

        lazy val sc = Cookies.encode(Seq(Cookie(c, token)))
        lazy val cookiesHeader = request.headers.get(HeaderNames.COOKIE).map { cookies =>
          Cookies.merge(cookies, Seq(Cookie(c, token)))
        }.getOrElse(sc)

        play.Logger.trace("[CSRF] cookies header value in request is now: " + cookiesHeader)
      })

      if(CREATE_IF_NOT_FOUND)
        COOKIE_NAME.map(addCookieToken).getOrElse(addSessionToken)
      else
        request
  }
}

class CSRFFilter(generator: () => CSRF.Token) extends EssentialFilter {
  import play.api.libs.Files._
  import play.api.libs.iteratee._
  import CSRF._
  import play.api.libs.concurrent.execution.defaultContext
  import BodyParsers.parse._

  private def checkBody[T](parser: BodyParser[T], extractor: (T => Map[String, Seq[String]]))(request: RequestHeader, token: Token,  next: EssentialAction) = {
    (Traversable.take[Array[Byte]](102400) &>> Iteratee.consume[Array[Byte]]()).flatMap{ b: Array[Byte] =>
        val eventuallyEither = Enumerator(b).run(parser(request))
        Iteratee.flatten(
          eventuallyEither.map{
            _.fold(_ => checkRequest(request), body => checkRequest(request, Some(extractor(body))))
              .fold(
                result => Done(result, Input.Empty: Input[Array[Byte]]),
                r => Iteratee.flatten(Enumerator(b).apply(next(addRequestToken(r, token)))).map(result => addResponseToken(request, result, token))
              )})
      }
  }

  def checkFormUrlEncodedBody = checkBody[Map[String, Seq[String]]](tolerantFormUrlEncoded, identity) _
  def checkMultipart =  checkBody[MultipartFormData[TemporaryFile]](multipartFormData, _.dataParts) _


  def apply(next: EssentialAction): EssentialAction = new EssentialAction {
    def apply(request: RequestHeader): Iteratee[Array[Byte],Result] = {
      import play.api.http.HeaderNames._

      play.Logger.trace("[CSRF] original request: " + request)
      play.Logger.trace("[CSRF] original cookies: " + request.cookies)
      play.Logger.trace("[CSRF] original session: " + request.session)

      val token = generator()
      request.headers.get(CONTENT_TYPE) match {
        case Some(ct) if ct.trim.startsWith("multipart/form-data") => checkMultipart(request, token, next)
        case _ => checkFormUrlEncodedBody(request, token, next)
      }
    }
  }
}

object CSRFFilter {
  def apply() = new CSRFFilter(CSRF.generate _)
  def apply(generator: () => CSRF.Token) = new CSRFFilter(generator)
}


/**
* Default global, use this if CSRF is your only Filter
*/
object Global extends WithFilters(CSRFFilter()) with GlobalSettings