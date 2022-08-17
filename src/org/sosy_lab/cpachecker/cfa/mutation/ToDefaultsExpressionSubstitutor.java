// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import org.sosy_lab.cpachecker.cfa.ast.AExpression;
import org.sosy_lab.cpachecker.cfa.ast.AFunctionCallExpression;
import org.sosy_lab.cpachecker.cfa.ast.AFunctionDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.AInitializer;
import org.sosy_lab.cpachecker.cfa.ast.ALeftHandSide;
import org.sosy_lab.cpachecker.cfa.ast.ALiteralExpression;
import org.sosy_lab.cpachecker.cfa.ast.FileLocation;
import org.sosy_lab.cpachecker.cfa.ast.c.CDesignatedInitializer;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionCallExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.CInitializer;
import org.sosy_lab.cpachecker.cfa.ast.c.CInitializerExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CInitializerList;
import org.sosy_lab.cpachecker.cfa.ast.java.JExpression;
import org.sosy_lab.cpachecker.cfa.ast.java.JInitializer;
import org.sosy_lab.cpachecker.cfa.types.Type;
import org.sosy_lab.cpachecker.cfa.types.c.CDefaults;
import org.sosy_lab.cpachecker.cfa.types.c.CType;
import org.sosy_lab.cpachecker.util.Pair;

/**
 * Replaces expression or initializer with some 'default' expression of same {@link Type}. (E.g. for
 * simple integers it's likely {@code 0} literal.)
 *
 * <p>For assignments, the right side is replaced. For function calls and function call assignments,
 * every argument is substituted.
 */
class ToDefaultsExpressionSubstitutor extends AbstractExpressionSubstitutor {
  private CExpression toDefault(AExpression pExpr) {
    CInitializer init =
        CDefaults.forType((CType) pExpr.getExpressionType(), pExpr.getFileLocation());
    if (!(init instanceof CInitializerExpression)) {
      return null;
    }
    return ((CInitializerExpression) init).getExpression();
  }

  @Override
  protected AExpression substituteExpression(AExpression pExpr) {
    if (pExpr instanceof ALiteralExpression) {
      // dont substitute literals
      return null;
    }

    if (pExpr instanceof CExpression) {
      return toDefault(pExpr);
    } else if (pExpr instanceof JExpression) {
      return null;
    } else {
      throw new AssertionError();
    }
  }

  @Override
  protected AInitializer substituteInitializer(AInitializer pInit) {
    if (pInit == null) {
      return null;
    }
    final FileLocation loc = pInit.getFileLocation();

    if (pInit instanceof CInitializer) {

      if (pInit instanceof CDesignatedInitializer) {
        // XXX should work?
        return new CInitializerList(loc, ImmutableList.of());

      } else if (pInit instanceof CInitializerList) {
        if (((CInitializerList) pInit).getInitializers().isEmpty()) {
          return null;
        }
        return new CInitializerList(loc, ImmutableList.of());

      } else if (pInit instanceof CInitializerExpression) {
        CExpression pExpr = ((CInitializerExpression) pInit).getExpression();
        return CDefaults.forType(pExpr.getExpressionType(), pExpr.getFileLocation());

      } else {
        throw new AssertionError();
      }

    } else if (pInit instanceof JInitializer) {
      return null;

    } else {
      throw new AssertionError();
    }
  }

  @Override
  protected Pair<ALeftHandSide, AExpression> substituteAssignment(
      ALeftHandSide pLeftHandSide, AExpression pRightHandSide) {
    // replace only right side
    if (pRightHandSide instanceof CExpression) {
      return Pair.of(pLeftHandSide, toDefault(pRightHandSide));
    } else if (pRightHandSide instanceof JExpression) {
      return null;
    } else {
      throw new AssertionError();
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  protected AFunctionCallExpression substituteCall(
      AFunctionCallExpression pFunctionCallExpression) {

    FileLocation loc = pFunctionCallExpression.getFileLocation();
    Type type = pFunctionCallExpression.getExpressionType();
    AExpression fun = pFunctionCallExpression.getFunctionNameExpression();
    AFunctionDeclaration decl = pFunctionCallExpression.getDeclaration();

    if (pFunctionCallExpression instanceof CFunctionCallExpression) {
      // replace arguments
      List<CExpression> args = new ArrayList<>();
      for (AExpression arg : pFunctionCallExpression.getParameterExpressions()) {
        CExpression arg2 = (CExpression) substituteExpression(arg);
        if (arg2 == null) {
          arg2 = (CExpression) arg;
        }
        args.add(arg2);
      }

      return new CFunctionCallExpression(
          loc, (CType) type, (CExpression) fun, args, (CFunctionDeclaration) decl);
    }

    // TODO Java
    return null;
  }

  @Override
  protected Pair<ALeftHandSide, AFunctionCallExpression> substituteAssignmentCall(
      ALeftHandSide pLeftHandSide, AFunctionCallExpression pRightHandSide) {
    AFunctionCallExpression rhs = substituteCall(pRightHandSide);
    return Pair.of(pLeftHandSide, rhs);
  }
}
