// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.UnmodifiableIterator;
import java.util.List;

/**
 * Use given strategies one after another in given order. Switch to next strategy only when current
 * strategy can not mutate CFA anymore. Stop when last strategy can not mutate CFA.
 */
public class CompositeCFAMutationStrategy implements CFAMutationStrategy {
  private final ImmutableList<CFAMutationStrategy> strategies;
  private final UnmodifiableIterator<CFAMutationStrategy> strategyIterator;
  private CFAMutationStrategy currentStrategy = null;

  public CompositeCFAMutationStrategy(List<CFAMutationStrategy> pStrategies) {
    strategies = ImmutableList.copyOf(pStrategies);
    strategyIterator = strategies.iterator();
    if (strategyIterator.hasNext()) {
      currentStrategy = strategyIterator.next();
    }
  }

  @Override
  public boolean canMutate(FunctionCFAsWithMetadata pCfa) {
    if (currentStrategy == null) {
      // no more strategies
      return false;
    }

    if (currentStrategy.canMutate(pCfa)) {
      return true;
    }

    while (strategyIterator.hasNext()) {
      currentStrategy = strategyIterator.next();
      if (currentStrategy.canMutate(pCfa)) {
        return true;
      }
    }

    currentStrategy = null;
    return false;
  }

  @Override
  public void mutate(FunctionCFAsWithMetadata pCfa) {
    currentStrategy.mutate(pCfa);
  }

  @Override
  public void setResult(FunctionCFAsWithMetadata pCfa, DDResultOfARun pResult) {
    currentStrategy.setResult(pCfa, pResult);
  }
}
