package bloop

import java.io.File
import java.net.URLClassLoader
import java.nio.file.{Files, Path}
import java.nio.file.attribute.BasicFileAttributes
import java.util.Properties

import bloop.internal.build.BloopScalaInfo
import bloop.logging.Logger

final class ScalaInstance private (
    val organization: String,
    val name: String,
    override val version: String,
    override val allJars: Array[File]
) extends xsbti.compile.ScalaInstance {
  override val compilerJar: File =
    allJars.find(f => isJar(f.getName) && hasScalaCompilerName(f.getName)).orNull
  override val libraryJar: File =
    allJars.find(f => isJar(f.getName) && hasScalaLibraryName(f.getName)).orNull
  override val otherJars: Array[File] = allJars.filter { file =>
    val filename = file.getName
    isJar(filename) && !hasScalaCompilerName(filename) && !hasScalaLibraryName(filename)
  }

  /** Is this `ScalaInstance` using Dotty? */
  def isDotty: Boolean =
    organization == "ch.epfl.lamp" && sbt.internal.inc.ScalaInstance.isDotty(version)

  override lazy val loaderLibraryOnly: ClassLoader =
    new URLClassLoader(Array(libraryJar.toURI.toURL), null)
  override lazy val loader: ClassLoader = {
    // For some exceptionally weird reason, we need to load all jars for dotty here
    val jarsToLoad = if (isDotty) allJars else allJars.filterNot(_ == libraryJar)
    new URLClassLoader(jarsToLoad.map(_.toURI.toURL), loaderLibraryOnly)
  }

  import ScalaInstance.ScalacCompilerName
  private def isJar(filename: String): Boolean = filename.endsWith(".jar")
  private def hasScalaCompilerName(filename: String): Boolean =
    filename.startsWith(ScalacCompilerName) || (isDotty && filename.startsWith("dotty-compiler"))
  private def hasScalaLibraryName(filename: String): Boolean =
    filename.startsWith("scala-library")

  /** Tells us what the real version of the classloaded scalac compiler in this instance is. */
  override def actualVersion(): String = {
    // TODO: Report when the `actualVersion` and the passed in version do not match.
    ScalaInstance.getVersion(loader)
  }

  override def equals(obj: Any): Boolean = obj match {
    case other: ScalaInstance => other.hashCode == hashCode
    case _ => false
  }

  override val hashCode: Int = {
    val attributedJars =
      allJars.toSeq.map { jar =>
        val attrs = Files.readAttributes(jar.toPath, classOf[BasicFileAttributes])
        (jar, attrs.lastModifiedTime(), attrs.size())
      }
    attributedJars.hashCode()
  }
}

object ScalaInstance {
  import bloop.io.AbsolutePath

  private[ScalaInstance] final val ScalacCompilerName = "scala-compiler"

  /**
   * Reuses all jars to create an Scala instance if and only if all of them exist.
   *
   * This is done mainly by performance reasons, since dependency resolution is not
   * in the scope of what bloop is supposed to do. All resolution should be done by the user.
   *
   * When this is not the case, we resolve the Scala jars from coursier. This is good
   * because it means that if for some reason the scala jars do not exist, the user
   * will get no matter what get the right instance. If the jars don't exist and they
   * cannot be resolved, users will get a resolution error instead of a weird compilation
   * error when compilation via Zinc starts.
   */
  def apply(scalaOrg: String,
            scalaName: String,
            scalaVersion: String,
            allJars: Array[AbsolutePath],
            logger: Logger): ScalaInstance = {
    val jarsKey = allJars.map(_.underlying).sortBy(_.toString).toList
    if (allJars.nonEmpty) {
      def newInstance = {
        logger.debug(s"Cache miss for scala instance ${scalaOrg}:${scalaName}:${scalaVersion}.")
        jarsKey.foreach(p => logger.debug(s"  => $p"))
        new ScalaInstance(scalaOrg, scalaName, scalaVersion, allJars.map(_.toFile))
      }

      val nonExistingJars = allJars.filter(j => !Files.exists(j.underlying))
      nonExistingJars.foreach(p => logger.warn(s"Scala instance jar ${p.syntax} doesn't exist!"))
      instancesByJar.computeIfAbsent(jarsKey, _ => newInstance)
    } else resolve(scalaOrg, scalaName, scalaVersion, logger)
  }

  // Cannot wait to use opaque types for this
  import java.util.concurrent.ConcurrentHashMap
  type InstanceId = (String, String, String)
  private val instancesById = new ConcurrentHashMap[InstanceId, ScalaInstance]
  private val instancesByJar = new ConcurrentHashMap[List[Path], ScalaInstance]
  def resolve(
      scalaOrg: String,
      scalaName: String,
      scalaVersion: String,
      logger: Logger
  ): ScalaInstance = {
    def resolveInstance: ScalaInstance = {
      val allPaths = DependencyResolution.resolve(scalaOrg, scalaName, scalaVersion, logger)
      val allJars = allPaths.collect {
        case path if path.underlying.toString.endsWith(".jar") => path.underlying.toFile
      }
      new ScalaInstance(scalaOrg, scalaName, scalaVersion, allJars.toArray)
    }

    val instanceId = (scalaOrg, scalaName, scalaVersion)
    instancesById.computeIfAbsent(instanceId, _ => resolveInstance)
  }

  /** Returns the default scala instance that is used in bloop's classloader. */
  def bloopScalaInstance(logger: Logger): ScalaInstance = {
    // This should be changed in the unlikely event the bloop codebase compiles with dotty
    ScalaInstance(
      BloopScalaInfo.scalaOrganization,
      ScalacCompilerName,
      BloopScalaInfo.scalaVersion,
      BloopScalaInfo.scalaJars.iterator.map(AbsolutePath(_)).toArray,
      logger
    )
  }

  def getVersion(loader: ClassLoader): String = {
    val version = Option(loader.getResource("compiler.properties")).flatMap { url =>
      val stream = url.openStream()
      val properties = new Properties()
      properties.load(stream)
      Option(properties.get("version.number").asInstanceOf[String])
    }
    version.getOrElse(s"Loader $loader doesn't have Scala in it!")
  }
}
