// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.graph.Graph;

interface CFAElementManipulator<Element> {
  enum NeedsDependencies {
    /** An object must be present while any predecessor is present. */
    ANY,
    /** An object can be present only if all predecessors are present. */
    ALL
  }

  /**
   * Return all possible atomic CFA parts, elements, this manager can remove.
   *
   * <p>The elements form a dependency graph, with edges from dependency to dependent object. For
   * functions it is a call graph, for basic blocks it can be a graph with edges from block with a
   * declaration to all blocks using it, for structured programming structures it can be a graph
   * with edges from including structure to the included ones.
   *
   * <p>Mutations need to be applicable in arbitrary combinations without new elements of the same
   * kind appearing or present elements implicitly hidden or blocked from removing.
   */
  public Graph<Element> getAllElements(FunctionCFAsWithMetadata pCfa);

  /** How to apply chosen mutation */
  public void remove(FunctionCFAsWithMetadata pCfa, Element pChosen);

  /** How to rollback a mutation */
  public void restore(FunctionCFAsWithMetadata pCfa, Element pRemoved);

  /** A human-readable name of changes. */
  public String getElementTitle();

  /** A human-readable name of dependency in a change dependency graph. */
  public String getElementRelationTitle();
}
