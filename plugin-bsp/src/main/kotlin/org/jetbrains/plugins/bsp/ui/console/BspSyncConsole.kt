package org.jetbrains.plugins.bsp.ui.console

import ch.epfl.scala.bsp4j.PublishDiagnosticsParams
import com.intellij.build.BuildProgressListener
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.FilePosition
import com.intellij.build.events.EventResult
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.impl.FileMessageEventImpl
import com.intellij.build.events.impl.FinishBuildEventImpl
import com.intellij.build.events.impl.OutputBuildEventImpl
import com.intellij.build.events.impl.ProgressBuildEventImpl
import com.intellij.build.events.impl.StartBuildEventImpl
import com.intellij.build.events.impl.SuccessResultImpl
import java.io.File
import java.net.URI

public class BspSyncConsole(private val syncView: BuildProgressListener, private val basePath: String) {

  private var syncId: Any = "needToStartId"
  private var inProgress: Boolean = false

  @Synchronized
  public fun startImport(syncId: Any, title: String, message: String): Unit = doUnlessImportInProcess {
    this.syncId = syncId
    this.inProgress = true

    doStartImport(syncId, title, message)
  }

  private fun doStartImport(syncId: Any, title: String, message: String) {
    val buildDescriptor = DefaultBuildDescriptor(syncId, title, basePath, System.currentTimeMillis())
    // TODO one day
    //  .withRestartActions(restartAction)

    val startEvent = StartBuildEventImpl(buildDescriptor, message)
    syncView.onEvent(syncId, startEvent)
  }

  @Synchronized
  public fun finishImport(message: String, result: EventResult): Unit = doIfImportInProcess {
    this.inProgress = false

    doFinishImport(message, result)
  }

  private fun doFinishImport(message: String, result: EventResult) {
    val event = FinishBuildEventImpl(syncId, null, System.currentTimeMillis(), message, result)
    syncView.onEvent(syncId, event)
  }

  @Synchronized
  public fun startSubtask(id: Any, message: String): Unit = doIfImportInProcess {
    val event = ProgressBuildEventImpl(id, syncId, System.currentTimeMillis(), message, -1, -1, "unit")
    syncView.onEvent(syncId, event)
  }

  @Synchronized
  public fun finishSubtask(id: Any, message: String): Unit = doIfImportInProcess {
    val event = FinishBuildEventImpl(id, null, System.currentTimeMillis(), message, SuccessResultImpl())
    syncView.onEvent(syncId, event)
  }

  @Synchronized
  public fun addDiagnosticMessage(params: PublishDiagnosticsParams) {
    params.diagnostics.forEach {
      if (it.message.isNotBlank()) {
        val messageToSend = prepareTextToPrint(it.message)
        val event = FileMessageEventImpl(syncId, MessageEvent.Kind.ERROR, null, messageToSend, null, FilePosition(File(
          URI(params.textDocument.uri)
        ), it.range.start.line, it.range.start.character))
        syncView.onEvent(syncId, event)
      }
    }
  }

  @Synchronized
  public fun addMessage(id: Any?, message: String): Unit = doIfImportInProcess {
    if (message.isNotBlank()) {
      val messageToSend = prepareTextToPrint(message)

      doAddMessage(id, messageToSend)
    }
  }

  private fun doAddMessage(id: Any?, message: String) {
    val event = OutputBuildEventImpl(id, message, true)

    syncView.onEvent(syncId, event)
  }

  @Synchronized
  public fun addWarning() {
    // TODO
  }

  private inline fun doUnlessImportInProcess(action: () -> Unit) {
    if (!inProgress) {
      action()
    }
  }

  private inline fun doIfImportInProcess(action: () -> Unit) {
    if (inProgress) {
      action()
    }
  }

  private fun prepareTextToPrint(text: String): String =
    if (text.endsWith("\n")) text else text + "\n"
}
