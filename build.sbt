name := """oocsi-web"""
organization := "IndustrialDesign"

version := "0.4.5"

maintainer := "m.funk@tue.nl"

scalaVersion := "2.13.13"

libraryDependencies ++= Seq(
  guice,
  javaWs,

  "com.ezylang" % "EvalEx" % "3.4.0"
)

lazy val root = (project in file(".")).enablePlugins(PlayJava, LauncherJarPlugin, JavaAppPackaging)

Compile / unmanagedSourceDirectories += (baseDirectory.value / "../oocsi/server/src")

// Play provides two styles of routers, one expects its actions to be injected, the
// other, legacy style, accesses its actions statically.
routesGenerator := InjectedRoutesGenerator

// Compile the project before generating Eclipse files, so that generated .scala or .class files for views and routes are present
EclipseKeys.preTasks := Seq(Compile / compile)
// Java project. Don't expect Scala IDE
EclipseKeys.projectFlavor := EclipseProjectFlavor.Java
// Use .class files instead of generated .scala files for views and routes 
EclipseKeys.createSrc := EclipseCreateSrc.Default + EclipseCreateSrc.ManagedClasses

import com.typesafe.sbt.packager.docker.DockerChmodType
import com.typesafe.sbt.packager.docker.DockerPermissionStrategy
dockerChmodType := DockerChmodType.UserGroupWriteExecute
dockerPermissionStrategy := DockerPermissionStrategy.CopyChown

Docker / maintainer := "m.funk@tue.nl"
Docker / packageName := "matsfunk/oocsi-server"
Docker / version := sys.env.getOrElse("BUILD_NUMBER", "0.4.3")
Docker / daemonUserUid  := None
Docker / daemonUser := "daemon"
dockerExposedPorts := Seq(9000,4444)
dockerBaseImage := "eclipse-temurin:17-jre-ubi9-minimal"
dockerRepository := sys.env.get("ecr_repo")
dockerUpdateLatest := true

// don't publish/package source documentation
Compile / doc / sources := Seq.empty
Compile / packageDoc / publishArtifact := false