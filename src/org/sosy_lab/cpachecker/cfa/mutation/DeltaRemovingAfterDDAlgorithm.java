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

/**
 * This delta debugging algorithm finds a minimal fail-inducing difference and then minimizes
 * remaining 'safe' part by removing only deltas (usually DD tries to remove complements too).
 */
class DeltaRemovingAfterDDAlgorithm<Element> implements CFAMutationStrategy {
  private DDAlgorithm<Element> delegate1 = null;
  private DDMinAlgorithm<Element> delegate2 = null;
  private final LogManager logger;
  private final CFAElementManipulator<Element> elementManipulator;
  private List<Element> unresolvedElements = null;
  private List<Element> causeElements = new ArrayList<>();
  private List<Statistics> stats = new ArrayList<>(2);

  public DeltaRemovingAfterDDAlgorithm(
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
    if (delegate1 == null) {
      unresolvedElements = new ArrayList<>(elementManipulator.getAllElements(pCfa).nodes());
      delegate1 = new DDAlgorithm<>(logger, elementManipulator, unresolvedElements);
    }

    if (delegate2 == null) {
      if (delegate1.canMutate(pCfa)) {
        return true;
      }

      // else found cause
      causeElements.addAll(delegate1.getCauseElements());
      unresolvedElements.retainAll(delegate1.getSafeElements());
      delegate1.collectStatistics(stats);

      if (unresolvedElements.isEmpty()) {
        return false;
      }

      delegate2 =
          new DDMinAlgorithm<>(
              logger, elementManipulator, unresolvedElements, PartsToRemove.ONLY_DELTAS);
    }

    return delegate2.canMutate(pCfa);
  }

  @Override
  public void mutate(FunctionCFAsWithMetadata pCfa) {
    (delegate2 == null ? delegate1 : delegate2).mutate(pCfa);
  }

  @Override
  public void setResult(FunctionCFAsWithMetadata pCfa, DDResultOfARun pResult) {
    (delegate2 == null ? delegate1 : delegate2).setResult(pCfa, pResult);
  }
}
