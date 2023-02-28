// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.EndpointPair;
import com.google.common.graph.ImmutableValueGraph;
import com.google.common.graph.MutableValueGraph;
import com.google.common.graph.ValueGraph;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import org.sosy_lab.common.log.LogManager;

abstract class CFAElementManipulator<Element, ElementRelation> {

  protected MutableValueGraph<Element, ElementRelation> graph;
  private ImmutableValueGraph<Element, ElementRelation> mutationBackupGraph;
  private ImmutableValueGraph<Element, ElementRelation> originalBackupGraph;

  private ImmutableSet<Element> currentLevel;
  private List<Element> previousLevels = new ArrayList<>();
  private ImmutableList<Element> currentMutation;

  private final LogManager logger;
  private final String elementTitle;

  protected CFAElementManipulator(LogManager pLogger, String pElementTitle) {
    logger = Preconditions.checkNotNull(pLogger);
    elementTitle = Preconditions.checkNotNull(pElementTitle);
  }

  /**
   * Retrieve elements — atomic CFA parts — this manipulator can remove, and their
   * hierarchy/dependency graph.
   *
   * <p>The graph contains edges from including element to included element and/or from dependency
   * to dependent element. If there can be different types of relations in one graph, {@link
   * ValueGraph} with enum edges can be used.
   *
   * <p>Examples. Consider a function as an element of a program; then the graph described can be a
   * call graph. For basic blocks it can be a graph with edges from block with a declaration to all
   * blocks using it. For structured programming structures (blocks, i.e. if-then-else, while, etc.)
   * it can be a graph with edges from including block to the included ones.
   *
   * <p>If CFA is changed by something else, this method must be called before other methods.
   */
  public void setupFromCfa(FunctionCFAsWithMetadata pCfa) {
    constructElementGraph(pCfa);
    originalBackupGraph = ImmutableValueGraph.copyOf(graph);
  }

  protected abstract void constructElementGraph(FunctionCFAsWithMetadata pCfa);

  protected Set<Element> getPredecessors(Element pNode) {
    return graph.predecessors(pNode);
  }

  /**
   * If hierarchy/dependency graph is a tree, it is obvious what a level means. Return all elements
   * that are on the next level (further from the root), or the root element if this method was not
   * called previously.
   *
   * <p>Formally (and for not-a-tree graph), all source elements (without incoming edges) are in
   * 0-level. All elements that must be removed, if 0-level is removed, form 1-level, and so on.
   *
   * <p>Usually in dependency graph if a node is removed, all its successors must be removed too.
   * But e.g. for function call graph, a function must be removed only if its callers (all its
   * predecessors) are removed.
   */
  public ImmutableSet<Element> getNextLevelElements() {
    Preconditions.checkState(graph != null, getElementTitle() + " graph was not set up");
    if (currentLevel == null) {
      // return roots/sources
      // functions that are not called by any other
      currentLevel = graph.nodes().stream()
          .filter(n -> getPredecessors(n).isEmpty())
          .collect(ImmutableSet.toImmutableSet());

    } else {
      currentLevel =
          FluentIterable.from(currentLevel)
              // filter out nodes removed from graph but remaining in 'currentLevel'
              .filter(cur -> graph.nodes().contains(cur))
              .transformAndConcat(cur -> graph.successors(cur))
              // filter out suc that were in current or previous levels
              // filter out suc that has predecessor not from current or previous level
              .filter(
                  g ->
                      !previousLevels.contains(g) && previousLevels.containsAll(getPredecessors(g)))
              .toSet();
    }
    previousLevels.addAll(currentLevel);
    return currentLevel;
  }

  protected abstract void removeElement(FunctionCFAsWithMetadata pCfa, Element pChosen);

  /** Which elements remain in the CFA if manipulator removes given elements. */
  public ImmutableSet<Element> whatRemainsIfRemove(Collection<Element> pChosen) {
    return FluentIterable.from(graph.nodes()).filter(n -> !pChosen.contains(n)).toSet();
  }

  /** Simply remove given elements from the CFA. */
  public void remove(FunctionCFAsWithMetadata pCfa, Collection<Element> pChosen) {
    Preconditions.checkState(graph != null, getElementTitle() + " graph was not set up");

    mutationBackupGraph = ImmutableValueGraph.copyOf(graph);
    currentMutation = ImmutableList.copyOf(pChosen);
    assert mutationBackupGraph.nodes().containsAll(currentMutation);

    logFine("Removing", currentMutation.size(), getElementTitle(), currentMutation);

    currentMutation.forEach(
        f -> {
          removeElement(pCfa, f);
          graph.removeNode(f);
        });
  }

  private ImmutableList<Element> whatToPruneIfChoose(Collection<Element> pChosen) {
    List<Element> result = new ArrayList<>(pChosen);
    assert graph.nodes().containsAll(result) : "chosen elements must be in graph";

    for (int i = 0; i < result.size(); i++) {
      Element f = result.get(i);
      graph.successors(f).stream()
          .filter(g -> !result.contains(g) && result.containsAll(getPredecessors(g)))
          .forEach(g -> result.add(g));
    }

    return ImmutableList.copyOf(result);
  }

  /** Which elements remain in the CFA if manipulator prunes given elements. */
  public ImmutableSet<Element> whatRemainsIfPrune(Collection<Element> pChosen) {
    List<Element> toRemove = whatToPruneIfChoose(pChosen);
    return FluentIterable.from(graph.nodes()).filter(f -> !toRemove.contains(f)).toSet();
  }

  /**
   * Remove chosen elements with their (direct and transitive) children in hierarchy/dependency
   * graph. (If the graph is not a tree, it is not exactly children; there are different decisions.)
   */
  public void prune(FunctionCFAsWithMetadata pCfa, Collection<Element> pChosen) {
    Preconditions.checkState(graph != null, getElementTitle() + " graph was not set up");
    logFine("Prunning", pChosen.size(), getElementTitle(), pChosen);

    mutationBackupGraph = ImmutableValueGraph.copyOf(graph);
    currentMutation = whatToPruneIfChoose(pChosen);
    assert mutationBackupGraph.nodes().containsAll(currentMutation);

    logFine("Removing", currentMutation.size(), getElementTitle(), currentMutation);

    currentMutation.forEach(
        f -> {
          removeElement(pCfa, f);
          graph.removeNode(f);
        });
  }

  /** Rollback the last mutation this manipulator did. */
  public void rollback(FunctionCFAsWithMetadata pCfa) {
    logFine("Restoring", currentMutation.size(), getElementTitle(), currentMutation);

    for (Element node : mutationBackupGraph.nodes()) {
      graph.addNode(node);
    }

    for (EndpointPair<Element> edge : mutationBackupGraph.edges()) {
      graph.putEdgeValue(edge, mutationBackupGraph.edgeValue(edge).orElseThrow());
    }

    currentMutation.reverse().forEach(f -> restoreElement(pCfa, f));
  }

  protected abstract void restoreElement(FunctionCFAsWithMetadata pCfa, Element pChosen);

  /** Restore given elements and update element graph completely. */
  public void restore(
      FunctionCFAsWithMetadata pCfa, Collection<Element> pChosen) {

    List<Element> result = new ArrayList<>(pChosen);

    result.forEach(
        cur -> {
          restoreElement(pCfa, cur);
          graph.addNode(cur);
          restoreEdgesWithOtherPresentNodes(cur);
        });

    for (int i = 0; i < result.size(); i++) {
      Element cur = result.get(i);
      originalBackupGraph.successors(cur).stream()
          .filter(suc -> !result.contains(suc) /* && graph contains any preds XXX */)
          .forEach(
              suc -> {
                result.add(suc);
                restoreElement(pCfa, suc);
                graph.addNode(suc);
                restoreEdgesWithOtherPresentNodes(cur);
              });
    }
  }

  private void restoreEdgesWithOtherPresentNodes(Element pNode) {
    for (EndpointPair<Element> edge : originalBackupGraph.incidentEdges(pNode)) {
      if (graph.nodes().contains(edge.nodeU()) && graph.nodes().contains(edge.nodeV())) {
        graph.putEdgeValue(edge, originalBackupGraph.edgeValue(edge).orElseThrow());
      }
    }
  }

  /** A human-readable name of CFA elements. */
  public String getElementTitle() {
    return elementTitle;
  }

  public ImmutableValueGraph<Element, ElementRelation> getGraph() {
    return ImmutableValueGraph.copyOf(graph);
  }

  /**
   * Return all possible atomic CFA parts — elements — this manager can remove.
   *
   * <p>Mutations need to be applicable in arbitrary combinations without new elements of the same
   * kind appearing or present elements implicitly hidden or blocked from removing.
   */
  public ImmutableSet<Element> getAllElements() {
    Preconditions.checkState(graph != null, getElementTitle() + " graph was not set up");
    return ImmutableSet.copyOf(graph.nodes());
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
}
