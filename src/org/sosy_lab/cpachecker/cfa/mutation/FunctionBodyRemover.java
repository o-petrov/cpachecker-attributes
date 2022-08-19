// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.logging.Level;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.util.Pair;

/** Remove a function CFA completely. */
// TODO replace main with {@code return 0;}
public class FunctionBodyRemover
    extends GenericDeltaDebuggingStrategy<String, Pair<FunctionEntryNode, SortedSet<CFANode>>> {

  public FunctionBodyRemover(LogManager pLogger) {
    super(pLogger.withComponentName(FunctionBodyRemover.class.getSimpleName()), "functions");
  }

  @Override
  protected List<String> getAllObjects(FunctionCFAsWithMetadata pCfa) {
    List<String> result = new ArrayList<>(pCfa.getFunctions().size());
    pCfa.getFunctions().keySet().stream()
        .filter(name -> !pCfa.getMainFunction().getFunctionName().equals(name))
        .forEach(name -> result.add(name));
    return result;
  }

  @Override
  protected Pair<FunctionEntryNode, SortedSet<CFANode>> removeObject(
      FunctionCFAsWithMetadata pCfa, String pChosen) {
    logger.log(Level.FINE, "Removing function", pChosen);
    // remove function entry
    FunctionEntryNode entry = pCfa.getFunctions().remove(pChosen);
    // remove nodes
    SortedSet<CFANode> nodes = pCfa.getCFANodes().removeAll(pChosen);
    // can not remove main function completely, TODO insert dummy body
    assert !pCfa.getMainFunction().getFunctionName().equals(pChosen);
    return Pair.of(entry, nodes);
  }

  @Override
  protected void restoreObject(
      FunctionCFAsWithMetadata pCfa, Pair<FunctionEntryNode, SortedSet<CFANode>> pRemoved) {
    String functionName = pRemoved.getFirst().getFunctionName();
    logger.log(Level.FINE, "Restoring function", functionName);
    pCfa.getCFANodes().putAll(functionName, pRemoved.getSecond());
    pCfa.getFunctions().put(functionName, pRemoved.getFirst());
  }
}
