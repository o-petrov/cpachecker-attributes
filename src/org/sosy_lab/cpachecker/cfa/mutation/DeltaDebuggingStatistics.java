// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import java.io.PrintStream;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.cpachecker.core.CPAcheckerResult.Result;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.reachedset.UnmodifiableReachedSet;
import org.sosy_lab.cpachecker.util.statistics.StatCounter;
import org.sosy_lab.cpachecker.util.statistics.StatInt;
import org.sosy_lab.cpachecker.util.statistics.StatKind;
import org.sosy_lab.cpachecker.util.statistics.StatTimer;
import org.sosy_lab.cpachecker.util.statistics.StatTimerWithMoreOutput;

class DeltaDebuggingStatistics implements Statistics {
  private final String strategyName;
  private final String elementTitle;

  private final StatCounter totalRounds = new StatCounter("mutation rounds");
  private final StatCounter failRounds = new StatCounter("successful, same error");
  private final StatCounter passRounds = new StatCounter("unsuccessful, no errors");
  private final StatCounter unresRounds = new StatCounter("unsuccessful, other problems");

  private int longestRowOfRollbacks = 0;
  private int currentRowOfRollbacks = 0;

  private final StatTimer totalTimer = new StatTimerWithMoreOutput("total time for strategy");

  private int totalCount;
  private final StatInt causeCount;
  private final StatInt safeCount;
  private final StatInt unresolvedCount;
  private final StatInt removedCount;

  public DeltaDebuggingStatistics(String pStrategyName, String pElementTitle) {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(pStrategyName));
    Preconditions.checkArgument(!Strings.isNullOrEmpty(pElementTitle));
    strategyName = pStrategyName;
    elementTitle = pElementTitle;
    unresolvedCount = new StatInt(StatKind.SUM, "count of unresolved " + elementTitle);
    causeCount = new StatInt(StatKind.SUM, "count of fail-inducing " + elementTitle);
    safeCount = new StatInt(StatKind.SUM, "count of safe " + elementTitle);
    removedCount = new StatInt(StatKind.SUM, "count of removed " + elementTitle);
  }

  @Override
  public void printStatistics(PrintStream pOut, Result pResult, UnmodifiableReachedSet pReached) {
    put(pOut, 1, totalRounds);
    put(pOut, 2, failRounds);
    put(pOut, 3, "longest row of rollbacks", longestRowOfRollbacks);
    put(pOut, 2, passRounds);
    put(pOut, 2, unresRounds);
    put(pOut, 1, totalTimer);
    put(pOut, 1, "count of found " + elementTitle, totalCount);
    put(pOut, 2, causeCount);
    put(pOut, 2, safeCount);
    put(pOut, 2, removedCount);
    put(pOut, 2, unresolvedCount);
  }

  @Override
  public @Nullable String getName() {
    return strategyName;
  }

  public void elementsFound(int pCount) {
    totalCount = pCount;
    unresolvedCount.setNextValue(pCount);
  }

  public void elementsRemoved(int pCount) {
    unresolvedCount.setNextValue(-pCount);
    removedCount.setNextValue(pCount);
  }

  public void elementsResolvedToCause(int pCount) {
    unresolvedCount.setNextValue(-pCount);
    causeCount.setNextValue(pCount);
  }

  public void elementsResolvedToSafe(int pCount) {
    unresolvedCount.setNextValue(-pCount);
    safeCount.setNextValue(pCount);
  }

  public void startMutation() {
    totalTimer.start();
    totalRounds.inc();
  }

  public void startPremath() {
    totalTimer.start();
  }

  public void startAftermath() {
    totalTimer.start();
  }

  public void stopTimers() {
    totalTimer.stop();
  }

  public void incFail() {
    failRounds.inc();
    currentRowOfRollbacks = 0;
  }

  public void incPass() {
    passRounds.inc();
    if (++currentRowOfRollbacks > longestRowOfRollbacks) {
      longestRowOfRollbacks = currentRowOfRollbacks;
    }
  }

  public void incUnres() {
    unresRounds.inc();
    if (++currentRowOfRollbacks > longestRowOfRollbacks) {
      longestRowOfRollbacks = currentRowOfRollbacks;
    }
  }
}