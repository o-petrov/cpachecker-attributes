// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.ImmutableValueGraph;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import org.sosy_lab.common.io.IO;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;

abstract class AbstractDeltaDebuggingStrategy<Element> implements CFAMutationStrategy {

  protected static final Map<ImmutableSet<?>, DDResultOfARun> resultCache = new HashMap<>();

  private final LogManager logger;
  private final List<DeltaDebuggingStatistics> stats = new ArrayList<>();
  protected final CFAElementManipulator<Element> manipulator;
  private final DDDirection direction;
  private final PartsToRemove mode;

  protected AbstractDeltaDebuggingStrategy(
      LogManager pLogger,
      CFAElementManipulator<Element> pManipulator,
      DDDirection pDirection,
      PartsToRemove pMode) {
    logger = Preconditions.checkNotNull(pLogger);
    manipulator = Preconditions.checkNotNull(pManipulator);
    direction = Preconditions.checkNotNull(pDirection);
    mode = Preconditions.checkNotNull(pMode);
    useNewStats();
  }

  protected LogManager getLogger() {
    return logger;
  }

  protected void logInfo(Object... args) {
    logger.log(Level.INFO, args);
  }

  protected void logFine(Object... args) {
    logger.log(Level.FINE, args);
  }

  protected void setupFromCfa(FunctionCFAsWithMetadata pCfa) {
    manipulator.setupFromCfa(pCfa);
  }

  protected CFAElementManipulator<Element> getManipulator() {
    return manipulator;
  }

  protected String getElementTitle() {
    return manipulator.getElementTitle();
  }

  protected DDDirection getDirection() {
    return direction;
  }

  protected PartsToRemove getMode() {
    return mode;
  }

  protected void useNewStats() {
    stats.add(
        new DeltaDebuggingStatistics(
            this.getClass().getSimpleName(), manipulator.getElementTitle()));
  }

  protected DeltaDebuggingStatistics getCurrStats() {
    return stats.get(stats.size() - 1);
  }

  @Override
  public void collectStatistics(Collection<Statistics> pStatsCollection) {
    pStatsCollection.addAll(stats);
  }

  private static <N, V> void generateDot(Writer pWriter, ImmutableValueGraph<N, V> pGraph)
      throws IOException {
    pWriter.append("digraph G {\n" + "rankdir=LR;\n");
    List<N> nodes = ImmutableList.copyOf(pGraph.nodes());
    for (int i = 0; i < nodes.size(); i++) {
      pWriter
          .append("node")
          .append(String.valueOf(i))
          .append(" [label=\"")
          .append(nodes.get(i).toString())
          .append("\"]\n");
    }
    for (int i = 0; i < nodes.size(); i++) {
      for (N succ : pGraph.successors(nodes.get(i))) {
        pWriter
            .append("node")
            .append(String.valueOf(i))
            .append(" -> node")
            .append(String.valueOf(nodes.indexOf(succ)))
            .append(" [label=\"")
            .append(pGraph.edgeValue(nodes.get(i), succ).orElseThrow().toString())
            .append("\"]\n");
      }
    }
    pWriter.append("}\n");
  }

  public void exportGraph(Path pDirectory) {
    String filename = getElementTitle().replace(' ', '-') + ".dot";

    try (Writer w = IO.openOutputFile(pDirectory.resolve(filename), Charset.defaultCharset())) {
      generateDot(w, manipulator.getGraph());
    } catch (IOException e) {
      logger.logUserException(Level.WARNING, e, "Could not write HDD graph");
    }
  }

  protected String shortListToLog(ImmutableList<?> pList) {
    String shortList =
        pList.size() > 4
            ? Joiner.on(", ").join(pList.get(0), pList.get(1), pList.get(2), "...")
            : Joiner.on(", ").join(pList);
    if (shortList.isEmpty()) {
      shortList = "no elements";
    }
    return '(' + shortList + ')';
  }

  protected ImmutableSet<Element> whatRemainsWithout(Collection<Element> pElements) {
    return manipulator.whatRemainsIfRemove(pElements);
  }

  // #cacheResult is always called after #getCachedResultWithout,
  // so no need to get remaining elements again
  private ImmutableSet<Element> lastAccessedKey;

  protected Optional<DDResultOfARun> getCachedResultWithout(Collection<Element> pElements) {
    lastAccessedKey = whatRemainsWithout(pElements);
    logInfo("Cache lookup for", shortListToLog(lastAccessedKey.asList()));
    return Optional.ofNullable(resultCache.get(lastAccessedKey));
  }

  protected void cacheResult(DDResultOfARun pResult) {
    resultCache.put(lastAccessedKey, pResult);
  }
}
