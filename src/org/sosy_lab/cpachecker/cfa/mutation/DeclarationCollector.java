// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.sosy_lab.cpachecker.cfa.ast.AAstNode;
import org.sosy_lab.cpachecker.cfa.ast.AExpression;
import org.sosy_lab.cpachecker.cfa.ast.AIdExpression;
import org.sosy_lab.cpachecker.cfa.ast.AParameterDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.ASimpleDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.AVariableDeclaration;
import org.sosy_lab.cpachecker.cfa.types.Type;
import org.sosy_lab.cpachecker.cfa.types.c.CEnumType.CEnumerator;
import org.sosy_lab.cpachecker.util.CFAUtils;

class DeclarationCollector {
  Map<String, ImmutableSet<AVariableDeclaration>> usedLocalVariables = new TreeMap<>();
  Map<String, ImmutableSet<AParameterDeclaration>> usedParameters = new TreeMap<>();

  ImmutableSet<Type> usedTypes = ImmutableSet.of();
  ImmutableSet<AVariableDeclaration> usedGlobalVariables = ImmutableSet.of();
  ImmutableSet<CEnumerator> usedEnumerators = ImmutableSet.of();

  public Map<String, ImmutableSet<AVariableDeclaration>> getUsedLocalVariables() {
    return usedLocalVariables;
  }

  public Map<String, ImmutableSet<AParameterDeclaration>> getUsedParameters() {
    return usedParameters;
  }

  public ImmutableSet<Type> getUsedTypes() {
    return usedTypes;
  }

  public ImmutableSet<AVariableDeclaration> getUsedGlobalVariables() {
    return usedGlobalVariables;
  }

  public ImmutableSet<CEnumerator> getUsedEnumerators() {
    return usedEnumerators;
  }

  public void collectUsed(FunctionCFAsWithMetadata pCfa) {
    List<Type> types = new ArrayList<>(usedTypes);
    List<AVariableDeclaration> globals = new ArrayList<>(usedGlobalVariables);
    List<CEnumerator> enumerators = new ArrayList<>(usedEnumerators);

    for (String funName : pCfa.getCFANodes().keySet()) {
      List<AParameterDeclaration> params =
          new ArrayList<>(usedParameters.getOrDefault(funName, ImmutableSet.of()));
      List<AVariableDeclaration> vars =
          new ArrayList<>(usedLocalVariables.getOrDefault(funName, ImmutableSet.of()));

      for (AAstNode astNode :
          FluentIterable.from(pCfa.getCFANodes().get(funName))
              .transformAndConcat(CFAUtils::leavingEdges)
              .transformAndConcat(CFAUtils::getAstNodesFromCfaEdge)
              .transformAndConcat(CFAUtils::traverseRecursively)) {

        if (astNode instanceof AIdExpression) {
          // add type
          types.add(((AExpression) astNode).getExpressionType());

          ASimpleDeclaration decl = ((AIdExpression) astNode).getDeclaration();

          if (decl instanceof AParameterDeclaration) {
            params.add((AParameterDeclaration) decl);

          } else if (decl instanceof CEnumerator) {
            enumerators.add((CEnumerator) decl);

          } else if (decl instanceof AVariableDeclaration) {
            if (((AVariableDeclaration) decl).isGlobal()) {
              globals.add((AVariableDeclaration) decl);
            } else {
              vars.add((AVariableDeclaration) decl);
            }

          } else {
            // ADeclaration, i.e. function or C type declaration
          }

        } else if (astNode instanceof AExpression) {
          // no need to collect function calls too as functions are dealt with in other place
          // mark type as used
          types.add(((AExpression) astNode).getExpressionType());
        }
      } // end for astNode

      // transform function-wise lists to sets
      usedParameters.put(funName, ImmutableSet.copyOf(params));
      usedLocalVariables.put(funName, ImmutableSet.copyOf(vars));
    }

    // transform global lists to sets
    usedTypes = ImmutableSet.copyOf(types);
    usedGlobalVariables = ImmutableSet.copyOf(globals);
    usedEnumerators = ImmutableSet.copyOf(enumerators);
  }
}
