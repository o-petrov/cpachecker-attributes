// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import java.util.logging.Level;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.log.LogManager;

/** This delta debugging algorithm (ddmin) minimizes a failing test. */
public class DDMinAlgorithm<Element> extends AbstractDeltaDebuggingAlgorithm<Element> {

  public DDMinAlgorithm(
      Configuration pConfig,
      LogManager pLogger,
      CFAElementManipulator<Element> pElementManipulator,
      PartsToRemove pMode)
      throws InvalidConfigurationException {
    super(pConfig, pLogger, pElementManipulator, pMode);
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

      case REMOVE_HALF1:
      case REMOVE_HALF2:
      case REMOVE_WHOLE:
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
    logger.log(
        Level.INFO,
        "The removed",
        pStage,
        "contains a part of a minimal failing test. "
            + "Nothing is resolved. Mutation is rollbacked.");

    rollback(pCfa);
  }
}
