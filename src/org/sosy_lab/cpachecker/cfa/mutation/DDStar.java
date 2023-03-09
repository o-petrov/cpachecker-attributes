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
import java.util.logging.Level;
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

  private ImmutableList<Element> storeOldAndGetNewList(FunctionCFAsWithMetadata pCfa) {
    ImmutableList<Element> newCause = super.getCauseElements();

    if (!newCause.isEmpty()) {
      causeList.add(newCause);
      logInfo("Found a cause:", shortListToLog(newCause));
    } else {
      logInfo("Found no cause");
    }

    ImmutableList<Element> safeElements = super.getSafeElements();
    ImmutableList<Element> removedElements = super.getRemovedElements();

    switch (getStarDirection()) {
      case MAXIMIZATION:
        safeList.add(safeElements);
        logInfo("Marked safe:", shortListToLog(safeElements));

        if (!removedElements.isEmpty()) {
          logInfo("Restoring removed:", shortListToLog(removedElements));
          manipulator.restore(pCfa, removedElements);
        } else {
          logInfo("No removed", getElementTitle(), "to restore");
        }

        if (!newCause.isEmpty()) {
          logInfo("Removing cause:", shortListToLog(newCause));
          mutate(pCfa, newCause);
          logInfo(
              "Repeating DD on previously removed",
              getElementTitle(),
              shortListToLog(removedElements));

        }

        return removedElements;

      case MINIMIZATION:
        removedList.add(removedElements);
        logInfo("Were removed:", shortListToLog(removedElements));

        if (!newCause.isEmpty()) {
          logInfo(
              "Repeating DD on", getElementTitle(), "marked as safe", shortListToLog(safeElements));
        }

        return safeElements;

      default:
        throw new AssertionError();
    }
  }

  @Override
  protected void finalize(FunctionCFAsWithMetadata pCfa) {
    super.finalize(pCfa);
    assert stage == DeltaDebuggingStage.FINISHED;

    ImmutableList<Element> cause = super.getCauseElements();
    ImmutableList<Element> newUnresolved = storeOldAndGetNewList(pCfa);

    if (cause.isEmpty()) {
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
        if (getStarDirection() != DDDirection.MAXIMIZATION) {
          getLogger()
              .log(Level.WARNING, "Maximization property holds on a whole, but DD* is minimizing");
        }
        markRemainingElementsAsSafe();
        stage = DeltaDebuggingStage.ALL_RESOLVED;
        break;

      case MINIMIZATION_PROPERTY_HOLDS:
        if (getStarDirection() != DDDirection.MINIMIZATION) {
          getLogger()
              .log(Level.WARNING, "Minimization property holds on a whole, but DD* is maximizing");
        }
        // $FALL-THROUGH$
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
