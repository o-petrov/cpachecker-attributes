// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;

/** // cause isolating // test minimizing via removal of deltas */
class DeltaRemovingAfterCauseIsolatingDDStrategy<Element> implements CFAMutationStrategy {
  private AbstractDeltaDebuggingAlgorithm<Element> delegate = null;
  private final LogManager logger;
  private final CFAElementManipulator<Element> elementManipulator;
  private List<Element> unresolvedElements = null;
  private List<Element> causeElements = new ArrayList<>();
  private List<Statistics> stats = new ArrayList<>(2);

  public DeltaRemovingAfterCauseIsolatingDDStrategy(
      LogManager pLogger, CFAElementManipulator<Element> pElementManipulator) {
    logger = pLogger;
    elementManipulator = pElementManipulator;
  }

  @Override
  public void collectStatistics(Collection<Statistics> pStatsCollection) {
    pStatsCollection.addAll(stats);
  }

  @Override
  public boolean canMutate(FunctionCFAsWithMetadata pCfa) {
    if (delegate == null) {
      unresolvedElements = new ArrayList<>(elementManipulator.getAllElements(pCfa).nodes());
      delegate =
          new DeltaDebuggingCauseIsolatingAlgorithm<>(
              logger, elementManipulator, unresolvedElements);
    }
    if (!delegate.canMutate(pCfa)) {
      // found cause
      causeElements.addAll(delegate.getCauseElements());
      unresolvedElements.retainAll(delegate.getSafeElements());
      delegate.collectStatistics(stats);

      if (unresolvedElements.isEmpty()) {
        return false;
      }

      // we should reach here only once
      assert delegate instanceof DeltaDebuggingCauseIsolatingAlgorithm;
      delegate =
          new DeltaDebuggingTestMinimizingAlgorithm<>(
              logger, elementManipulator, unresolvedElements, PartsToRemove.ONLY_DELTAS);
      boolean result = delegate.canMutate(pCfa);
      assert result;
    }
    return true;
  }

  @Override
  public void mutate(FunctionCFAsWithMetadata pCfa) {
    delegate.mutate(pCfa);
  }

  @Override
  public void setResult(FunctionCFAsWithMetadata pCfa, DDResultOfARun pResult) {
    delegate.setResult(pCfa, pResult);
  }
}
