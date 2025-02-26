// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.debugger.coroutine

import com.intellij.debugger.actions.AsyncStacksToggleAction
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.engine.SuspendManagerUtil
import com.intellij.debugger.engine.evaluation.*
import com.intellij.debugger.engine.evaluation.expression.ExpressionEvaluator
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.execution.ui.layout.impl.RunnerContentUi
import com.intellij.execution.ui.layout.impl.RunnerLayoutUiImpl
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.sun.jdi.LongValue
import com.sun.jdi.ObjectReference
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.base.util.KotlinPlatformUtils
import org.jetbrains.kotlin.idea.debugger.base.util.evaluate.DefaultExecutionContext
import org.jetbrains.kotlin.idea.debugger.core.StackFrameInterceptor
import org.jetbrains.kotlin.idea.debugger.core.stepping.ContinuationFilter
import org.jetbrains.kotlin.idea.debugger.coroutine.data.SuspendExitMode
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.SkipCoroutineStackFrameProxyImpl
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.mirror.BaseContinuationImplLight
import org.jetbrains.kotlin.idea.debugger.coroutine.util.*
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

class CoroutineStackFrameInterceptor(val project: Project) : StackFrameInterceptor {
    private var sequenceNumberExtractor: ExpressionEvaluator? = null

    override fun createStackFrame(frame: StackFrameProxyImpl, debugProcess: DebugProcessImpl): XStackFrame? {
        if (debugProcess.xdebugProcess?.session is XDebugSessionImpl
            && frame !is SkipCoroutineStackFrameProxyImpl
            && AsyncStacksToggleAction.isAsyncStacksEnabled(debugProcess.xdebugProcess?.session as XDebugSessionImpl)) {
            val suspendContextImpl = SuspendManagerUtil.getContextForEvaluation(debugProcess.suspendManager)
            val stackFrame = suspendContextImpl?.let {
                CoroutineFrameBuilder.coroutineExitFrame(frame, it)
            }

            if (stackFrame != null) {
                showCoroutinePanel(debugProcess)
            }

            return stackFrame
        }
        return null
    }

    private fun showCoroutinePanel(debugProcess: DebugProcessImpl) {
        val ui = debugProcess.session.xDebugSession?.ui.safeAs<RunnerLayoutUiImpl>() ?: return
        val runnerContentUi = RunnerContentUi.KEY.getData(ui) ?: return
        runInEdt {
            runnerContentUi.findOrRestoreContentIfNeeded(CoroutineDebuggerContentInfo.XCOROUTINE_THREADS_CONTENT)
        }
    }

    override fun extractContinuationFilter(suspendContext: SuspendContextImpl): ContinuationFilter? {
        val frameProxy = suspendContext.getStackFrameProxyImpl() ?: return null
        val evaluationContext = EvaluationContextImpl(suspendContext, frameProxy)

        if (Registry.`is`("debugger.filter.breakpoints.by.coroutine.id.with.evaluation") &&
            !useContinuationObjectFilter.get(suspendContext.debugProcess, false)) {
            try {
                val coroutineId = extractContinuationId(evaluationContext)
                if (coroutineId != null) {
                    if (coroutineId <= 0L) return null
                    return ContinuationIdFilter(coroutineId)
                }
            }
            catch (e: EvaluateException) {
                // go lower
            }
            useContinuationObjectFilter.set(suspendContext.debugProcess, true)
            thisLogger().error("Cannot extract continuation from thread")
        }
        val defaultExecutionContext = DefaultExecutionContext(evaluationContext)
        return continuationObjectFilter(suspendContext, defaultExecutionContext)
    }

    private fun extractContinuationId(evaluationContext: EvaluationContextImpl): Long? {
        if (sequenceNumberExtractor == null) {
            sequenceNumberExtractor = runReadAction { buildExpression() }
        }

        return (sequenceNumberExtractor?.evaluate(evaluationContext) as? LongValue)?.longValue()
    }

    private fun continuationObjectFilter(
        suspendContext: SuspendContextImpl,
        defaultExecutionContext: DefaultExecutionContext
    ): ContinuationObjectFilter? {
        val frameProxy = suspendContext.getStackFrameProxyImpl() ?: return null
        val suspendExitMode = frameProxy.location().getSuspendExitMode()

        val continuation = extractContinuation(suspendExitMode, frameProxy) ?: return null

        val baseContinuation = extractBaseContinuation(continuation, defaultExecutionContext) ?: return null

        return ContinuationObjectFilter(baseContinuation)
    }

    private fun extractContinuation(mode: SuspendExitMode, frameProxy: StackFrameProxyImpl): ObjectReference? = when (mode) {
        SuspendExitMode.SUSPEND_LAMBDA -> frameProxy.thisVariableValue()
        SuspendExitMode.SUSPEND_METHOD_PARAMETER -> frameProxy.completionVariableValue() ?: frameProxy.continuationVariableValue()
        else -> null
    }

    private fun extractBaseContinuation(
        continuation: ObjectReference,
        defaultExecutionContext: DefaultExecutionContext
    ): ObjectReference? {
        val baseContinuationImpl = BaseContinuationImplLight(defaultExecutionContext)
        var loopContinuation = continuation
        while (true) {
            val continuationMirror = baseContinuationImpl.mirror(loopContinuation, defaultExecutionContext) ?: return null
            val nextContinuation = continuationMirror.nextContinuation
            if (nextContinuation == null) {
                return continuationMirror.coroutineOwner
            }
            loopContinuation = nextContinuation
        }
    }

    private fun SuspendContextImpl.getStackFrameProxyImpl(): StackFrameProxyImpl? =
        activeExecutionStack?.threadProxy?.frame(0) ?: this.frameProxy

    private data class ContinuationIdFilter(val id: Long) : ContinuationFilter

    private fun constructIdExtractor(): TextWithImports {
        val text = """
            val dumpCoroutinesInfo = kotlinx.coroutines.debug.internal.DebugProbesImpl.dumpCoroutinesInfo()
            var id = -1L
            val thread = Thread.currentThread()
            for (it in dumpCoroutinesInfo) {
                if (it.lastObservedThread == thread) {
                    id = it.sequenceNumber
                }
            }
            id
        """.trimIndent()
        return TextWithImportsImpl(CodeFragmentKind.EXPRESSION, text, "", KotlinFileType.INSTANCE)
    }

    private fun buildExpression(): ExpressionEvaluator {
        val psiFacade = JavaPsiFacade.getInstance(project)
        val findClasses = psiFacade.findClasses("kotlinx.coroutines.debug.internal.DebugProbesImpl", GlobalSearchScope.allScope(project))
        val psiContext = findClasses[0]
        val fragmentFactory = CodeFragmentFactory.EXTENSION_POINT_NAME.extensionList.first { it.fileType == KotlinFileType.INSTANCE }
        val codeFragment = fragmentFactory.createCodeFragment(constructIdExtractor(), psiContext, project)
        // do not log this fragment compilation in tests, because is it an implementation details
        codeFragment.putUserData(KotlinPlatformUtils.suppressCodeFragmentCompilationLogging, true)
        return fragmentFactory.getEvaluatorBuilder().build(codeFragment, null)
    }

    // Is used when there is no debug information about unique Continuation ID (for example, for the old versions)
    private data class ContinuationObjectFilter(val reference: ObjectReference) : ContinuationFilter

    companion object {
        @JvmStatic
        private val useContinuationObjectFilter = Key.create<Boolean>("useContinuationObjectFilter")
    }
}
