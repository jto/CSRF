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
    println("=== CALLED index")
    import jto.scala.csrf._
    Ok(views.html.index(CSRF.getToken(request).getOrElse("")))
  }

  def save = Action{ implicit request =>
    println("=== CALLED save")
    val (name, age) = loginForm.bindFromRequest.get
    Ok(Json.toJson(Map("name" -> name, "age" -> age)))
  }

}