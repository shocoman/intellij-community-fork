// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.settingsRepository

import com.intellij.configurationStore.*
import com.intellij.configurationStore.schemeManager.ROOT_CONFIG
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.stateStore
import com.intellij.util.containers.forEachGuaranteed
import com.intellij.util.io.directoryStreamIfExists
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

fun copyLocalConfig(storageManager: StateStorageManagerImpl = ApplicationManager.getApplication()!!.stateStore.storageManager as StateStorageManagerImpl) {
  val streamProvider = storageManager.compoundStreamProvider.getInstanceOf(IcsManager.IcsStreamProvider::class.java) as IcsManager.IcsStreamProvider

  val fileToItems = getExportableItemsFromLocalStorage(getExportableComponentsMap(false), storageManager)
  fileToItems.keys.forEachGuaranteed { file ->
    var fileSpec: String
    try {
      val absolutePath = file.toAbsolutePath().invariantSeparatorsPathString
      fileSpec = normalizeFileSpec(storageManager, absolutePath)
      if (fileSpec == absolutePath) {
        // we have not experienced such problem yet, but we are just aware
        val canonicalPath = file.toRealPath().invariantSeparatorsPathString
        if (canonicalPath != absolutePath) {
          fileSpec = normalizeFileSpec(storageManager, absolutePath)
        }
      }
    }
    catch (e: NoSuchFileException) {
      return@forEachGuaranteed
    }

    val roamingType = fileToItems.get(file)?.firstOrNull()?.roamingType ?: RoamingType.DEFAULT
    if (file.isRegularFile()) {
      val fileBytes = file.readBytes()
      streamProvider.doSave(fileSpec, fileBytes, roamingType)
    }
    else {
      saveDirectory(file, fileSpec, roamingType, streamProvider)
    }
  }
}

private fun normalizeFileSpec(storageManager: StateStorageManagerImpl, absolutePath: String): String {
  return removeMacroIfStartsWith(removeMacroIfStartsWith(storageManager.collapseMacro(absolutePath), ROOT_CONFIG), APP_CONFIG)
}

private fun saveDirectory(parent: Path, parentFileSpec: String, roamingType: RoamingType, streamProvider: IcsManager.IcsStreamProvider) {
  parent.directoryStreamIfExists {
    for (file in it) {
      val childFileSpec = "$parentFileSpec/${file.fileName}"
      if (file.isRegularFile()) {
        val fileBytes = Files.readAllBytes(file)
        streamProvider.doSave(childFileSpec, fileBytes, roamingType)
      }
      else {
        saveDirectory(file, childFileSpec, roamingType, streamProvider)
      }
    }
  }
}
