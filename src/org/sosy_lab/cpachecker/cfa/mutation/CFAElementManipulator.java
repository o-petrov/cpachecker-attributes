// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.collect.ImmutableSet;
import com.google.common.graph.ImmutableValueGraph;
import com.google.common.graph.ValueGraph;
import java.util.Collection;

interface CFAElementManipulator<Element, ElementRelation> {

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
  public void setupFromCfa(FunctionCFAsWithMetadata pCfa);

  public ImmutableValueGraph<Element, ElementRelation> getGraph();

  /** A human-readable name of CFA elements. */
  public String getElementTitle();

  /**
   * Return all possible atomic CFA parts — elements — this manager can remove.
   *
   * <p>Mutations need to be applicable in arbitrary combinations without new elements of the same
   * kind appearing or present elements implicitly hidden or blocked from removing.
   */
  public ImmutableSet<Element> getAllElements();

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
  public ImmutableSet<Element> getNextLevelElements();

  /** Remove chosen elements from CFA (ignore hierarchy/dependency graph). */
  public void remove(FunctionCFAsWithMetadata pCfa, Collection<Element> pChosen);

  /**
   * Remove chosen elements with their (direct and transitive) children in hierarchy/dependency
   * graph. (If the graph is not a tree, it is not exactly children; there are different decisions.)
   */
  public void prune(FunctionCFAsWithMetadata pCfa, Collection<Element> pChosen);

  /** Rollback the last mutation this manipulator did. */
  public void rollback(FunctionCFAsWithMetadata pCfa);

  /** Restore given elements (XXX does order matter?). */
  public void restore(FunctionCFAsWithMetadata pCfa, Collection<Element> pChosen);

  /** Which elements remain in the CFA if manipulator removes given elements. */
  ImmutableSet<Element> whatRemainsIfRemove(Collection<Element> pChosen);

  /** Which elements remain in the CFA if manipulator prunes given elements. */
  ImmutableSet<Element> whatRemainsIfPrune(Collection<Element> pChosen);
}
