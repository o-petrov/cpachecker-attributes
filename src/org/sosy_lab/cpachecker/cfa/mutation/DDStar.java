// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2023 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import org.sosy_lab.common.log.LogManager;

/**
 * DD* repeats DD algorithm...
 *
 * <ul>
 *   <li>...on safe part so that only causes remain in a minimum result;
 *   <li>...on removed part with cause removed, so that all safe parts remain in a maximum.
 * </ul>
 *
 * It can not isolate cause.
 */
public class DDStar<Element> extends FlatDeltaDebugging<Element> {
  private List<ImmutableList<Element>> causeList = new ArrayList<>();
  private List<ImmutableList<Element>> safeList = new ArrayList<>();
  private List<ImmutableList<Element>> removedList = new ArrayList<>();

  private DDDirection direction;

  public DDStar(
      LogManager pLogger,
      CFAElementManipulator<Element, ?> pManipulator,
      DDDirection pDirection,
      PartsToRemove pMode) {
    super(pLogger, pManipulator, DDDirection.ISOLATION, pMode);
    assert pDirection != DDDirection.ISOLATION;
    direction = pDirection;
  }

  protected DDDirection getStarDirection() {
    return direction;
  }

  private ImmutableList<Element> storeOldAndGetNewList() {
    causeList.add(super.getCauseElements());
    logInfo("Found a cause:", shortListToLog(super.getCauseElements()));

    switch (getStarDirection()) {
      case MAXIMIZATION:
        safeList.add(super.getSafeElements());
        logInfo("Marked safe:", shortListToLog(super.getSafeElements()));
        logInfo("Repeating dd on removed:", shortListToLog(super.getRemovedElements()));
        return super.getRemovedElements();

      case MINIMIZATION:
        removedList.add(super.getRemovedElements());
        logInfo("Were removed:", shortListToLog(super.getRemovedElements()));
        logInfo("Repeating dd on safe:", shortListToLog(super.getSafeElements()));
        return super.getSafeElements();

      default:
        throw new AssertionError();
    }

  }

  @Override
  protected void finalize(FunctionCFAsWithMetadata pCfa) {
    super.finalize(pCfa);

    ImmutableList<Element> newUnresolved = storeOldAndGetNewList();
    if (getStarDirection() == DDDirection.MAXIMIZATION) {
      logInfo("Restoring removed, removing found cause");
      manipulator.restore(pCfa, super.getRemovedElements());
      mutate(pCfa, super.getCauseElements());
    }
    if (newUnresolved.isEmpty()) {
      return;
    }

    // stop old timer
    getCurrStats().stopTimers();
    // generates new stats and new timer
    workOn(newUnresolved);
    // flip stage from ready to remove_whole
    stage = DeltaDebuggingStage.REMOVE_WHOLE;
    // start new timer so canMutate that called finalize can stop timer without exception
    getCurrStats().startPremath();
  }

  @Override
  public ImmutableList<Element> getCauseElements() {
    return FluentIterable.concat(causeList).toList();
  }

  protected ImmutableList<ImmutableList<Element>> getCauseList() {
    return ImmutableList.copyOf(causeList);
  }

  @Override
  public ImmutableList<Element> getSafeElements() {
    return FluentIterable.concat(safeList).toList();
  }

  protected ImmutableList<ImmutableList<Element>> getSafeList() {
    return ImmutableList.copyOf(safeList);
  }

  @Override
  public ImmutableList<Element> getRemovedElements() {
    return FluentIterable.concat(removedList).toList();
  }

  protected ImmutableList<ImmutableList<Element>> getRemovedList() {
    return ImmutableList.copyOf(removedList);
  }
}
