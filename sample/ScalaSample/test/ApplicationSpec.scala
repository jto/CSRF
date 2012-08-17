package test

import org.specs2.mutable._

import play.api.test._
import play.api.test.Helpers._

import jto.scala.csrf._

class CSRFSpec extends Specification {
  
  "Application" should {

    "put a CSRF Token in session" in {
      running(FakeApplication(additionalConfiguration = Map("application.secret" -> "42"))) {
        val result = routeAndGet(FakeRequest(GET, "/"))
        result must beSome.which { r =>
          status(r) must equalTo(OK)
          session(r).get(CSRF.TOKEN_NAME) must beSome
        }
      }
    }
    
    "not change token if it's already there" in {
      running(FakeApplication(additionalConfiguration = Map("application.secret" -> "42"))) {
        val withSession = FakeRequest(GET, "/").withSession(CSRF.TOKEN_NAME -> "FAKE_TOKEN")
        routeAndGet(withSession) must beSome.which { r =>
          status(r) must equalTo(OK)
          session(r).get(CSRF.TOKEN_NAME) must beNone
        }
      }
    }

  }
}