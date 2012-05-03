import sbt._
import Keys._
import PlayProject._

object ApplicationBuild extends Build {

    val appName         = "CSRF"
    val appVersion      = "1.0"

    val appDependencies = Seq(
      "commons-codec" % "commons-codec" % "1.6",
      "filters" %% "filters" % "1.0"
    )

    val main = PlayProject(appName, appVersion, appDependencies, mainLang = SCALA).settings(
      organization := "jto",
      resolvers += "JTO snapshots" at "https://raw.github.com/jto/mvn-repo/master/snapshots",
      publishTo := Some(Resolver.file("file",  new File(Path.userHome.absolutePath + "/Documents/mvn-repo/snapshots")))
    )

}
