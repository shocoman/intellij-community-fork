// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.actions

import com.intellij.codeInsight.navigation.activateFileWithPsiElement
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ActionPlaces.PROJECT_VIEW_POPUP
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ex.MessagesEx
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.file.PsiDirectoryFactory
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.base.codeInsight.pathBeforeJavaToKotlinConversion
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.base.util.KotlinPlatformUtils
import org.jetbrains.kotlin.idea.codeinsight.utils.commitAndUnblockDocument
import org.jetbrains.kotlin.idea.configuration.ExperimentalFeatures.NewJ2k
import org.jetbrains.kotlin.idea.core.util.toPsiDirectory
import org.jetbrains.kotlin.idea.core.util.toPsiFile
import org.jetbrains.kotlin.j2k.OldJ2kPostProcessor
import org.jetbrains.kotlin.idea.statistics.ConversionType
import org.jetbrains.kotlin.idea.statistics.J2KFusCollector
import org.jetbrains.kotlin.idea.util.application.executeWriteCommand
import org.jetbrains.kotlin.idea.util.getAllFilesRecursively
import org.jetbrains.kotlin.j2k.*
import org.jetbrains.kotlin.j2k.ConverterSettings.Companion.defaultSettings
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.findDescendantOfType
import java.io.IOException
import kotlin.io.path.notExists
import kotlin.system.measureTimeMillis

class JavaToKotlinAction : AnAction() {
    object Handler {
        val title: String = KotlinBundle.message("action.j2k.name")

        fun convertFiles(
            files: List<PsiJavaFile>,
            project: Project,
            module: Module,
            enableExternalCodeProcessing: Boolean = true,
            askExternalCodeProcessing: Boolean = true,
            forceUsingOldJ2k: Boolean = false,
            settings: ConverterSettings = defaultSettings
        ): List<KtFile> {
            val javaFiles = files.filter { it.virtualFile.isWritable }.ifEmpty { return emptyList() }
            var converterResult: FilesResult? = null

            fun convertWithStatistics() {
                val converter =
                    if (forceUsingOldJ2k) OldJavaToKotlinConverter(project, settings)
                    else J2kConverterExtension.extension(NewJ2k.isEnabled).createJavaToKotlinConverter(project, module, settings)
                val postProcessor =
                    if (forceUsingOldJ2k) OldJ2kPostProcessor()
                    else J2kConverterExtension.extension(NewJ2k.isEnabled).createPostProcessor()
                val progressIndicator = ProgressManager.getInstance().progressIndicator!!

                val conversionTime = measureTimeMillis {
                    converterResult = converter.filesToKotlin(javaFiles, postProcessor, progressIndicator)
                }
                val linesCount = runReadAction {
                    javaFiles.sumOf { StringUtil.getLineBreakCount(it.text) }
                }
                J2KFusCollector.log(ConversionType.FILES, NewJ2k.isEnabled, conversionTime, linesCount, javaFiles.size)
            }

            if (!runSynchronousProcess(project, ::convertWithStatistics)) return emptyList()

            val result = converterResult ?: return emptyList()
            val externalCodeProcessing = result.externalCodeProcessing
            val externalCodeUpdate =
                prepareExternalCodeUpdate(project, externalCodeProcessing, enableExternalCodeProcessing, askExternalCodeProcessing)

            return project.executeWriteCommand(KotlinBundle.message("action.j2k.task.name"), null) {
                CommandProcessor.getInstance().markCurrentCommandAsGlobal(project)

                val newFiles = saveResults(javaFiles, result.results)
                    .map { it.toPsiFile(project) as KtFile }
                    .onEach { it.commitAndUnblockDocument() }

                externalCodeUpdate?.invoke(newFiles)

                PsiDocumentManager.getInstance(project).commitAllDocuments()

                newFiles.singleOrNull()?.let {
                    FileEditorManager.getInstance(project).openFile(it.virtualFile, true)
                }

                newFiles
            }
        }

        private fun prepareExternalCodeUpdate(
            project: Project,
            processing: ExternalCodeProcessing?,
            isEnabled: Boolean,
            shouldAsk: Boolean
        ): ((List<KtFile>) -> Unit)? {
            if (!isEnabled || processing == null) return null

            var result: ((List<KtFile>) -> Unit)? = null
            val question = KotlinBundle.message("action.j2k.correction.required")

            if (!shouldAsk || Messages.showYesNoDialog(project, question, title, Messages.getQuestionIcon()) == Messages.YES) {
                runSynchronousProcess(project) {
                    runReadAction {
                        result = processing.prepareWriteOperation(ProgressManager.getInstance().progressIndicator!!)
                    }
                }
            }

            return result
        }

        private fun runSynchronousProcess(project: Project, process: () -> Unit): Boolean =
            ProgressManager.getInstance().runProcessWithProgressSynchronously(process, title, /* canBeCanceled = */ true, project)

        private fun saveResults(javaFiles: List<PsiJavaFile>, convertedTexts: List<String>): List<VirtualFile> {
            fun uniqueKotlinFileName(javaFile: VirtualFile): String {
                val nioFile = javaFile.fileSystem.getNioPath(javaFile)

                var i = 0
                while (true) {
                    val fileName = javaFile.nameWithoutExtension + (if (i > 0) i else "") + ".kt"
                    if (nioFile == null || nioFile.resolveSibling(fileName).notExists()) return fileName
                    i++
                }
            }

            val result = ArrayList<VirtualFile>()
            for ((psiFile, text) in javaFiles.zip(convertedTexts)) {
                try {
                    val document = PsiDocumentManager.getInstance(psiFile.project).getDocument(psiFile)
                    val errorMessage = when {
                        document == null -> KotlinBundle.message("action.j2k.error.cant.find.document", psiFile.name)
                        !document.isWritable -> KotlinBundle.message("action.j2k.error.read.only", psiFile.name)
                        else -> null
                    }
                    if (errorMessage != null) {
                        val message = KotlinBundle.message("action.j2k.error.cant.save.result", errorMessage)
                        MessagesEx.error(psiFile.project, message).showLater()
                        continue
                    }
                    document!!.replaceString(0, document.textLength, text)
                    FileDocumentManager.getInstance().saveDocument(document)

                    val virtualFile = psiFile.virtualFile
                    if (ScratchRootType.getInstance().containsFile(virtualFile)) {
                        val mapping = ScratchFileService.getInstance().scratchesMapping
                        mapping.setMapping(virtualFile, KotlinFileType.INSTANCE.language)
                    } else {
                        val fileName = uniqueKotlinFileName(virtualFile)
                        virtualFile.putUserData(pathBeforeJavaToKotlinConversion, virtualFile.path)
                        virtualFile.rename(this, fileName)
                    }
                    result += virtualFile
                } catch (e: IOException) {
                    MessagesEx.error(psiFile.project, e.message ?: "").showLater()
                }
            }
            return result
        }

        /**
         * For binary compatibility with third-party plugins.
         */
        fun convertFiles(
            files: List<PsiJavaFile>,
            project: Project,
            module: Module,
            enableExternalCodeProcessing: Boolean = true,
            askExternalCodeProcessing: Boolean = true,
            forceUsingOldJ2k: Boolean = false
        ): List<KtFile> =
            convertFiles(files, project, module, enableExternalCodeProcessing, askExternalCodeProcessing, forceUsingOldJ2k, defaultSettings)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = CommonDataKeys.PROJECT.getData(e.dataContext) ?: return
        val module = e.getData(PlatformCoreDataKeys.MODULE) ?: return
        val javaFiles = getSelectedWritableJavaFiles(e)
        if (javaFiles.isEmpty()) {
            showNothingToConvertErrorMessage(project)
            return
        }
        if (!J2kConverterExtension.extension(NewJ2k.isEnabled).doCheckBeforeConversion(project, module)) return
        if (shouldSkipConversionOfErroneousCode(javaFiles, project)) return
        Handler.convertFiles(javaFiles, project, module)
    }

    private fun getSelectedWritableJavaFiles(e: AnActionEvent): List<PsiJavaFile> {
        val virtualFilesAndDirectories = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return emptyList()
        val project = e.project ?: return emptyList()
        val psiManager = PsiManager.getInstance(project)
        return getAllFilesRecursively(virtualFilesAndDirectories)
            .asSequence()
            .mapNotNull { psiManager.findFile(it) as? PsiJavaFile }
            .filter { it.fileType == JavaFileType.INSTANCE } // skip .jsp files
            .filter { it.isWritable }
            .toList()
    }

    private fun showNothingToConvertErrorMessage(project: Project) {
        val statusBar = WindowManager.getInstance().getStatusBar(project)
        JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(KotlinBundle.message("action.j2k.error.nothing.to.convert"), MessageType.ERROR, null)
            .createBalloon()
            .showInCenterOf(statusBar.component)
    }

    private fun shouldSkipConversionOfErroneousCode(javaFiles: List<PsiJavaFile>, project: Project): Boolean {
        val firstSyntaxError = javaFiles.asSequence().map { it.findDescendantOfType<PsiErrorElement>() }.firstOrNull() ?: return false
        val count = javaFiles.count { PsiTreeUtil.hasErrorElements(it) }
        assert(count > 0)
        val firstFileName = firstSyntaxError.containingFile.name
        val question = when (count) {
            1 -> KotlinBundle.message("action.j2k.correction.errors.single", firstFileName)
            else -> KotlinBundle.message("action.j2k.correction.errors.multiple", firstFileName, count - 1)
        }
        val okText = KotlinBundle.message("action.j2k.correction.investigate")
        val cancelText = KotlinBundle.message("action.j2k.correction.proceed")

        if (Messages.showOkCancelDialog(project, question, Handler.title, okText, cancelText, Messages.getWarningIcon()) == Messages.OK) {
            activateFileWithPsiElement(firstSyntaxError.navigationElement)
            return true
        }

        return false
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = isEnabled(e)
    }

    private fun isEnabled(e: AnActionEvent): Boolean {
        if (KotlinPlatformUtils.isCidr) return false
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return false
        val project = e.project ?: return false
        if (project.isDisposed) return false
        if (e.getData(PlatformCoreDataKeys.MODULE) == null) return false

        fun isWritableJavaFile(file: VirtualFile): Boolean {
            val psiManager = PsiManager.getInstance(project)
            return file.extension == JavaFileType.DEFAULT_EXTENSION && psiManager.findFile(file) is PsiJavaFile && file.isWritable
        }

        fun isWritablePackageDirectory(file: VirtualFile): Boolean {
            val directory = file.toPsiDirectory(project) ?: return false
            return PsiDirectoryFactory.getInstance(project).isPackage(directory) && file.isWritable
        }

        if (e.place != PROJECT_VIEW_POPUP && files.any(::isWritablePackageDirectory)) {
            // If a package is selected, we consider that it may contain Java files,
            // but don't actually check, because this check is recursive and potentially expensive: KTIJ-12688.
            //
            // This logic is disabled for the project view popup to avoid cluttering it.
            return true
        }

        return files.any(::isWritableJavaFile)
    }
}