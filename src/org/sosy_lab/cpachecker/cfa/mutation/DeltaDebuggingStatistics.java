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
import com.google.common.graph.ImmutableValueGraph;
import com.google.common.graph.ValueGraph;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.logging.Level;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.FileOption;
import org.sosy_lab.common.configuration.FileOption.Type;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.io.IO;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.core.CPAcheckerResult.Result;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.reachedset.UnmodifiableReachedSet;
import org.sosy_lab.cpachecker.util.statistics.StatCounter;
import org.sosy_lab.cpachecker.util.statistics.StatInt;
import org.sosy_lab.cpachecker.util.statistics.StatKind;
import org.sosy_lab.cpachecker.util.statistics.StatTimer;
import org.sosy_lab.cpachecker.util.statistics.StatTimerWithMoreOutput;

@Options(prefix = "cfaMutation")
class DeltaDebuggingStatistics implements Statistics {
  @FileOption(Type.OUTPUT_DIRECTORY)
  @Option(
      secure = true,
      name = "graphsDir",
      description =
          "directory for hierarchical/dependency graphs built by CFA mutation DD algorithms")
  private Path graphDirectory = Path.of(".");
  private ImmutableValueGraph<?, ?> graph = null;
  private final int pass;

  private final String strategyName;
  private final String elementTitle;
  private final LogManager logger;

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

  public DeltaDebuggingStatistics(
      int pPass,
      Configuration pConfig,
      LogManager pLogger,
      String pStrategyName,
      String pElementTitle)
      throws InvalidConfigurationException {
    pass = pPass;
    Preconditions.checkArgument(!Strings.isNullOrEmpty(pStrategyName));
    Preconditions.checkArgument(!Strings.isNullOrEmpty(pElementTitle));
    strategyName = pStrategyName;
    elementTitle = pElementTitle;
    logger = Preconditions.checkNotNull(pLogger);
    pConfig.inject(this, DeltaDebuggingStatistics.class);

    unresolvedCount = new StatInt(StatKind.SUM, "count of unresolved " + elementTitle);
    causeCount = new StatInt(StatKind.SUM, "count of fail-inducing " + elementTitle);
    safeCount = new StatInt(StatKind.SUM, "count of safe " + elementTitle);
    removedCount = new StatInt(StatKind.SUM, "count of removed " + elementTitle);
  }

  public DeltaDebuggingStatistics(
      Configuration pConfig, LogManager pLogger, String pStrategyName, String pElementTitle)
      throws InvalidConfigurationException {
    this(0, pConfig, pLogger, pStrategyName, pElementTitle);
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
  public void writeOutputFiles(Result pResult, UnmodifiableReachedSet pReached) {
    if (graph != null) {
      String filename = elementTitle + " " + String.valueOf(pass) + ".dot";
      try (Writer w =
          IO.openOutputFile(graphDirectory.resolve(filename), Charset.defaultCharset())) {
        generateDot(w, graph);
      } catch (IOException e) {
        logger.logUserException(Level.WARNING, e, "Could not write " + elementTitle + " graph");
      }
    }
  }

  private static <N, V> void generateDot(Writer pWriter, ImmutableValueGraph<N, V> pGraph)
      throws IOException {
    pWriter.append("digraph G {\n" + "rankdir=LR;\n");
    for (N node : pGraph.nodes()) {
      pWriter.append(node.toString());
      pWriter.append("\n");
    }
    for (N pred : pGraph.nodes()) {
      for (N succ : pGraph.successors(pred)) {
        pWriter.append(pred.toString());
        pWriter.append(" -> ");
        pWriter.append(succ.toString());
        pWriter.append(" [label=\"");
        pWriter.append(pGraph.edgeValue(pred, succ).orElseThrow().toString());
        pWriter.append("\"]\n");
      }
    }
    pWriter.append("}\n");
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

  public void setGraph(ValueGraph<?, ?> pGraph) {
    Preconditions.checkState(graph == null, "Dependency graph was already set");
    graph = ImmutableValueGraph.copyOf(pGraph);
  }
}