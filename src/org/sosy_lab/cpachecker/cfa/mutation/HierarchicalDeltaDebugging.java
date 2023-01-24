// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.collect.ImmutableSet;
import org.sosy_lab.common.log.LogManager;

class HierarchicalDeltaDebugging<Element> extends AbstractDeltaDebuggingStrategy<Element> {
  private final FlatDeltaDebugging<Element> delegate;
  private ImmutableSet<Element> currentLevel = null;

  public HierarchicalDeltaDebugging(LogManager pLogger, FlatDeltaDebugging<Element> pDelegate) {
    super(pLogger, pDelegate.getManipulator(), PartsToRemove.DUMMY);
    delegate = pDelegate;
    delegate.setHierarchical();
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

    } else if (delegate.canMutate(pCfa)) {
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
      return false;
    }

    delegate.workOn(currentLevel);
    boolean result = delegate.canMutate(pCfa);
    assert result : "Can not work on " + currentLevel;
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
