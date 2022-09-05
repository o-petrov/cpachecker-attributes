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

/** This delta debugging algorithm (ddmax) maximizes a passing test. */
class DDMaxAlgorithm<Element> extends AbstractDeltaDebuggingAlgorithm<Element> {

  public DDMaxAlgorithm(
      LogManager pLogger,
      CFAElementManipulator<Element> pElementManipulator,
      List<Element> pElements) {
    super(pLogger, pElementManipulator, pElements);
  }

  @Override
  protected void logFinish() {
    logger.log(
        Level.INFO,
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
    switch (pStage) {
      case REMOVE_COMPLEMENT:
        logger.log(
            Level.INFO,
            "The remaining delta is a failing test. Nothing is resolved. The removed complement is restored.");

        break;
      case REMOVE_DELTA:
        logger.log(
            Level.INFO,
            "The remaining complement is a failing test. Nothing is resolved. The removed delta is restored.");

        break;
      default:
        throw new AssertionError();
    }

    rollback(pCfa);
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
