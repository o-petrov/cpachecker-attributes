// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.collect.TreeMultimap;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.sosy_lab.cpachecker.cfa.Language;
import org.sosy_lab.cpachecker.cfa.MutableCFA;
import org.sosy_lab.cpachecker.cfa.ParseResult;
import org.sosy_lab.cpachecker.cfa.ast.ADeclaration;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.types.MachineModel;
import org.sosy_lab.cpachecker.util.CFATraversal;
import org.sosy_lab.cpachecker.util.CFATraversal.EdgeCollectingCFAVisitor;
import org.sosy_lab.cpachecker.util.Pair;

/**
 * This class stores CFA of every function and list of global declarations as {@link ParseResult}
 * does. It also stores machine model, main function, and language, as {@link MutableCFA} does.
 *
 * <p>Besides nodes, it also stores all edges in CFA. This is needed to undo changes from following
 * steps of CFA creation.
 */
// TODO consistent interface. Class will be immutable?
class FunctionCFAsWithMetadata extends ParseResult {
  private final Map<String, List<CFAEdge>> localEdges = new TreeMap<>();
  private final MachineModel machinemodel;
  private final FunctionEntryNode mainFunction;
  private final Language language;

  public FunctionCFAsWithMetadata(
      MachineModel pMachineModel,
      NavigableMap<String, FunctionEntryNode> pFunctions,
      TreeMultimap<String, CFANode> pAllNodes,
      FunctionEntryNode pMainFunction,
      List<Path> pFileNames,
      Language pLanguage,
      List<Pair<ADeclaration, String>> pGlobalDeclarations) {
    super(pFunctions, pAllNodes, pGlobalDeclarations, pFileNames);
    machinemodel = pMachineModel;
    mainFunction = pMainFunction;
    language = pLanguage;

    // store present edges too
    for (FunctionEntryNode entry : pFunctions.values()) {
      EdgeCollectingCFAVisitor v = new EdgeCollectingCFAVisitor();
      CFATraversal.dfs().traverseOnce(entry, v);
      localEdges.put(entry.getFunctionName(), v.getVisitedEdges());
    }
  }

  public Language getLanguage() {
    return language;
  }

  public MachineModel getMachinemodel() {
    return machinemodel;
  }

  public FunctionEntryNode getMainFunction() {
    return mainFunction;
  }

  public Map<String, List<CFAEdge>> getLocalEdges() {
    return localEdges;
  }
}
