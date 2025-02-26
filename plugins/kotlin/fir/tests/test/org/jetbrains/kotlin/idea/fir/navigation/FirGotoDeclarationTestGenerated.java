// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.fir.navigation;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.idea.test.JUnit3RunnerWithInners;
import org.jetbrains.kotlin.idea.test.KotlinTestUtils;
import org.jetbrains.kotlin.test.TestMetadata;
import org.jetbrains.kotlin.idea.base.test.TestRoot;
import org.junit.runner.RunWith;

/**
 * This class is generated by {@link org.jetbrains.kotlin.testGenerator.generator.TestGenerator}.
 * DO NOT MODIFY MANUALLY.
 */
@SuppressWarnings("all")
@TestRoot("fir/tests")
@TestDataPath("$CONTENT_ROOT")
@RunWith(JUnit3RunnerWithInners.class)
@TestMetadata("../../idea/tests/testData/navigation/gotoDeclaration")
public class FirGotoDeclarationTestGenerated extends AbstractFirGotoDeclarationTest {
    private void runTest(String testDataFilePath) throws Exception {
        KotlinTestUtils.runTest(this::doTest, this, testDataFilePath);
    }

    @TestMetadata("annotationCallWithMissedTypeArgs.test")
    public void testAnnotationCallWithMissedTypeArgs() throws Exception {
        runTest("../../idea/tests/testData/navigation/gotoDeclaration/annotationCallWithMissedTypeArgs.test");
    }

    @TestMetadata("dataClassToString.test")
    public void testDataClassToString() throws Exception {
        runTest("../../idea/tests/testData/navigation/gotoDeclaration/dataClassToString.test");
    }

    @TestMetadata("importAlias.test")
    public void testImportAlias() throws Exception {
        runTest("../../idea/tests/testData/navigation/gotoDeclaration/importAlias.test");
    }

    @TestMetadata("itExtensionLambda.test")
    public void testItExtensionLambda() throws Exception {
        runTest("../../idea/tests/testData/navigation/gotoDeclaration/itExtensionLambda.test");
    }

    @TestMetadata("itExtensionLambdaInBrackets.test")
    public void testItExtensionLambdaInBrackets() throws Exception {
        runTest("../../idea/tests/testData/navigation/gotoDeclaration/itExtensionLambdaInBrackets.test");
    }

    @TestMetadata("itInLambdaAsDefaultArgument.test")
    public void testItInLambdaAsDefaultArgument() throws Exception {
        runTest("../../idea/tests/testData/navigation/gotoDeclaration/itInLambdaAsDefaultArgument.test");
    }

    @TestMetadata("itInLambdaWithoutCall.test")
    public void testItInLambdaWithoutCall() throws Exception {
        runTest("../../idea/tests/testData/navigation/gotoDeclaration/itInLambdaWithoutCall.test");
    }

    @TestMetadata("itParameterInLambda.test")
    public void testItParameterInLambda() throws Exception {
        runTest("../../idea/tests/testData/navigation/gotoDeclaration/itParameterInLambda.test");
    }

    @TestMetadata("labeledThisToClass.test")
    public void testLabeledThisToClass() throws Exception {
        runTest("../../idea/tests/testData/navigation/gotoDeclaration/labeledThisToClass.test");
    }

    @TestMetadata("labeledThisToMemberExtension.test")
    public void testLabeledThisToMemberExtension() throws Exception {
        runTest("../../idea/tests/testData/navigation/gotoDeclaration/labeledThisToMemberExtension.test");
    }

    @TestMetadata("thisExtensionFunction.test")
    public void testThisExtensionFunction() throws Exception {
        runTest("../../idea/tests/testData/navigation/gotoDeclaration/thisExtensionFunction.test");
    }

    @TestMetadata("thisExtensionLambda.test")
    public void testThisExtensionLambda() throws Exception {
        runTest("../../idea/tests/testData/navigation/gotoDeclaration/thisExtensionLambda.test");
    }

    @TestMetadata("thisInExtensionPropertyAccessor.test")
    public void testThisInExtensionPropertyAccessor() throws Exception {
        runTest("../../idea/tests/testData/navigation/gotoDeclaration/thisInExtensionPropertyAccessor.test");
    }
}
