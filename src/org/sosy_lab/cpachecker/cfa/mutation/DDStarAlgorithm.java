// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.base.Preconditions;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.core.CPAcheckerResult.Result;
import org.sosy_lab.cpachecker.core.defaults.MultiStatistics;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.reachedset.UnmodifiableReachedSet;
import org.sosy_lab.cpachecker.util.statistics.StatCounter;

/**
 * A delta debugging algorithm that repeatedly finds minimal fail-inducing difference on remaining
 * CFA. This way it finds a minimal failing test.
 */
class DDStarAlgorithm<Element> implements CFAMutationStrategy {
  private DDAlgorithm<Element> delegate = null;
  private final LogManager logger;
  private final CFAElementManipulator<Element> elementManipulator;
  private List<Element> unresolvedElements = null;
  private List<Element> causeElements = new ArrayList<>();

  private final MultiStatistics stats;
  private final StatCounter passes = new StatCounter("passes of DD cause isolation");

  public DDStarAlgorithm(
      LogManager pLogger, CFAElementManipulator<Element> pElementManipulator) {
    logger = Preconditions.checkNotNull(pLogger);
    elementManipulator = Preconditions.checkNotNull(pElementManipulator);

    stats =
        new MultiStatistics(logger) {
          @Override
          public void printStatistics(
              PrintStream pOut, Result pResult, UnmodifiableReachedSet pReached) {
            put(pOut, 0, passes);
            super.printStatistics(pOut, pResult, pReached);
          }

          @Override
          public @Nullable String getName() {
            // TODO Auto-generated method stub
            return "Cascade cause isolating DD on" + elementManipulator.getElementTitle();
          }
        };
  }

  @Override
  public void collectStatistics(Collection<Statistics> pStatsCollection) {
    pStatsCollection.add(stats);
  }

  @Override
  public boolean canMutate(FunctionCFAsWithMetadata pCfa) {
    if (unresolvedElements == null) {
      unresolvedElements = new ArrayList<>(elementManipulator.getAllElements(pCfa).nodes());
      passes.inc();
      delegate =
          new DDAlgorithm<>(
              logger, elementManipulator, unresolvedElements);
    }
    if (!delegate.canMutate(pCfa)) {
      // found cause
      causeElements.addAll(delegate.getCauseElements());
      unresolvedElements.retainAll(delegate.getSafeElements());
      delegate.collectStatistics(stats.getSubStatistics());

      if (unresolvedElements.isEmpty()) {
        return false;
      }

      passes.inc();
      delegate =
          new DDAlgorithm<>(
              logger, elementManipulator, unresolvedElements);
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
