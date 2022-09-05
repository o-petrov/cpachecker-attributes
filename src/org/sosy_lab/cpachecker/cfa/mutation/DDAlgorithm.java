// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import java.util.logging.Level;
import org.sosy_lab.common.log.LogManager;

/** This delta debugging algorithm (dd) finds a minimal fail-inducing difference. */
class DDAlgorithm<Element> extends AbstractDeltaDebuggingAlgorithm<Element> {

  public DDAlgorithm(
      LogManager pLogger, CFAElementManipulator<Element> pElementManipulator, PartsToRemove pMode) {
    super(pLogger, pElementManipulator, pMode);
  }

  @Override
  protected void logFinish() {
    logger.log(
        Level.INFO,
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

    switch (pStage) {
      case REMOVE_COMPLEMENT:
        // delta has the cause, complement is safe
        logger.log(
            Level.INFO,
            "The remaining delta is a fail-inducing test. The removed complement is not restored.");
        // remove complement from list, i.e. make list of one delta, and then split it.
        resetDeltaListWithHalvesOfCurrentDelta();

        break;
      case REMOVE_DELTA:
        // delta is safe, complement has the cause
        logger.log(
            Level.INFO,
            "The remaining complement is a fail-inducing test. The removed delta is not restored.");
        removeCurrentDeltaFromDeltaList();

        break;
      default:
        throw new AssertionError();
    }
  }

  @Override
  protected void testPassed(FunctionCFAsWithMetadata pCfa, DeltaDebuggingStage pStage) {
    markRemainingElementsAsSafe();

    switch (pStage) {
      case REMOVE_COMPLEMENT:
        logger.log(
            Level.INFO,
            "The removed complement contains a fail-inducing difference. "
                + "The remaining delta is safe by itself. Mutation is rollbacked.");
        removeCurrentDeltaFromDeltaList();
        break;

      case REMOVE_DELTA:
        logger.log(
            Level.INFO,
            "The removed delta contains a fail-inducing difference. "
                + "The remaining complement is safe by itself. Mutation is rollbacked.");
        resetDeltaListWithHalvesOfCurrentDelta();
        break;

      default:
        throw new AssertionError();
    }

    rollback(pCfa);
  }
}
