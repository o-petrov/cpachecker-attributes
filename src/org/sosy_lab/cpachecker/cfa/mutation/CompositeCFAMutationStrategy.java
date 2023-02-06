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
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;

/**
 * Use given strategies one after another in given order. Switch to next strategy only when current
 * strategy can not mutate CFA anymore. Stop when last strategy can not mutate CFA.
 */
public class CompositeCFAMutationStrategy implements CFAMutationStrategy {
  private final ImmutableList<CFAMutationStrategy> strategies;
  private UnmodifiableIterator<CFAMutationStrategy> strategyIterator = null;
  private CFAMutationStrategy currentStrategy = null;
  private final LogManager logger;

  public CompositeCFAMutationStrategy(LogManager pLogger, List<CFAMutationStrategy> pStrategies) {
    strategies = ImmutableList.copyOf(pStrategies);
    logger = pLogger;
  }

  @Override
  public boolean canMutate(FunctionCFAsWithMetadata pCfa) {
    if (strategyIterator == null) {
      strategyIterator = strategies.iterator();
      if (strategyIterator.hasNext()) {
        currentStrategy = strategyIterator.next();
        logger.log(
            Level.INFO, "Switched to next strategy", currentStrategy.getClass().getSimpleName());
      } else {
        logger.log(Level.INFO, "No strategies to mutate CFA");
      }
    }

    if (currentStrategy == null) {
      // no more strategies
      return false;
    }

    if (currentStrategy.canMutate(pCfa)) {
      return true;
    }

    while (strategyIterator.hasNext()) {
      currentStrategy = strategyIterator.next();
      logger.log(
          Level.INFO, "Switched to next strategy", currentStrategy.getClass().getSimpleName());
      if (currentStrategy.canMutate(pCfa)) {
        return true;
      }
    }

    currentStrategy = null;
    logger.log(Level.INFO, "Strategies to mutate CFA ended");
    return false;
  }

  @Override
  public void mutate(FunctionCFAsWithMetadata pCfa) {
    currentStrategy.mutate(pCfa);
  }

  @Override
  public MutationRollback setResult(FunctionCFAsWithMetadata pCfa, DDResultOfARun pResult) {
    return currentStrategy.setResult(pCfa, pResult);
  }

  @Override
  public void collectStatistics(Collection<Statistics> pStatsCollection) {
    if (strategyIterator == null) {
      // no strategies used
      return;
    }

    for (CFAMutationStrategy strategy : strategies) {
      if (currentStrategy == strategy) {
        // current strategy and all after it have not finished
        return;
      }

      strategy.collectStatistics(pStatsCollection);
    }
  }
}
