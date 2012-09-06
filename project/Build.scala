import sbt._
import Keys._
import PlayProject._

object ApplicationBuild extends Build {

    val appName         = "csrf"
    val appVersion      = "2012.08.15.c4c3576.v4"

    object Repos {
      val pattern = Patterns(
        Seq("[organisation]/[module]/[revision]/[module]-[revision](-[classifier]).ivy"),
        Seq("[organisation]/[module]/[revision]/[module]-[revision](-[classifier]).[ext]"),
        true
      )

      val artifactory = "http://artifactory.corp.linkedin.com:8081/artifactory/"
      val mavenLocal = Resolver.file("file",  new File(Path.userHome.absolutePath + "/Documents/mvn-repo/snapshots"))(Resolver.ivyStylePatterns)
      val jtoRepo = Resolver.url("JTO snapshots", url("https://raw.github.com/jto/mvn-repo/master/snapshots"))(Resolver.ivyStylePatterns)
      val sandbox = Resolver.url("Artifactory sandbox", url(artifactory + "ext-sandbox"))(pattern)
      val local = Resolver.file("file",  new File(Path.userHome.absolutePath + "/Desktop"))(pattern) // debug
    }

    val pluginDependencies = Seq(
      "jto" %% "filters" % "2012.08.15.c4c3576.v3" exclude("play", "play"), // versions should match
      "commons-codec" % "commons-codec" % "1.6"
    )

    lazy val plugin = PlayProject(appName, appVersion, pluginDependencies, mainLang = SCALA, path = file("plugin")).settings(
      organization := "jto",
      licenses := Seq("Apache License v2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
      homepage := Some(url("https://github.com/jto/play-filters")),
      resolvers ++= Seq(Repos.sandbox, Repos.jtoRepo),
      publishTo := Some(Repos.sandbox),
      credentials += Credentials(Path.userHome / ".sbt" / ".licredentials"),
      publishMavenStyle := false
    )


    lazy val sample = PlayProject("csrf-sample", appVersion, Seq(), mainLang = SCALA, path = file("sample/ScalaSample")).settings(
      organization := "jto",
      resolvers ++= Seq(Repos.sandbox, Repos.jtoRepo),
      publishTo := Some(Repos.sandbox),
      credentials += Credentials(Path.userHome / ".sbt" / ".licredentials")
    ).dependsOn(plugin)
}