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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.Graph;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.ImmutableGraph;
import com.google.common.graph.ImmutableGraph.Builder;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.CFACreationUtils;
import org.sosy_lab.cpachecker.cfa.FunctionCallCollector;
import org.sosy_lab.cpachecker.cfa.Language;
import org.sosy_lab.cpachecker.cfa.ast.AFunctionCall;
import org.sosy_lab.cpachecker.cfa.ast.AFunctionCallExpression;
import org.sosy_lab.cpachecker.cfa.ast.AFunctionDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.FileLocation;
import org.sosy_lab.cpachecker.cfa.ast.c.CDesignatedInitializer;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CIdExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CInitializer;
import org.sosy_lab.cpachecker.cfa.ast.c.CInitializerExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CInitializerList;
import org.sosy_lab.cpachecker.cfa.ast.c.CReturnStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CVariableDeclaration;
import org.sosy_lab.cpachecker.cfa.model.AStatementEdge;
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

class FunctionBodyManipulator implements CFAElementManipulator<FunctionBodyManipulator.FunctionElement> {
  //  private static final NeedsDependencies needsAnyCaller = NeedsDependencies.ANY;

  class FunctionElement {
    private final String name;
    private final FunctionEntryNode entry;
    private final NavigableSet<CFANode> oldNodes;
    private NavigableSet<CFANode> newNodes = null;
    private ImmutableList<CFAEdge> halfConnectedEdges = ImmutableList.of();

    FunctionElement(FunctionCFAsWithMetadata pCfa, String pName) {
      Preconditions.checkArgument(!Strings.isNullOrEmpty(pName));
      name = pName;
      entry = pCfa.getFunctions().get(name);
      oldNodes = new TreeSet<>(pCfa.getCFANodes().get(name));
    }

    @Override
    public String toString() {
      return name;
    }

    private void insertDummyReturn() {
      FileLocation loc = FileLocation.DUMMY;
      FunctionExitNode exit = entry.getExitNode();
      ImmutableList.Builder<CFAEdge> halfConnected = ImmutableList.builder();

      newNodes = new TreeSet<>();
      newNodes.add(entry);
      newNodes.add(exit);

      assert entry.getNumLeavingEdges() == 1;
      halfConnected.add(entry.getLeavingEdge(0));
      halfConnected.addAll(CFAUtils.enteringEdges(exit));
      CFAMutationUtils.removeAllEnteringEdges(exit);

      final CType returnType = (CType) entry.getFunction().getType().getReturnType();
      if (returnType instanceof CVoidType) {
        // no need in return
        CFAMutationUtils.changeSuccessor(entry.getLeavingEdge(0).getSuccessor(), exit);
        halfConnectedEdges = halfConnected.build();
      }

      // add node after entry
      CFANode lastNode = new CFANode(entry.getFunction());
      CFAMutationUtils.changeSuccessor(entry.getLeavingEdge(0).getSuccessor(), lastNode);
      newNodes.add(lastNode);

      CInitializer init = CDefaults.forType(returnType, loc);
      CExpression rexp = null;

      if (init instanceof CInitializerList) {
        // complex type, can not write return with this value
        CFANode node = new CFANode(entry.getFunction());
        newNodes.add(node);

        CVariableDeclaration decl =
            new CVariableDeclaration(
                loc,
                false,
                CStorageClass.AUTO,
                returnType,
                retVarName,
                retVarName,
                retVarName,
                init);
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
          new CReturnStatementEdge(rst.toASTString(), rst, loc, lastNode, exit), logger);
      halfConnectedEdges = halfConnected.build();
    }
  }

  private static final String retVarName = "CPAchecker_CFAmutator_dummy_retval";

  private ImmutableSet<String> bodiesNeeded;

  private final ThreadCreateTransformer threadCreateTransformer;

  private final LogManager logger;

  private Map<String, FunctionElement> functionElements = new TreeMap<>();

  public FunctionBodyManipulator(Configuration pConfig, LogManager pLogger)
      throws InvalidConfigurationException {
    logger = Preconditions.checkNotNull(pLogger);
    threadCreateTransformer = new ThreadCreateTransformer(pLogger, pConfig);
  }

  private FunctionElement functionElementByName(FunctionCFAsWithMetadata pCfa, String pName) {
    FunctionElement result = functionElements.get(pName);
    if (result == null) {
      result = new FunctionElement(pCfa, pName);
      functionElements.put(pName, result);
    }
    return result;
  }

  @Override
  public Graph<FunctionElement> getAllElements(FunctionCFAsWithMetadata pCfa) {
    ImmutableGraph.Builder<FunctionElement> result =
        GraphBuilder.directed().expectedNodeCount(pCfa.getFunctions().size()).immutable();

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

    for (String name : pCfa.getFunctions().keySet()) {
      result.addNode(functionElementByName(pCfa, name));
    }

    addDirectCalls(pCfa, result);
    // TODO other function calls... (via pointers and thread creation too)

    return result.build();
  }

  private void addDirectCalls(FunctionCFAsWithMetadata pCfa, Builder<FunctionElement> pCallGraph) {
    FunctionCallCollector fcc = new FunctionCallCollector();
    for (FunctionEntryNode entry : pCfa.getFunctions().values()) {
      CFATraversal.dfs().ignoreFunctionCalls().traverseOnce(entry, fcc);
    }

    for (AStatementEdge edge : fcc.getFunctionCalls()) {
      String callerName = edge.getPredecessor().getFunctionName();
      AFunctionCall callStmt = (AFunctionCall) edge.getStatement();
      AFunctionCallExpression callExpr = callStmt.getFunctionCallExpression();
      AFunctionDeclaration decl = callExpr.getDeclaration();
      if (decl == null) {
        continue;
      }
      String calledName = callExpr.getDeclaration().getName();
      pCallGraph.putEdge(
          functionElementByName(pCfa, callerName), functionElementByName(pCfa, calledName));
    }
  }

  @Override
  public void remove(FunctionCFAsWithMetadata pCfa, FunctionElement pChosen) {
    if (bodiesNeeded.contains(pChosen.name)) {
      logger.log(Level.FINE, "Replacing function", pChosen, "body with default 'return'");
      if (pCfa.getLanguage() != Language.C) {
        throw new UnsupportedOperationException(
            "Replacing function bodies with dummy returns is not supported for Java");
      }

      pChosen.insertDummyReturn();
      pCfa.getCFANodes().removeAll(pChosen.name);
      pCfa.getCFANodes().putAll(pChosen.name, pChosen.newNodes);

    } else {
      logger.log(Level.FINE, "Removing function", pChosen);
      // remove function entry
      pCfa.getFunctions().remove(pChosen.name);
      // remove nodes
      pCfa.getCFANodes().asMap().remove(pChosen.name);
    }
  }

  @Override
  public void restore(FunctionCFAsWithMetadata pCfa, FunctionElement pRemoved) {
    logger.log(Level.FINE, "Restoring function", pRemoved.name);
    pCfa.getCFANodes().removeAll(pRemoved.name);
    pCfa.getCFANodes().putAll(pRemoved.name, pRemoved.oldNodes);
    pCfa.getFunctions().put(pRemoved.name, pRemoved.entry);
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

  @Override
  public String getElementTitle() {
    // TODO Auto-generated method stub
    return "functions";
  }

  @Override
  public String getElementRelationTitle() {
    // TODO Auto-generated method stub
    return "is called by";
  }
}
