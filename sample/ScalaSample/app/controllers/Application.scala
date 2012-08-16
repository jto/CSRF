package controllers

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
    println("ACTION COOKIES: " + request.cookies)
    println("ACTION SESSION: " + request.session)
    Ok(views.html.index(request.session.get("csrfToken").getOrElse("")))
  }
  
  def save = Action{ implicit request =>
    val (name, age) = loginForm.bindFromRequest.get
    Ok(Json.toJson(Map("name" -> name, "age" -> age)))
  }
  
}