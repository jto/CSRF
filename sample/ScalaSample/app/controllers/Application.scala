package controllers

import play.Logger._

import play.api._
import play.api.data._
import play.api.data.Forms._
import play.api.mvc._

import play.api.libs.json._

object Application extends Controller {

  val loginForm = Form(
    tuple(
      "name" -> text,
      "age" -> text
    )
  )
  
  def index = Action { implicit request =>
    import jto.scala.csrf._
    trace("CSRF TOKEN: " + request.session.get(CSRF.TOKEN_NAME))
    Ok(views.html.index(request.session.get(CSRF.TOKEN_NAME).getOrElse("")))
  }
  
  def save = Action{ implicit request =>
    val (name, age) = loginForm.bindFromRequest.get
    Ok(Json.toJson(Map("name" -> name, "age" -> age)))
  }
  
}