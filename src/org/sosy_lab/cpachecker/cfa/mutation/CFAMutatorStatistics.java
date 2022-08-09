// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.base.Preconditions;
import java.io.PrintStream;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.FunctionExitNode;
import org.sosy_lab.cpachecker.core.CPAcheckerResult.Result;
import org.sosy_lab.cpachecker.core.defaults.MultiStatistics;
import org.sosy_lab.cpachecker.core.reachedset.UnmodifiableReachedSet;
import org.sosy_lab.cpachecker.util.statistics.StatCounter;
import org.sosy_lab.cpachecker.util.statistics.StatInt;
import org.sosy_lab.cpachecker.util.statistics.StatKind;
import org.sosy_lab.cpachecker.util.statistics.StatTimerWithMoreOutput;

class CFAMutatorStatistics extends MultiStatistics {
  private static class FirstLastStatInt extends StatInt {
    private int first;
    private int last;

    public FirstLastStatInt(StatKind pMainStatisticKind, String pTitle) {
      super(pMainStatisticKind, pTitle);
    }

    @Override
    public void setNextValue(int pNewValue) {
      if (getUpdateCount() == 0) {
        first = pNewValue;
      }
      last = pNewValue;
      super.setNextValue(pNewValue);
    }

    public int getFirstValue() {
      Preconditions.checkState(getUpdateCount() > 0);
      return first;
    }

    public int getLastValue() {
      Preconditions.checkState(getUpdateCount() > 0);
      return last;
    }
  }

  private StatCounter totalRounds = new StatCounter("mutation rounds");
  private StatCounter failRounds = new StatCounter("successful mutations (sought-for error)");
  private StatCounter passRounds = new StatCounter("unsuccessfull mutations (no error)");
  private StatCounter unresRounds = new StatCounter("unsuccessfull mutations (other problems)");

  private FirstLastStatInt globalDeclsCount =
      new FirstLastStatInt(StatKind.AVG, "count of global declarations");
  private FirstLastStatInt localNodesCount =
      new FirstLastStatInt(StatKind.AVG, "count of nodes in function CFAs");
  private FirstLastStatInt localEdgesCount =
      new FirstLastStatInt(StatKind.AVG, "count of edges in function CFAs");
  private FirstLastStatInt localFunctionsCount =
      new FirstLastStatInt(StatKind.AVG, "count of functions in function CFAs");
  private FirstLastStatInt localCyclomatic =
      new FirstLastStatInt(StatKind.AVG, "cyclomatic complexity of function CFAs");

  private FirstLastStatInt fullNodesCount =
      new FirstLastStatInt(StatKind.AVG, "count of nodes in full CFA");
  private FirstLastStatInt fullEdgesCount =
      new FirstLastStatInt(StatKind.AVG, "count of edges in full CFA");
  private FirstLastStatInt fullFunctionsCount =
      new FirstLastStatInt(StatKind.AVG, "count of functions in full CFA");
  private FirstLastStatInt fullCyclomatic =
      new FirstLastStatInt(StatKind.AVG, "cyclomatic complexity of full CFA");

  private StatTimerWithMoreOutput preparationTimer =
      new StatTimerWithMoreOutput("time for preparations");
  private StatTimerWithMoreOutput mutationTimer = new StatTimerWithMoreOutput("time for mutations");
  private StatTimerWithMoreOutput aftermathTimer =
      new StatTimerWithMoreOutput("time for rollbacks");
  private StatTimerWithMoreOutput totalMutatorTimer =
      new StatTimerWithMoreOutput("total time for CFA mutator");
  private StatTimerWithMoreOutput processCfaTimer =
      new StatTimerWithMoreOutput("time for CFA creation from function CFAs");
  private StatTimerWithMoreOutput resetCfaTimer =
      new StatTimerWithMoreOutput("time for reverting CFA to function CFAs");
  private StatTimerWithMoreOutput exportTimer = new StatTimerWithMoreOutput("time for CFA export");

  CFAMutatorStatistics(LogManager pLogger) {
    super(pLogger);
  }

  @Override
  public @Nullable String getName() {
    return "CFA mutator";
  }

  @Override
  public void printStatistics(PrintStream pOut, Result pResult, UnmodifiableReachedSet pReached) {
    put(pOut, 0, totalRounds);
    put(pOut, 1, failRounds);
    put(pOut, 1, passRounds);
    put(pOut, 1, unresRounds);

    put(pOut, 0, totalMutatorTimer);
    put(pOut, 1, preparationTimer);
    put(pOut, 1, mutationTimer);
    put(pOut, 1, aftermathTimer);
    pOut.println("Additionally,");
    put(pOut, 2, processCfaTimer);
    put(pOut, 2, resetCfaTimer);
    put(pOut, 0, exportTimer);
    pOut.println();

    pOut.println("Statistics for function CFAs");
    pOut.println("----------------------------");
    put(pOut, 1, localNodesCount);
    put(pOut, 1, localEdgesCount);
    put(pOut, 1, globalDeclsCount);
    put(pOut, 1, localFunctionsCount);
    put(pOut, 1, localCyclomatic);
    pOut.println();

    pOut.println("Statistics for full CFA");
    pOut.println("-----------------------");
    put(pOut, 1, fullNodesCount);
    put(pOut, 1, fullEdgesCount);
    put(pOut, 1, fullFunctionsCount);
    put(pOut, 1, fullCyclomatic);

    super.printStatistics(pOut, pResult, pReached);
  }

  public void put(PrintStream pTarget, int pIndent, FirstLastStatInt pValue) {
    super.put(pTarget, pIndent, pValue);
    super.put(
        pTarget, pIndent + 1, pValue.getTitle() + " before mutations", pValue.getFirstValue());
    super.put(pTarget, pIndent + 1, pValue.getTitle() + " after mutations", pValue.getLastValue());
  }

  public void setCfaStats(FunctionCFAsWithMetadata pLocalCfa) {
    localNodesCount.setNextValue(pLocalCfa.getCFANodes().size());
    globalDeclsCount.setNextValue(pLocalCfa.getGlobalDeclarations().size());
    // cc = decision points - exit points + 2
    int cc = 2 * pLocalCfa.getFunctions().size();
    int edges = 0;
    for (CFANode n : pLocalCfa.getCFANodes().values()) {
      edges += n.getNumLeavingEdges();
      // += 1 for decision point (2 leaving edges)
      // -= 1 for terminal point (0 leaving edges)
      cc += n.getNumLeavingEdges() - 1;
    }
    localEdgesCount.setNextValue(edges);
    localFunctionsCount.setNextValue(pLocalCfa.getFunctions().size());
    localCyclomatic.setNextValue(cc);
    // cfa will be constucted fully between these calls
    processCfaTimer.start();
  }

  public void setCfaStats(CFA pCfa) {
    // cfa was constucted fully between these calls
    processCfaTimer.stop();

    fullNodesCount.setNextValue(pCfa.getAllNodes().size());
    int cc = 0;
    int edges = 0;
    for (CFANode n : pCfa.getAllNodes()) {
      edges += n.getNumLeavingEdges();
      // nodes can be unreachable via local edges, dont count these
      if (n instanceof FunctionExitNode) {
        if (((FunctionExitNode) n).getEntryNode().getNumEnteringEdges() == 0) {
          // +2 for function not called by anyone
          cc += 2;
        }
        // function exits can have any amount of leaving edges
        if (n.getNumEnteringEdges() > 0 && n.getNumLeavingEdges() == 0) {
          // count only reachable exits
          // -1 for exit not leading anywhere
          cc -= 1;
        }
      } else if (n.getNumEnteringEdges() > 0) {
        // dont count nodes that are not reachable via local edges
        // += 1 for decision point (2 leaving edges)
        // -= 1 for terminal point (0 leaving edges)
        cc += n.getNumLeavingEdges() - 1;
      }
    }
    fullEdgesCount.setNextValue(edges);
    fullFunctionsCount.setNextValue(pCfa.getAllFunctionHeads().size());
    fullCyclomatic.setNextValue(cc);
  }

  public void startExport() {
    exportTimer.start();
  }

  public void stopExport() {
    exportTimer.stop();
  }

  public void startPreparations() {
    totalMutatorTimer.start();
    preparationTimer.start();
  }

  public void startCfaReset() {
    resetCfaTimer.start();
  }

  public void stopCfaReset() {
    resetCfaTimer.stop();
  }

  public void stopPreparations() {
    preparationTimer.stop();
    totalMutatorTimer.stop();
  }

  public void startMutation() {
    totalMutatorTimer.start();
    mutationTimer.start();
    totalRounds.inc();
  }

  public void stopMutation() {
    mutationTimer.stop();
    totalMutatorTimer.stop();
  }

  public void startAftermath(DDResultOfARun pResult) {
    totalMutatorTimer.start();
    aftermathTimer.start();

    switch (pResult) {
      case FAIL:
        failRounds.inc();
        break;
      case PASS:
        passRounds.inc();
        break;
      case UNRESOLVED:
        unresRounds.inc();
        break;
      default:
        throw new AssertionError();
    }
  }

  public void stopAftermath() {
    aftermathTimer.stop();
    totalMutatorTimer.stop();
  }

  public long getRound() {
    return totalRounds.getValue();
  }
}