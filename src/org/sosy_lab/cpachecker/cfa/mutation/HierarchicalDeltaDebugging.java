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
import java.util.logging.Level;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.log.LogManager;

class HierarchicalDeltaDebugging<Element> extends DDMinAlgorithm<Element> {
  private ImmutableSet<Element> currentLevel = null;
  private ImmutableList<Element> currentMutation = null;

  public HierarchicalDeltaDebugging(
      Configuration pConfig,
      LogManager pLogger,
      CFAElementManipulator<Element> pManipulator,
      PartsToRemove pMode)
      throws InvalidConfigurationException {
    super(pConfig, pLogger, pManipulator, pMode);
  }

  @Override
  public boolean canMutate(FunctionCFAsWithMetadata pCfa) {
    if (currentLevel == null) {
      // setup
      elementManipulator.setupFromCfa(pCfa, stats);
      currentLevel = elementManipulator.getNextLevelElements();
      logger.log(
          Level.INFO,
          "HDD starts with",
          currentLevel.size(),
          "(out of total",
          elementManipulator.getAllElements().size() + ')',
          "root/source",
          getElementTitle() + ':',
          currentLevel);

    } else if (super.canMutate(pCfa)) {
      logger.log(Level.FINE, "HDD continues on same level");
      return true;

    } else {
      // current level is minimized, go one level deeper
      currentLevel = elementManipulator.getNextLevelElements();
      logger.log(
          Level.INFO,
          "HDD steps to successors' level with",
          currentLevel.size(),
          "(out of total",
          elementManipulator.getAllElements().size() + ')',
          getElementTitle() + ':',
          currentLevel);
    }

    if (currentLevel.isEmpty()) {
      // no nodes left to remove
      return false;
    }

    workOn(currentLevel);
    boolean result = super.canMutate(pCfa);
    assert result : "Can not work on " + currentLevel;
    return true;
  }

  @Override
  protected void applyMutation(
      FunctionCFAsWithMetadata pCfa, ImmutableList<Element> pChosenElements) {
    currentMutation = elementManipulator.remove(pCfa, pChosenElements);
  }

  @Override
  protected void rollback(FunctionCFAsWithMetadata pCfa) {
    elementManipulator.restore(pCfa, currentMutation);
  }
}
