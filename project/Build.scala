import sbt._
import Keys._
import PlayProject._

object ApplicationBuild extends Build {

    val appName         = "CSRF"
    val appVersion      = "1.0-SNAPSHOT"

    val appDependencies = Seq(
      "commons-codec" % "commons-codec" % "1.6"
    )

    val main = PlayProject(appName, appVersion, appDependencies, mainLang = SCALA).settings(
      // Add your own project settings here      
    )

}
