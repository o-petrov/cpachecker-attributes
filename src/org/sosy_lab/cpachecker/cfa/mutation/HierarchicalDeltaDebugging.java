// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import org.sosy_lab.common.log.LogManager;

class HierarchicalDeltaDebugging<Element> extends FlatDeltaDebugging<Element> {
  private ImmutableSet<Element> currentLevel = null;

  public HierarchicalDeltaDebugging(
      LogManager pLogger,
      CFAElementManipulator<Element> pManipulator,
      DDDirection pDirection,
      PartsToRemove pMode) {
    super(pLogger, pManipulator, pDirection, pMode);
  }

  @Override
  public boolean canMutate(FunctionCFAsWithMetadata pCfa) {
    if (currentLevel == null) {
      // setup
      setupFromCfa(pCfa);
      currentLevel = manipulator.getNextLevelElements();
      logInfo(
          "HDD starts with",
          currentLevel.size(),
          "(out of total",
          manipulator.getAllElements().size() + ')',
          "root/source",
          manipulator.getElementTitle() + ':',
          currentLevel);

    } else if (super.canMutate(pCfa)) {
      logFine("HDD continues on same level");
      return true;

    } else {
      // current level is minimized, go one level deeper
      currentLevel = manipulator.getNextLevelElements();
      logInfo(
          "HDD steps to successors' level with",
          currentLevel.size(),
          "(out of total",
          manipulator.getAllElements().size() + ')',
          manipulator.getElementTitle() + ':',
          currentLevel);
    }

    if (currentLevel.isEmpty()) {
      // no nodes left to remove
      super.finalize(pCfa);
      return false;
    }

    workOn(currentLevel);
    boolean result = super.canMutate(pCfa);
    assert result : "Can not work on " + currentLevel;
    return true;
  }

  @Override
  protected void mutate(FunctionCFAsWithMetadata pCfa, Collection<Element> pChosen) {
    manipulator.prune(pCfa, pChosen);
  }

  @Override
  protected void finalize(FunctionCFAsWithMetadata pCfa) {
    stage = DeltaDebuggingStage.FINISHED;
  }
}
