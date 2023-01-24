// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import org.sosy_lab.common.log.LogManager;

/** This delta debugging algorithm (dd) finds a minimal fail-inducing difference. */
class DDAlgorithm<Element> extends FlatDeltaDebugging<Element> {

  public DDAlgorithm(
      LogManager pLogger, CFAElementManipulator<Element> pManipulator, PartsToRemove pMode) {
    super(pLogger, pManipulator, pMode);
  }

  @Override
  protected void logFinish() {
    logInfo(
        "All",
        getElementTitle(),
        "are resolved, minimal fail-inducing difference of",
        getCauseElements().size(),
        getElementTitle(),
        shortListToLog(getCauseElements()),
        "found,",
        getSafeElements().size(),
        getElementTitle(),
        "also remain",
        shortListToLog(getSafeElements()));
  }

  @Override
  protected void testFailed(FunctionCFAsWithMetadata pCfa, DeltaDebuggingStage pStage) {
    markRemovedElementsAsResolved();
    logInfo(
        "The remaining",
        pStage.nameOther(),
        "is a fail-inducing test. The removed",
        pStage.nameThis(),
        "is not restored.");

    switch (pStage) {
      case REMOVE_COMPLEMENT:
        // delta has the cause, complement is safe
        // remove complement from list, i.e. make list of one delta, and then split it.
        resetDeltaListWithHalvesOfCurrentDelta();
        break;

      case REMOVE_WHOLE:
      case REMOVE_HALF1:
      case REMOVE_HALF2:
      case REMOVE_DELTA:
        // delta is safe, complement has the cause
        removeCurrentDeltaFromDeltaList();

        break;
      default:
        throw new AssertionError();
    }
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
