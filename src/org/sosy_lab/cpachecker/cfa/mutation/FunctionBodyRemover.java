// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.logging.Level;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.CFACreationUtils;
import org.sosy_lab.cpachecker.cfa.Language;
import org.sosy_lab.cpachecker.cfa.ast.FileLocation;
import org.sosy_lab.cpachecker.cfa.ast.c.CDesignatedInitializer;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CIdExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CInitializer;
import org.sosy_lab.cpachecker.cfa.ast.c.CInitializerExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CInitializerList;
import org.sosy_lab.cpachecker.cfa.ast.c.CReturnStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CVariableDeclaration;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.model.FunctionExitNode;
import org.sosy_lab.cpachecker.cfa.model.c.CDeclarationEdge;
import org.sosy_lab.cpachecker.cfa.model.c.CReturnStatementEdge;
import org.sosy_lab.cpachecker.cfa.postprocessing.function.ThreadCreateTransformer;
import org.sosy_lab.cpachecker.cfa.postprocessing.function.ThreadCreateTransformer.ThreadFinder;
import org.sosy_lab.cpachecker.cfa.types.c.CDefaults;
import org.sosy_lab.cpachecker.cfa.types.c.CStorageClass;
import org.sosy_lab.cpachecker.cfa.types.c.CType;
import org.sosy_lab.cpachecker.cfa.types.c.CVoidType;
import org.sosy_lab.cpachecker.util.CFATraversal;
import org.sosy_lab.cpachecker.util.CFAUtils;

class RemovedFunction {
  final FunctionEntryNode entry;
  final SortedSet<CFANode> nodes;
  final ImmutableList<CFAEdge> halfConnectedEdges;

  RemovedFunction(
      FunctionEntryNode pEntry, SortedSet<CFANode> pNodes, ImmutableList<CFAEdge> pEdges) {
    entry = pEntry;
    nodes = pNodes;
    halfConnectedEdges = pEdges;
  }

  RemovedFunction(FunctionEntryNode pEntry, SortedSet<CFANode> pNodes) {
    this(pEntry, pNodes, ImmutableList.of());
  }
}

/**
 * Remove a function CFA completely. In case of {@code main} or a function that is called via thread
 * operations, the body must be present, so replace the body with dummy {@code return}.
 */
public class FunctionBodyRemover extends GenericDeltaDebuggingStrategy<String, RemovedFunction> {

  private final static String retVarName = "CPAchecker_CFAmutator_dummy_retval";

  private ImmutableSet<String> bodiesNeeded;

  private final ThreadCreateTransformer threadCreateTransformer;

  public FunctionBodyRemover(Configuration pConfig, LogManager pLogger)
      throws InvalidConfigurationException {
    super(pLogger.withComponentName(FunctionBodyRemover.class.getSimpleName()), "functions");
    threadCreateTransformer = new ThreadCreateTransformer(pLogger, pConfig);
  }

  @Override
  protected List<String> getAllObjects(FunctionCFAsWithMetadata pCfa) {
    List<String> result = new ArrayList<>(pCfa.getFunctions().size());

    ImmutableSet.Builder<String> builder = ImmutableSet.builder();
    builder.add(pCfa.getMainFunction().getFunctionName());

    // thread operations need body of thread-called function to be present
    ThreadFinder threadFinder = threadCreateTransformer.new ThreadFinder();
    for (FunctionEntryNode functionStartNode : pCfa.getFunctions().values()) {
      CFATraversal.dfs().traverseOnce(functionStartNode, threadFinder);
    }

    threadFinder.getThreadCreates().values().stream()
        .map(
            threadCreate ->
                ThreadCreateTransformer.getFunctionName(
                    threadCreate.getParameterExpressions().get(2)))
        .forEach(idExp -> builder.add(idExp.getName()));

    bodiesNeeded = builder.build();

    pCfa.getFunctions().keySet().stream()
        .filter(name -> !pCfa.getMainFunction().getFunctionName().equals(name))
        .forEach(name -> result.add(name));

    if (pCfa.getLanguage() != Language.C) {
      // cant insert dummy return
      result.removeAll(bodiesNeeded);
    } else {
      for (String name : bodiesNeeded) {
        FunctionEntryNode entry = pCfa.getFunctions().get(name);
        assert entry.getNumLeavingEdges() == 1;
        if (entry.getLeavingEdge(0).getSuccessor() == entry.getExitNode()) {
          // already simple body of single edge
          result.remove(name);
        }
      }
    }

    return result;
  }

  @Override
  protected RemovedFunction removeObject(FunctionCFAsWithMetadata pCfa, String pChosen) {
    if (bodiesNeeded.contains(pChosen)) {
      logger.log(Level.FINE, "Replacing function", pChosen, "body with default 'return'");
      if (pCfa.getLanguage() != Language.C) {
        throw new UnsupportedOperationException(
            "Replacing function bodies with dummy returns is not supported for Java");
      }

      FunctionEntryNode entry = pCfa.getFunctions().get(pChosen);
      FunctionExitNode exit = entry.getExitNode();

      // remove all nodes
      SortedSet<CFANode> nodes = pCfa.getCFANodes().removeAll(pChosen);
      // but return entry and exit
      pCfa.getCFANodes().put(pChosen, entry);
      pCfa.getCFANodes().put(pChosen, exit);

      var edges = insertDummyReturn(pCfa.getCFANodes().get(pChosen), entry);
      return new RemovedFunction(entry, nodes, edges);

    } else {
      logger.log(Level.FINE, "Removing function", pChosen);
      // remove function entry
      FunctionEntryNode entry = pCfa.getFunctions().remove(pChosen);
      // remove nodes
      SortedSet<CFANode> nodes = pCfa.getCFANodes().removeAll(pChosen);
      return new RemovedFunction(entry, nodes);
    }
  }

  private ImmutableList<CFAEdge> insertDummyReturn(
      SortedSet<CFANode> pFunctionNodes, FunctionEntryNode entry) {
    FileLocation loc = FileLocation.DUMMY;
    FunctionExitNode exit = entry.getExitNode();
    ImmutableList.Builder<CFAEdge> halfConnected = ImmutableList.builder();

    assert entry.getNumLeavingEdges() == 1;
    halfConnected.add(entry.getLeavingEdge(0));
    halfConnected.addAll(CFAUtils.enteringEdges(exit));
    CFAMutationUtils.removeAllEnteringEdges(exit);

    final CType returnType = (CType) entry.getFunction().getType().getReturnType();
    if (returnType instanceof CVoidType) {
      // no need in return
      CFAMutationUtils.changeSuccessor(entry.getLeavingEdge(0).getSuccessor(), exit);
      return halfConnected.build();
    }

    // add node after entry
    CFANode lastNode = new CFANode(entry.getFunction());
    CFAMutationUtils.changeSuccessor(entry.getLeavingEdge(0).getSuccessor(), lastNode);
    pFunctionNodes.add(lastNode);

    CInitializer init = CDefaults.forType(returnType, loc);
    CExpression rexp = null;

    if (init instanceof CInitializerList) {
      // complex type, can not write return with this value
      CFANode node = new CFANode(entry.getFunction());
      pFunctionNodes.add(node);

      CVariableDeclaration decl =
          new CVariableDeclaration(
              loc, false, CStorageClass.AUTO, returnType, retVarName, retVarName, retVarName, init);
      CFACreationUtils.addEdgeToCFA(
          new CDeclarationEdge(decl.toASTString(), loc, lastNode, node, decl), logger);
      lastNode = node;
      rexp = new CIdExpression(loc, decl);

    } else if (init instanceof CDesignatedInitializer) {
      throw new AssertionError();

    } else if (init instanceof CInitializerExpression) {
      rexp = ((CInitializerExpression) init).getExpression();
    }

    CReturnStatement rst = new CReturnStatement(loc, Optional.of(rexp), Optional.empty());
    CFACreationUtils.addEdgeToCFA(
        new CReturnStatementEdge(rst.toASTString(), rst, loc, lastNode, exit),
        logger);
    return halfConnected.build();
  }

  @Override
  protected void restoreObject(FunctionCFAsWithMetadata pCfa, RemovedFunction pRemoved) {
    String functionName = pRemoved.entry.getFunctionName();
    logger.log(Level.FINE, "Restoring function", functionName);
    // remove possible dummy return nodes
    pCfa.getCFANodes().removeAll(functionName);
    // restore body nodes
    pCfa.getCFANodes().putAll(functionName, pRemoved.nodes);
    pCfa.getFunctions().put(functionName, pRemoved.entry);
    // restore edges if needed
    if (pRemoved.halfConnectedEdges.isEmpty()) {
      return;
    }
    pRemoved.entry.removeLeavingEdge(pRemoved.entry.getLeavingEdge(0));
    pRemoved.entry.addLeavingEdge(pRemoved.halfConnectedEdges.get(0));
    FunctionExitNode exit = pRemoved.entry.getExitNode();
    CFAMutationUtils.removeAllEnteringEdges(exit);
    for (int i = 1; i < pRemoved.halfConnectedEdges.size(); i++) {
      exit.addEnteringEdge(pRemoved.halfConnectedEdges.get(i));
    }
  }
}
