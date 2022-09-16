// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.logging.Level;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;

class HierarchicalDeltaDebugging<Element> implements CFAMutationStrategy {
  private CFAElementManipulator<Element> manipulator = null;
  private AbstractDeltaDebuggingAlgorithm<Element> delegate = null;
  private ImmutableSet<Element> currentLevel = null;
  private ImmutableList<Element> currentMutation = null;
  private LogManager logger;

  public HierarchicalDeltaDebugging(
      Configuration pConfig,
      LogManager pLogger,
      CFAElementManipulator<Element> pManipulator,
      PartsToRemove pMode)
      throws InvalidConfigurationException {
    logger = pLogger;
    manipulator = pManipulator;
    delegate = new DDMinAlgorithm<>(pConfig, pLogger, manipulator, pMode);
  }

  @Override
  public boolean canMutate(FunctionCFAsWithMetadata pCfa) {
    if (currentLevel == null) {
      // setup
      manipulator.setupFromCfa(pCfa, stats);
      currentLevel = manipulator.getNextLevelElements();
      logger.log(
          Level.INFO,
          "HDD starts with",
          currentLevel.size(),
          "(out of total",
          manipulator.getAllElements().size() + ')',
          "root/source",
          manipulator.getElementTitle() + ':',
          currentLevel);

    } else if (delegate.canMutate(pCfa)) {
      logger.log(Level.FINE, "HDD continues on same level");
      return true;

    } else {
      // current level is minimized, go one level deeper
      currentLevel = manipulator.getNextLevelElements();
      logger.log(
          Level.INFO,
          "HDD steps to successors' level with",
          currentLevel.size(),
          "(out of total",
          manipulator.getAllElements().size() + ')',
          manipulator.getElementTitle() + ':',
          currentLevel);
    }

    if (currentLevel.isEmpty()) {
      // no nodes left to remove
      return false;
    }

    delegate.workOn(currentLevel);
    boolean result = delegate.canMutate(pCfa);
    assert result : "Can not work on " + currentLevel;
    return true;
  }

  @Override
  public void collectStatistics(Collection<Statistics> pStatsCollection) {
    // TODO Auto-generated method stub

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
