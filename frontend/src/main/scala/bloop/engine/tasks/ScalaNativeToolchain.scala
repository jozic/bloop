package bloop.engine.tasks

import java.nio.file.Path

import scala.util.{Failure, Success, Try}
import bloop.Project
import bloop.cli.ExitStatus
import bloop.config.Config
import bloop.config.Config.NativeConfig
import bloop.engine.State
import bloop.exec.Forker
import bloop.internal.build.BuildInfo
import bloop.io.AbsolutePath
import bloop.logging.Logger
import monix.eval.Task

class ScalaNativeToolchain private (classLoader: ClassLoader) {

  /**
   * Compile down to native binary using Scala Native's toolchain.
   *
   * @param config    The native configuration to use.
   * @param project   The project to link
   * @param mainClass The fully qualified main class name
   * @param logger    The logger to use
   * @return The absolute path to the native binary.
   */
  def link(
      config: NativeConfig,
      project: Project,
      mainClass: String,
      target: AbsolutePath,
      logger: Logger
  ): Task[Try[Unit]] = {
    val bridgeClazz = classLoader.loadClass("bloop.scalanative.NativeBridge")
    val paramTypes = classOf[NativeConfig] :: classOf[Project] :: classOf[String] :: classOf[Path] :: classOf[Logger] :: Nil
    val nativeLinkMeth = bridgeClazz.getMethod("nativeLink", paramTypes: _*)

    // The Scala Native toolchain expects to receive the module class' name
    val fullEntry = if (mainClass.endsWith("$")) mainClass else mainClass + "$"
    Task(nativeLinkMeth.invoke(null, config, project, fullEntry, target.underlying, logger)
      .asInstanceOf[Unit]).materialize
  }

  /**
   * Link `project` to a native binary and run it.
   *
   * @param state     The current state of Bloop.
   * @param config    The native configuration to use.
   * @param project   The project to link.
   * @param cwd       The working directory in which to start the process.
   * @param mainClass The fully qualified main class name.
   * @param args      The arguments to pass to the program.
   * @return A task that links and run the project.
   */
  def run(
      state: State,
      config: NativeConfig,
      project: Project,
      cwd: AbsolutePath,
      mainClass: String,
      target: AbsolutePath,
      args: Array[String]
  ): Task[State] = {
    link(config, project, mainClass, target, state.logger).flatMap {
      case Success(_) =>
        val cmd = target.syntax +: args
        Forker.run(cwd, cmd, state.logger, state.commonOptions).map { exitCode =>
          val exitStatus = Forker.exitStatus(exitCode)
          state.mergeStatus(exitStatus)
        }
      case Failure(ex) =>
        Task {
          state.logger.error("Couldn't create native binary.")
          state.logger.trace(ex)
          state.mergeStatus(ExitStatus.LinkingError)
        }
    }
  }
}

object ScalaNativeToolchain extends ToolchainCompanion[ScalaNativeToolchain] {
  override type Platform = Config.Platform.Native
  override def artifactNameFrom(version: String): String = BuildInfo.nativeBridge

  override def apply(classLoader: ClassLoader): ScalaNativeToolchain =
    new ScalaNativeToolchain(classLoader)
}
