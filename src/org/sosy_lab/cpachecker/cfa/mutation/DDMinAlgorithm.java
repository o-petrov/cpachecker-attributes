// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import java.util.List;
import java.util.logging.Level;
import org.sosy_lab.common.log.LogManager;

/** This delta debugging algorithm (ddmin) minimizes a failing test. */
public class DDMinAlgorithm<Element> extends AbstractDeltaDebuggingAlgorithm<Element> {

  public DDMinAlgorithm(
      LogManager pLogger,
      CFAElementManipulator<Element> pElementManipulator,
      List<Element> pElements) {
    super(pLogger, pElementManipulator, pElements);
  }

  public DDMinAlgorithm(
      LogManager pLogger,
      CFAElementManipulator<Element> pElementManipulator,
      List<Element> pElements,
      PartsToRemove pMode) {
    super(pLogger, pElementManipulator, pElements, pMode);
  }

  @Override
  protected void logFinish() {
    logger.log(
        Level.INFO,
        "All",
        getElementTitle(),
        "are resolved,",
        getCauseElements().size(),
        getElementTitle(),
        shortListToLog(getCauseElements()),
        "remain in a minimal failing test.");
  }

  @Override
  protected void testFailed(FunctionCFAsWithMetadata pCfa, DeltaDebuggingStage pStage) {
    markRemovedElementsAsResolved();

    switch (pStage) {
      case REMOVE_COMPLEMENT:
        // delta has the cause, complement is safe
        logger.log(
            Level.INFO,
            "The remaining delta is a failing test. The removed complement is not restored.");
        // remove complement from list, i.e. make list of one delta, and then split it.
        resetDeltaListWithHalvesOfCurrentDelta();

        break;
      case REMOVE_DELTA:
        // delta is safe, complement has the cause
        logger.log(
            Level.INFO,
            "The remaining complement is a failing test. The removed delta is not restored.");
        // remove delta from list
        removeCurrentDeltaFromDeltaList();

        break;
      default:
        throw new AssertionError();
    }
  }

  @Override
  protected void testPassed(FunctionCFAsWithMetadata pCfa, DeltaDebuggingStage pStage) {
    switch (pStage) {
      case REMOVE_COMPLEMENT:
        logger.log(
            Level.INFO,
            "The removed complement contains a part of a minimal failing test. "
                + "Nothing is resolved. Mutation is rollbacked.");
        break;

      case REMOVE_DELTA:
        logger.log(
            Level.INFO,
            "The removed delta contains a part of a minimal failing test. "
                + "Nothing is resolved. Mutation is rollbacked.");
        break;

      default:
        throw new AssertionError();
    }

    rollback(pCfa);
  }
}
