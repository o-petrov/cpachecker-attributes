// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;

/**
 * A delta debugging algorithm that repeatedly finds minimal fail-inducing difference on remaining
 * CFA. This way it finds a minimal failing test.
 */
class DDStarAlgorithm<Element> implements CFAMutationStrategy {
  private final DDAlgorithm<Element> delegate;
  private final LogManager logger;
  private final CFAElementManipulator<Element> elementManipulator;
  private List<Element> causeElements = new ArrayList<>();

  public DDStarAlgorithm(
      LogManager pLogger, CFAElementManipulator<Element> pElementManipulator) {
    logger = Preconditions.checkNotNull(pLogger);
    elementManipulator = Preconditions.checkNotNull(pElementManipulator);
    delegate = new DDAlgorithm<>(logger, elementManipulator, PartsToRemove.DELTAS_AND_COMPLEMENTS);
  }

  @Override
  public void collectStatistics(Collection<Statistics> pStatsCollection) {
    delegate.collectStatistics(pStatsCollection);
  }

  @Override
  public boolean canMutate(FunctionCFAsWithMetadata pCfa) {
    if (!delegate.canMutate(pCfa)) {
      // found cause
      causeElements.addAll(delegate.getCauseElements());
      ImmutableList<Element> remaining = delegate.getSafeElements();

      if (remaining.isEmpty()) {
        return false;
      }

      delegate.workOn(remaining);
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
