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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.cpachecker.cfa.ast.AAstNode;
import org.sosy_lab.cpachecker.cfa.ast.AExpression;
import org.sosy_lab.cpachecker.cfa.ast.AIdExpression;
import org.sosy_lab.cpachecker.cfa.ast.AParameterDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.ASimpleDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.AVariableDeclaration;
import org.sosy_lab.cpachecker.cfa.types.Type;
import org.sosy_lab.cpachecker.cfa.types.c.CArrayType;
import org.sosy_lab.cpachecker.cfa.types.c.CBitFieldType;
import org.sosy_lab.cpachecker.cfa.types.c.CCompositeType;
import org.sosy_lab.cpachecker.cfa.types.c.CCompositeType.CCompositeTypeMemberDeclaration;
import org.sosy_lab.cpachecker.cfa.types.c.CElaboratedType;
import org.sosy_lab.cpachecker.cfa.types.c.CEnumType.CEnumerator;
import org.sosy_lab.cpachecker.cfa.types.c.CFunctionType;
import org.sosy_lab.cpachecker.cfa.types.c.CPointerType;
import org.sosy_lab.cpachecker.cfa.types.c.CType;
import org.sosy_lab.cpachecker.cfa.types.c.CTypedefType;
import org.sosy_lab.cpachecker.cfa.types.c.DefaultCTypeVisitor;
import org.sosy_lab.cpachecker.cfa.types.java.JArrayType;
import org.sosy_lab.cpachecker.cfa.types.java.JClassOrInterfaceType;
import org.sosy_lab.cpachecker.cfa.types.java.JMethodType;
import org.sosy_lab.cpachecker.cfa.types.java.JType;
import org.sosy_lab.cpachecker.exceptions.NoException;
import org.sosy_lab.cpachecker.util.CFAUtils;

class DeclarationCollector {
  private Map<String, ImmutableSet<AVariableDeclaration>> usedLocalVariables = new TreeMap<>();
  private Map<String, ImmutableSet<AParameterDeclaration>> usedParameters = new TreeMap<>();

  private ImmutableSet<Type> usedTypes = ImmutableSet.of();
  private ImmutableSet<AVariableDeclaration> usedGlobalVariables = ImmutableSet.of();
  private ImmutableSet<CEnumerator> usedEnumerators = ImmutableSet.of();

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
    TypeCollector types = new TypeCollector(usedTypes);
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
    usedTypes = ImmutableSet.copyOf(types.getCollectedTypes());
    usedGlobalVariables = ImmutableSet.copyOf(globals);
    usedEnumerators = ImmutableSet.copyOf(enumerators);
  }

  private static class TypeCollector extends DefaultCTypeVisitor<Void, NoException> {

    private final Set<Type> collectedTypes;

    public TypeCollector(Set<Type> pInit) {
      collectedTypes = new HashSet<>(pInit);
    }

    public Set<Type> getCollectedTypes() {
      return ImmutableSet.copyOf(collectedTypes);
    }

    private void addJType(JType pT) {
      if (collectedTypes.add(pT)) {

        if (pT instanceof JMethodType) {
          ((JMethodType) pT).getParameters().forEach(t -> add(t));
          add(((JMethodType) pT).getReturnType());

        } else if (pT instanceof JArrayType) {
          add(((JArrayType) pT).getElementType());

        } else if (pT instanceof JClassOrInterfaceType) {
          ((JClassOrInterfaceType) pT).getAllEnclosingTypes().forEach(t -> add(t));
          ((JClassOrInterfaceType) pT).getAllSuperTypesOfType().forEach(t -> add(t));

        }
      }
    }

    public void add(Type pT) {
      if (pT instanceof CType) {
        ((CType) pT).accept(this);
      } else if (pT instanceof JType) {
        addJType((JType) pT);
      }
    }

    @Override
    public @Nullable Void visitDefault(CType pT) {
      collectedTypes.add(pT);
      return null;
    }

    @Override
    public @Nullable Void visit(CArrayType pArrayType) {
      if (collectedTypes.add(pArrayType)) {
        pArrayType.getType().accept(this);
        if (pArrayType.getLength() != null) {
          pArrayType.getLength().getExpressionType().accept(this);
        }
      }
      return null;
    }

    @Override
    public @Nullable Void visit(CCompositeType pCompositeType) {
      if (collectedTypes.add(pCompositeType)) {
        for (CCompositeTypeMemberDeclaration member : pCompositeType.getMembers()) {
          member.getType().accept(this);
        }
      }
      return null;
    }

    @Override
    public @Nullable Void visit(CElaboratedType pElaboratedType) {
      if (collectedTypes.add(pElaboratedType)) {
        if (pElaboratedType.getRealType() != null) {
          pElaboratedType.getRealType().accept(this);
        }
      }
      return null;
    }

    @Override
    public @Nullable Void visit(CFunctionType pFunctionType) {
      if (collectedTypes.add(pFunctionType)) {
        for (CType parameterType : pFunctionType.getParameters()) {
          parameterType.accept(this);
        }
        pFunctionType.getReturnType().accept(this);
      }
      return null;
    }

    @Override
    public @Nullable Void visit(CPointerType pPointerType) {
      if (collectedTypes.add(pPointerType)) {
        pPointerType.getType().accept(this);
      }
      return null;
    }

    @Override
    public @Nullable Void visit(CTypedefType pTypedefType) {
      if (collectedTypes.add(pTypedefType)) {
        pTypedefType.getRealType().accept(this);
      }
      return null;
    }

    @Override
    public @Nullable Void visit(CBitFieldType pCBitFieldType) {
      if (collectedTypes.add(pCBitFieldType)) {
        pCBitFieldType.getType().accept(this);
      }
      return null;
    }
  }
}
