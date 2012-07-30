import sbt._
import Keys._
import PlayProject._

object ApplicationBuild extends Build {

    val appName         = "ScalaSample"
    val appVersion      = "1.0-SNAPSHOT"

    object Repos {
      val artifactory = "http://artifactory.corp.linkedin.com:8081/artifactory/"
      val mavenLocal = Resolver.file("file",  new File(Path.userHome.absolutePath + "/Documents/mvn-repo/snapshots"))
      val sandbox = Resolver.url("Artifactory sandbox", url(artifactory + "ext-sandbox"))(Patterns(
        "[organisation]/[module]/[revision]/[module]-[revision](-[classifier]).[ext]"
      ))
    }

    val appDependencies = Seq(
      "jto" %% "csrf" % "1.1-SNAPSHOT"
    )

    val main = PlayProject(appName, appVersion, appDependencies, mainLang = SCALA).settings(
      organization := "jto",
      resolvers += Repos.sandbox,
      publishTo := Some(Repos.sandbox),
      credentials += Credentials(Path.userHome / ".sbt" / ".licredentials")
    )

}
