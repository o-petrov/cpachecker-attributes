// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.base.Joiner;
import java.util.List;
import java.util.logging.Level;
import org.sosy_lab.common.log.LogManager;

class DDAlgorithm<Element>
    extends DDMinAlgorithm<Element> {

  public DDAlgorithm(
      LogManager pLogger,
      CFAElementManipulator<Element> pElementManipulator,
      List<Element> pElements) {
    super(pLogger, pElementManipulator, pElements);
  }

  @Override
  protected void logFinish() {
    String causeSize =
        getCauseElements().size() > 0 ? String.valueOf(getCauseElements().size()) : "no";
    String causeList =
        getCauseElements().size() > 4
            ? Joiner.on(", ")
                .join(
                    getCauseElements().get(0),
                    getCauseElements().get(1),
                    getCauseElements().get(2),
                    "...")
            : Joiner.on(", ").join(getCauseElements());
    if (!causeList.isEmpty()) {
      causeList = '(' + causeList + ')';
    }

    String safeSize =
        getSafeElements().size() > 0 ? String.valueOf(getSafeElements().size()) : "no";
    String safeList =
        getSafeElements().size() > 4
            ? Joiner.on(", ")
                .join(
                    getSafeElements().get(0),
                    getSafeElements().get(1),
                    getSafeElements().get(2),
                    "...")
            : Joiner.on(", ").join(getSafeElements());
    if (!safeList.isEmpty()) {
      safeList = '(' + safeList + ')';
    }

    logger.log(
        Level.INFO,
        "All",
        elementManipulator.getElementTitle(),
        "are resolved, minimal fail-inducing difference of",
        causeSize,
        elementManipulator.getElementTitle(),
        causeList,
        "found,",
        safeSize,
        elementManipulator.getElementTitle(),
        "also remain",
        safeList);
  }

  @Override
  protected void passesWithoutDelta() {
    logger.log(
        Level.INFO,
        "The removed delta contains a minimal fail-inducing diference. The complement of",
        elementsNotRemovedCurrently(),
        elementManipulator.getElementTitle(),
        "is safe by itself.");
    markRemainingElementsAsSafe();
    resetDeltaListWithHalvesOfCurrentDelta();
  }

  @Override
  protected void passesWithoutComplement() {
    logger.log(
        Level.INFO,
        "The removed complement contains a minimal fail-inducing diference. The delta of",
        elementsNotRemovedCurrently(),
        elementManipulator.getElementTitle(),
        "is safe by itself.");
    markRemainingElementsAsSafe();
    deltaIter.remove();
  }
}
