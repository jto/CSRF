package test

import org.specs2.mutable._

import play.api.test._
import play.api.test.Helpers._

import jto.scala.csrf._
import jto.scala.csrf.CSRF.Conf._

class CSRFSpec extends Specification {

  val fakeApp = FakeApplication(path = new java.io.File("sample/ScalaSample"))

  val showToken = FakeRequest(GET, "/test/token")
  val postData =  FakeRequest(POST, "/test/post")

  "CSRF module with default configuration" should {

    "put a CSRF Token in session" in running(fakeApp) {
      val result = route(showToken)
      result must beSome.which { r =>
        status(r) must equalTo(OK)
        session(r).get(TOKEN_NAME) must beSome
      }
    }

    "not change token if it's already in session" in running(fakeApp) {
      val withSession = showToken.withSession(TOKEN_NAME -> "FAKE_TOKEN")
      route(withSession) must beSome.which { r =>
        status(r) must equalTo(OK)
        session(r).get(TOKEN_NAME) must beNone
      }
    }

    "stuff first request session with a token" in running(fakeApp) {
       route(showToken) must beSome.which { r =>
        status(r) must equalTo(OK)
        val contentToken = contentAsString(r)
        val sessionToken = session(r).get(TOKEN_NAME)

        sessionToken must beSome
        contentToken must not(beEmpty)

        sessionToken.get must beEqualTo(contentToken)
      }
    }

    "reject POST without token" in running(fakeApp) {
      route(postData, "Hello World!") must beSome.which { r =>
        status(r) must equalTo(BAD_REQUEST)
        session(r).get(TOKEN_NAME) must beNone
      }
    }

    "reject POST without session Token" in running(fakeApp) {
      route(FakeRequest(POST, "/test/post?%s=%s".format(TOKEN_NAME, "FAKE_TOKEN")),
        "Hello World!") must beSome.which { r =>
        status(r) must equalTo(BAD_REQUEST)
        session(r).get(TOKEN_NAME) must beNone
      }
    }

    "reject POST without URL Token" in running(fakeApp) {
      route(postData.withSession(TOKEN_NAME -> "FAKE_TOKEN"), "Hello World!") must beSome.which { r =>
        status(r) must equalTo(BAD_REQUEST)
        session(r).get(TOKEN_NAME) must beNone
      }
    }

    "reject POST with invalid token" in running(fakeApp) {
      route(showToken).flatMap {
        session(_).get(TOKEN_NAME)
      }.flatMap { token =>
        // TODO: Add contructor with ActionRef
        // TODO: Add helper in FakeRequest for GET params
        route(FakeRequest(POST, "/test/post?%s=%s".format(TOKEN_NAME, token.reverse))
          .withSession(TOKEN_NAME -> token), "Hello World!")
      } must beSome.which { r =>
        status(r) must equalTo(BAD_REQUEST)
        session(r).get(TOKEN_NAME) must beNone
      }
    }

    "allow POST with correct token" in running(fakeApp) {
      route(showToken).flatMap {
        session(_).get(TOKEN_NAME)
      }.flatMap { token =>
        route(FakeRequest(POST, "/test/post?%s=%s".format(TOKEN_NAME, token))
          .withSession(TOKEN_NAME -> token), "Hello World!")
      } must beSome.which { r =>
        status(r) must equalTo(OK)
        session(r).get(TOKEN_NAME) must beNone
      }
    }

  }

  val fakeAppWithCookieName = FakeApplication(path = new java.io.File("sample/ScalaSample"),
    additionalConfiguration = Map("csrf.cookie.name" -> "JSESSIONID"))

  "CSRF module with csrf.cookie.name" should {
    "put a CSRF Token in Cookies(CSRF.COOKIES)" in running(fakeAppWithCookieName) {
      import play.api.Play.current
      val result = route(showToken)
      result must beSome.which { r =>
        status(r) must equalTo(OK)
        cookies(r).get("JSESSIONID") must beSome
      }
    }
  }
}