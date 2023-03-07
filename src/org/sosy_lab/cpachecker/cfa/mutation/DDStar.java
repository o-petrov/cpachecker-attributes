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
  private boolean noCause;

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
    noCause = newCause.isEmpty();

    if (!noCause) {
      causeList.add(newCause);
      logInfo("Found a cause:", shortListToLog(newCause));
    }

    ImmutableList<Element> list1 = super.getSafeElements();
    ImmutableList<Element> list2 = super.getRemovedElements();
    String elementSet;

    switch (getStarDirection()) {
      case MAXIMIZATION:
        safeList.add(list1);
        logInfo("Marked safe:", shortListToLog(list1));
        elementSet = "removed:";
        break;

      case MINIMIZATION:
        removedList.add(list2);
        logInfo("Were removed:", shortListToLog(list2));
        elementSet = "safe:";
        list2 = list1;
        break;

      default:
        throw new AssertionError();
    }

    if (noCause) {
      logInfo("Found no cause");
      return ImmutableList.of();

    } else if (list2.isEmpty()) {
      logInfo("No elements to repeat DD on");

    } else {
      logInfo("Repeating DD on", elementSet, shortListToLog(list2));
    }
    return list2;
  }

  @Override
  protected void finalize(FunctionCFAsWithMetadata pCfa) {
    super.finalize(pCfa);
    assert stage == DeltaDebuggingStage.FINISHED;

    ImmutableList<Element> newUnresolved = storeOldAndGetNewList();
    if (getStarDirection() == DDDirection.MAXIMIZATION) {
      logInfo("Restoring removed, removing found cause");
      manipulator.restore(pCfa, super.getRemovedElements());
      mutate(pCfa, super.getCauseElements());
    }

    if (noCause) {
      logInfo(
          "DD* has finished, as it can not find",
          getStarDirection() == DDDirection.MAXIMIZATION
              ? "a safe CFA to maximize"
              : "a fail-inducing CFA to minimize");
      return;

    } else if (newUnresolved.isEmpty()) {
      switch (getStarDirection()) {
        case MAXIMIZATION:
          logInfo("DD* has found maximal safe CFA:", getAllSafeElements());
          return;

        case MINIMIZATION:
          logInfo("DD* has found minimal failing CFA:", getAllCauseElements());
          return;

        default:
          throw new AssertionError();
      }
    }

    workOnElements(newUnresolved);
  }

  @Override
  protected void checkWholeResult(FunctionCFAsWithMetadata pCfa, DDResultOfARun pResult) {
    switch (pResult) {
      case MAXIMIZATION_PROPERTY_HOLDS:
        // test(whole) == PASS, and we are maximizing
        // so whole is safe
        // (if we are minimizing, there is nothing to minimize)
        assert getStarDirection() == DDDirection.MAXIMIZATION;
        markRemainingElementsAsSafe();
        stage = DeltaDebuggingStage.ALL_RESOLVED;
        break;

      case MINIMIZATION_PROPERTY_HOLDS:
        // test(whole) == FAIL, and we are minimizing
        // so just do usual
        assert getStarDirection() == DDDirection.MINIMIZATION;
        stage = DeltaDebuggingStage.REMOVE_WHOLE;

        break;
      case NEITHER_PROPERTY_HOLDS:
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

  public ImmutableList<Element> getAllCauseElements() {
    return FluentIterable.concat(causeList).toList();
  }

  protected ImmutableList<ImmutableList<Element>> getListOfCauses() {
    return ImmutableList.copyOf(causeList);
  }

  public ImmutableList<Element> getAllSafeElements() {
    return FluentIterable.concat(safeList).toList();
  }

  protected ImmutableList<ImmutableList<Element>> getSafeList() {
    return ImmutableList.copyOf(safeList);
  }

  public ImmutableList<Element> getAllRemovedElements() {
    return FluentIterable.concat(removedList).toList();
  }

  protected ImmutableList<ImmutableList<Element>> getRemovedList() {
    return ImmutableList.copyOf(removedList);
  }
}
