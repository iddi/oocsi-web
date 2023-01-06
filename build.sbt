name := """oocsi-web"""
organization := "IndustrialDesign"

version := "0.3.37"

maintainer := "m.funk@tue.nl"

scalaVersion := "2.13.6"

//offline := true

libraryDependencies ++= Seq(
  guice,
  javaWs,
  
  "com.google.inject" % "guice" % "5.0.1"
)


lazy val root = (project in file(".")).enablePlugins(PlayJava, LauncherJarPlugin, JavaAppPackaging)

// Play provides two styles of routers, one expects its actions to be injected, the
// other, legacy style, accesses its actions statically.
routesGenerator := InjectedRoutesGenerator

// Compile the project before generating Eclipse files, so that generated .scala or .class files for views and routes are present
EclipseKeys.preTasks := Seq(compile in Compile)

// Java project. Don't expect Scala IDE
EclipseKeys.projectFlavor := EclipseProjectFlavor.Java
// Use .class files instead of generated .scala files for views and routes 
EclipseKeys.createSrc := EclipseCreateSrc.ValueSet(EclipseCreateSrc.ManagedClasses, EclipseCreateSrc.ManagedResources)

import com.typesafe.sbt.packager.docker.DockerChmodType
import com.typesafe.sbt.packager.docker.DockerPermissionStrategy
dockerChmodType := DockerChmodType.UserGroupWriteExecute
dockerPermissionStrategy := DockerPermissionStrategy.CopyChown

Docker / maintainer := "m.funk@tue.nl"
Docker / packageName := "matsfunk/oocsi-server"
Docker / version := sys.env.getOrElse("BUILD_NUMBER", "0.3.37")
Docker / daemonUserUid  := None
Docker / daemonUser := "daemon"
dockerExposedPorts := Seq(9000,4444)
dockerBaseImage := "openjdk:11.0.11-jre-slim"
dockerRepository := sys.env.get("ecr_repo")
dockerUpdateLatest := true

// don't publish/package source documentation
sources in (Compile, doc) := Seq.empty
publishArtifact in (Compile, packageDoc) := false