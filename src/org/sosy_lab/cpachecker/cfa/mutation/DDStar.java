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
    ImmutableList<Element> newCause = super.getCauseElements();
    if (newCause.isEmpty()) {
      logInfo("Found no cause");
    } else {
      causeList.add(newCause);
      logInfo("Found a cause:", shortListToLog(newCause));
    }

    ImmutableList<Element> safeElements = super.getSafeElements();
    ImmutableList<Element> removedElements = super.getRemovedElements();

    switch (getStarDirection()) {
      case MAXIMIZATION:
        safeList.add(safeElements);
        logInfo("Marked safe:", shortListToLog(safeElements));
        if (removedElements.isEmpty()) {
          logInfo("No removed elements to repeat dd on");
        } else {
          logInfo("Repeating dd on removed:", shortListToLog(removedElements));
        }
        return removedElements;

      case MINIMIZATION:
        removedList.add(removedElements);
        logInfo("Were removed:", shortListToLog(removedElements));
        if (safeElements.isEmpty()) {
          logInfo("No safe elements to repeat dd on");
        } else {
          logInfo("Repeating dd on safe:", shortListToLog(safeElements));
        }
        return safeElements;

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
      assert stage == DeltaDebuggingStage.FINISHED;
      return;
    }

    workOnElements(newUnresolved);
  }

  @Override
  protected void testWholeUnresolved() {
    switch (getStarDirection()) {
      case MAXIMIZATION:
        // removing whole is wrong, we've just returned it inside
        resetDeltaListWithHalvesOfCurrentDelta();
        break;

      case MINIMIZATION:
        // just return to usual
        stage = DeltaDebuggingStage.REMOVE_WHOLE;
        break;

      default:
        throw new AssertionError();
    }
  }

  protected void clear() {
    causeList.clear();
    safeList.clear();
    removedList.clear();
  }

  protected void workOnElements(ImmutableList<Element> pElements) {
    // stop old timer
    getCurrStats().stopTimers();
    // generates new stats and new timer
    workOn(pElements);
    // flip stage from ready
    if (getStarDirection() == DDDirection.MAXIMIZATION) {
      // for max, it is needed to check whole: in case max-property holds, nothing should be removed
      stage = DeltaDebuggingStage.CHECK_WHOLE;
    } else {
      stage = DeltaDebuggingStage.REMOVE_WHOLE;
    }
    // start new timer so canMutate that called finalize can stop timer without exception
    getCurrStats().startPremath();
  }

  @Override
  public ImmutableList<Element> getCauseElements() {
    return FluentIterable.concat(causeList).toList();
  }

  protected ImmutableList<ImmutableList<Element>> getListOfCauses() {
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
