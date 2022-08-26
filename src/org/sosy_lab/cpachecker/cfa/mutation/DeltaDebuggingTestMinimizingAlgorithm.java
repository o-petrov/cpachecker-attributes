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

public class DeltaDebuggingTestMinimizingAlgorithm<Element>
    extends AbstractDeltaDebuggingAlgorithm<Element> {

  public DeltaDebuggingTestMinimizingAlgorithm(
      LogManager pLogger,
      CFAElementManipulator<Element> pElementManipulator,
      List<Element> pElements) {
    super(pLogger, pElementManipulator, pElements);
  }

  public DeltaDebuggingTestMinimizingAlgorithm(
      LogManager pLogger,
      CFAElementManipulator<Element> pElementManipulator,
      List<Element> pElements,
      PartsToRemove pMode) {
    super(pLogger, pElementManipulator, pElements, pMode);
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

    logger.log(
        Level.INFO,
        "All",
        elementManipulator.getElementTitle(),
        "are resolved,",
        causeSize,
        "fail-inducing",
        elementManipulator.getElementTitle(),
        "remain",
        causeList);
  }

  @Override
  protected void failsWithoutDelta() {
    // delta is safe, complement has the cause
    logger.log(Level.INFO, "The removed delta is safe");
    // remove delta from list
    updateUnresElements();
    deltaIter.remove();
  }

  @Override
  protected void failsWithoutComplement() {
    // delta has the cause, complement is safe
    logger.log(Level.INFO, "The removed complement is safe");
    // remove complement from list, i.e. make list of one delta, and then split it.
    updateUnresElements();
    resetDeltaListWithHalvesOfCurrentDelta();
  }

  @Override
  protected void passesWithoutDelta() {
    logger.log(
        Level.INFO,
        "The removed delta contains part of a minimal failing test. No",
        elementManipulator.getElementTitle(),
        "are resolved");
  }

  @Override
  protected void passesWithoutComplement() {
    logger.log(
        Level.INFO,
        "The removed complement contains part of a minimal failing test. No",
        elementManipulator.getElementTitle(),
        "are resolved");
  }

  @Override
  protected void unresWithoutDelta() {
    logger.log(
        Level.INFO,
        "Something in the removed delta is needed for a test run to be resolved. No",
        elementManipulator.getElementTitle(),
        "are resolved");
  }

  @Override
  protected void unresWithoutComplement() {
    logger.log(
        Level.INFO,
        "Something in the removed complement is needed for a test run to be resolved. No",
        elementManipulator.getElementTitle(),
        "are resolved");
  }
}
