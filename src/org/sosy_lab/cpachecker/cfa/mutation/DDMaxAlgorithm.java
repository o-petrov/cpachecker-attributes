// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import org.sosy_lab.common.log.LogManager;

/** This delta debugging algorithm (ddmax) maximizes a passing test. */
class DDMaxAlgorithm<Element> extends FlatDeltaDebugging<Element> {

  public DDMaxAlgorithm(
      LogManager pLogger, CFAElementManipulator<Element> pManipulator, PartsToRemove pMode) {
    super(pLogger, pManipulator, pMode);
  }

  @Override
  protected void logFinish() {
    logInfo(
        "All",
        getElementTitle(),
        "are resolved,",
        getSafeElements().size(),
        getElementTitle(),
        shortListToLog(getSafeElements()),
        "remain in a maximal passing test");
  }

  @Override
  protected void testFailed(FunctionCFAsWithMetadata pCfa, DeltaDebuggingStage pStage) {
    logInfo(
        "The remaining",
        pStage.nameThis(),
        "is a failing test. Nothing is resolved. The removed complement is restored.");
    manipulator.rollback(pCfa);
  }

  @Override
  protected void testPassed(FunctionCFAsWithMetadata pCfa, DeltaDebuggingStage pStage) {
    markRemainingElementsAsSafe();

    logInfo(
        "The removed",
        pStage.nameThis(),
        "contains a fail-inducing difference. The remaining",
        pStage.nameOther(),
        "is safe by itself. Mutation is rollbacked.");

    switch (pStage) {
      case REMOVE_COMPLEMENT:
        removeCurrentDeltaFromDeltaList();
        break;

      case REMOVE_WHOLE:
      case REMOVE_HALF1:
      case REMOVE_HALF2:
      case REMOVE_DELTA:
        resetDeltaListWithHalvesOfCurrentDelta();
        break;

      default:
        throw new AssertionError();
    }

    manipulator.rollback(pCfa);
  }
}
