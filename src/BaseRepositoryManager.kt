package org.jetbrains.settingsRepository

import com.intellij.openapi.util.io.FileUtil

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import com.intellij.openapi.vcs.merge.MultipleFileMergeDialog
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vcs.merge.MergeDialogCustomizer
import com.intellij.openapi.vcs.merge.MergeProvider
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.PathUtilRt
import com.intellij.openapi.fileTypes.StdFileTypes
import com.intellij.openapi.vfs.CharsetToolkit
import java.io.OutputStream
import com.intellij.openapi.application.ApplicationManager

public abstract class BaseRepositoryManager protected() : RepositoryManager {
  protected var dir: File = File(getPluginSystemDir(), "repository")

  protected val lock: Any = Object();

  override fun listSubFileNames(path: String): Collection<String> {
    val files = File(dir, path).list()
    if (files == null || files.size == 0) {
      return listOf()
    }
    return listOf(*files)
  }

  override fun deleteRepository() {
    FileUtil.delete(dir)
  }

  throws(javaClass<IOException>())
  override fun read(path: String): InputStream? {
    val file = File(dir, path)
    //noinspection IOResourceOpenedButNotSafelyClosed
    return if (file.exists()) FileInputStream(file) else null
  }

  override fun write(path: String, content: ByteArray, size: Int, async: Boolean) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Write " + path)
    }

    try {
      val file = File(dir, path)
      FileUtil.writeToFile(file, content, 0, size)

      synchronized (lock) {
        addToIndex(file, path, content, size)
      }
    }
    catch (e: Exception) {
      LOG.error(e)
    }
  }

  /**
   * path relative to repository root
   */
  throws(javaClass<Exception>())
  protected abstract fun addToIndex(file: File, path: String, content: ByteArray, size: Int)

  override fun delete(path: String) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Remove " + path)
    }

    try {
      val file = File(dir, path)
      // delete could be called for non-existent file
      if (!file.exists()) {
        return
      }

      val isFile = file.isFile()
      removeFileAndParentDirectoryIfEmpty(file, isFile, dir)

      synchronized (lock) {
        deleteFromIndex(path, isFile)
      }
    }
    catch (e: Exception) {
      LOG.error(e)
    }
  }

  throws(javaClass<Exception>())
  protected abstract fun deleteFromIndex(path: String, isFile: Boolean)

  override fun has(path: String): Boolean {
    return File(dir, path).exists()
  }
}

fun removeFileAndParentDirectoryIfEmpty(file: File, isFile: Boolean, root: File) {
  FileUtil.delete(file)

  if (isFile) {
    // remove empty directories
    var parent: File? = file.getParentFile()
    //noinspection FileEqualsUsage
    while (parent != null && parent != root && parent!!.delete()) {
      parent = parent!!.getParentFile()
    }
  }
}

fun resolveConflicts(files: List<VirtualFile>, mergeProvider: MergeProvider): List<VirtualFile> {
  if (ApplicationManager.getApplication()!!.isUnitTestMode()) {
    for (file in files) {
      val mergeData = mergeProvider.loadRevisions(file)
      if (String(mergeData.CURRENT!!, CharsetToolkit.UTF8_CHARSET) == "reset to my") {
        file.setBinaryContent(mergeData.CURRENT!!)
      }
      else if (String(mergeData.LAST!!, CharsetToolkit.UTF8_CHARSET) == "reset to my") {
        file.setBinaryContent(mergeData.LAST!!)
      }
      mergeProvider.conflictResolvedForFile(file)
    }

    return files
  }

  var processedFiles: List<VirtualFile>? = null
  invokeAndWaitIfNeed {
    val fileMergeDialog = MultipleFileMergeDialog(null, files, mergeProvider, MergeDialogCustomizer())
    fileMergeDialog.show()
    processedFiles = fileMergeDialog.getProcessedFiles()
  }
  return processedFiles!!
}

class RepositoryVirtualFile(private val path: String) : LightVirtualFile(PathUtilRt.getFileName(path), StdFileTypes.XML, "", CharsetToolkit.UTF8_CHARSET, 1L) {
  var content: ByteArray? = null
    private set

  override fun getPath() = path

  override fun setBinaryContent(content: ByteArray, newModificationStamp: Long, newTimeStamp: Long, requestor: Any?) {
    $content = content
  }

  override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream {
    throw IllegalStateException("You must use setBinaryContent")
  }

  override fun setContent(requestor: Any?, content: CharSequence?, fireEvent: Boolean) {
    throw IllegalStateException("You must use setBinaryContent")
  }
}