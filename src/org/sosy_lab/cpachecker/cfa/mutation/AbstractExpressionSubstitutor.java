// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import java.util.Optional;
import org.sosy_lab.cpachecker.cfa.ast.AAssignment;
import org.sosy_lab.cpachecker.cfa.ast.ADeclaration;
import org.sosy_lab.cpachecker.cfa.ast.AExpression;
import org.sosy_lab.cpachecker.cfa.ast.AExpressionAssignmentStatement;
import org.sosy_lab.cpachecker.cfa.ast.AExpressionStatement;
import org.sosy_lab.cpachecker.cfa.ast.AFunctionCallAssignmentStatement;
import org.sosy_lab.cpachecker.cfa.ast.AFunctionCallExpression;
import org.sosy_lab.cpachecker.cfa.ast.AFunctionCallStatement;
import org.sosy_lab.cpachecker.cfa.ast.AInitializer;
import org.sosy_lab.cpachecker.cfa.ast.ALeftHandSide;
import org.sosy_lab.cpachecker.cfa.ast.AReturnStatement;
import org.sosy_lab.cpachecker.cfa.ast.AStatement;
import org.sosy_lab.cpachecker.cfa.ast.AVariableDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.FileLocation;
import org.sosy_lab.cpachecker.cfa.ast.c.CAssignment;
import org.sosy_lab.cpachecker.cfa.ast.c.CDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpressionAssignmentStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpressionStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionCallAssignmentStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionCallExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionCallStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CInitializer;
import org.sosy_lab.cpachecker.cfa.ast.c.CLeftHandSide;
import org.sosy_lab.cpachecker.cfa.ast.c.CReturnStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CThreadOperationStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CVariableDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.java.JDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.java.JExpression;
import org.sosy_lab.cpachecker.cfa.ast.java.JExpressionAssignmentStatement;
import org.sosy_lab.cpachecker.cfa.ast.java.JExpressionStatement;
import org.sosy_lab.cpachecker.cfa.ast.java.JFieldDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.java.JInitializer;
import org.sosy_lab.cpachecker.cfa.ast.java.JLeftHandSide;
import org.sosy_lab.cpachecker.cfa.ast.java.JMethodInvocationAssignmentStatement;
import org.sosy_lab.cpachecker.cfa.ast.java.JMethodInvocationExpression;
import org.sosy_lab.cpachecker.cfa.ast.java.JMethodInvocationStatement;
import org.sosy_lab.cpachecker.cfa.ast.java.JObjectReferenceReturn;
import org.sosy_lab.cpachecker.cfa.ast.java.JReferencedMethodInvocationExpression;
import org.sosy_lab.cpachecker.cfa.ast.java.JReturnStatement;
import org.sosy_lab.cpachecker.cfa.ast.java.JStatement;
import org.sosy_lab.cpachecker.cfa.ast.java.JThisExpression;
import org.sosy_lab.cpachecker.cfa.ast.java.JVariableDeclaration;
import org.sosy_lab.cpachecker.cfa.model.ADeclarationEdge;
import org.sosy_lab.cpachecker.cfa.model.AReturnStatementEdge;
import org.sosy_lab.cpachecker.cfa.model.AStatementEdge;
import org.sosy_lab.cpachecker.cfa.model.BlankEdge;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.FunctionExitNode;
import org.sosy_lab.cpachecker.cfa.model.c.CDeclarationEdge;
import org.sosy_lab.cpachecker.cfa.model.c.CReturnStatementEdge;
import org.sosy_lab.cpachecker.cfa.model.c.CStatementEdge;
import org.sosy_lab.cpachecker.cfa.model.java.JDeclarationEdge;
import org.sosy_lab.cpachecker.cfa.model.java.JReturnStatementEdge;
import org.sosy_lab.cpachecker.cfa.model.java.JStatementEdge;
import org.sosy_lab.cpachecker.cfa.types.Type;
import org.sosy_lab.cpachecker.cfa.types.c.CStorageClass;
import org.sosy_lab.cpachecker.cfa.types.c.CType;
import org.sosy_lab.cpachecker.cfa.types.java.JClassType;
import org.sosy_lab.cpachecker.cfa.types.java.JType;
import org.sosy_lab.cpachecker.util.Pair;

/**
 * Replaces expressions (assignments, calls, and initializers too) on any given {@link CFAEdge}.
 *
 * <p>What will be substituted is defined by abstract methods, which get what to substitute. These
 * methods return {@code null} if they can not substitute given expression.
 *
 * <p>In case nothing on the given edge is changed, {@link #substitute(CFAEdge)} returns {@code
 * null}.
 */
abstract class AbstractExpressionSubstitutor {

  protected abstract AExpression substituteExpression(AExpression pExpr);

  protected abstract AInitializer substituteInitializer(AInitializer pInit);

  protected abstract Pair<ALeftHandSide, AExpression> substituteAssignment(
      ALeftHandSide pLeftHandSide, AExpression pRightHandSide);

  protected abstract AFunctionCallExpression substituteCall(
      AFunctionCallExpression pFunctionCallExpression);

  protected abstract Pair<ALeftHandSide, AFunctionCallExpression> substituteAssignmentCall(
      ALeftHandSide pLeftHandSide, AFunctionCallExpression pRightHandSide);

  public CFAEdge substitute(CFAEdge pEdge) {
    FileLocation loc = pEdge.getFileLocation();
    CFANode pred = pEdge.getPredecessor();
    CFANode succ = pEdge.getSuccessor();

    if (pEdge instanceof ADeclarationEdge) {
      ADeclaration decl = substitute(((ADeclarationEdge) pEdge).getDeclaration());
      if (decl == null) {
        return null;
      }
      String raw = decl.toASTString();

      if (pEdge instanceof CDeclarationEdge) {
        return new CDeclarationEdge(raw, loc, pred, succ, (CDeclaration) decl);
      } else if (pEdge instanceof JDeclarationEdge) {
        return new JDeclarationEdge(raw, loc, pred, succ, (JDeclaration) decl);
      } else {
        throw new AssertionError();
      }

    } else if (pEdge instanceof AStatementEdge) {
      AStatement stat = substitute(((AStatementEdge) pEdge).getStatement());
      if (stat == null) {
        return null;
      }
      String raw = stat.toASTString();

      if (pEdge instanceof CStatementEdge) {
        return new CStatementEdge(raw, (CStatement) stat, loc, pred, succ);
      } else if (pEdge instanceof JStatementEdge) {
        return new JStatementEdge(raw, (JStatement) stat, loc, pred, succ);
      } else {
        throw new AssertionError();
      }

    } else if (pEdge instanceof AReturnStatementEdge) {
      AReturnStatement rst = substitute(((AReturnStatementEdge) pEdge).getReturnStatement());
      if (rst == null) {
        return null;
      }
      String raw = rst.toASTString();

      if (pEdge instanceof CReturnStatementEdge) {
        return new CReturnStatementEdge(
            raw, (CReturnStatement) rst, loc, pred, (FunctionExitNode) succ);
      } else if (pEdge instanceof JReturnStatementEdge) {
        return new JReturnStatementEdge(
            raw, (JReturnStatement) rst, loc, pred, (FunctionExitNode) succ);
      } else {
        throw new AssertionError();
      }

    } else if (pEdge instanceof BlankEdge) {
      return null;

    } else {
      throw new AssertionError();
    }
  }

  private AReturnStatement substitute(AReturnStatement pStat) {
    AAssignment assign = pStat.asAssignment().orElse(null);
    if (assign != null) {
      // TODO deal with 'as-assignment' returns
      return null;
    }

    AExpression expr = pStat.getReturnValue().orElse(null);
    if (expr == null) {
      return null;
    }
    expr = substituteExpression(expr);
    if (expr == null) {
      return null;
    }

    FileLocation loc = pStat.getFileLocation();

    if (pStat instanceof CReturnStatement) {
      Optional<CExpression> optExpr = Optional.of((CExpression) expr);
      Optional<CAssignment> optAssign = Optional.empty();
      return new CReturnStatement(loc, optExpr, optAssign);

    } else if (pStat instanceof JObjectReferenceReturn) {
      return new JObjectReferenceReturn(
          loc, (JClassType) ((JThisExpression) expr).getExpressionType());

    } else if (pStat instanceof JReturnStatement) {
      Optional<JExpression> optExpr = Optional.of((JExpression) expr);
      return new JReturnStatement(loc, optExpr);

    } else {
      throw new AssertionError();
    }

  }

  private AStatement substitute(AStatement pStat) {
    if (pStat instanceof AExpressionStatement) {
      AExpression expr = substituteExpression(((AExpressionStatement) pStat).getExpression());
      if (expr == null) {
        return null;
      }
      return toStatement(expr);

    } else if (pStat instanceof AExpressionAssignmentStatement) {
      var pair =
          substituteAssignment(
              ((AExpressionAssignmentStatement) pStat).getLeftHandSide(),
              ((AExpressionAssignmentStatement) pStat).getRightHandSide());
      if (pair == null) {
        return null;
      }
      ALeftHandSide lhs = pair.getFirst();
      AExpression rhs = pair.getSecond();
      FileLocation loc = pStat.getFileLocation();

      if (lhs == null) {
        return toStatement(rhs);
      } else if (rhs == null) {
        return toStatement(lhs);
      }


      if (pStat instanceof CExpressionAssignmentStatement) {
        return new CExpressionAssignmentStatement(loc, (CLeftHandSide) lhs, (CExpression) rhs);
      } else if (pStat instanceof JExpressionAssignmentStatement) {
        return new JExpressionAssignmentStatement(loc, (JLeftHandSide) lhs, (JExpression) rhs);
      } else {
        throw new AssertionError();
      }

    } else if (pStat instanceof CThreadOperationStatement) {
      // TODO thread operations
      return null;

    } else if (pStat instanceof AFunctionCallStatement) {
      AFunctionCallExpression call =
          substituteCall(((AFunctionCallStatement) pStat).getFunctionCallExpression());
      if (call == null) {
        return null;
      }
      return toStatement(call);

    } else if (pStat instanceof AFunctionCallAssignmentStatement) {
      var pair =
          substituteAssignmentCall(
              ((AFunctionCallAssignmentStatement) pStat).getLeftHandSide(),
              ((AFunctionCallAssignmentStatement) pStat).getRightHandSide());
      if (pair == null) {
        return null;
      }
      ALeftHandSide lhs = pair.getFirst();
      AFunctionCallExpression rhs = pair.getSecond();
      FileLocation loc = pStat.getFileLocation();

      if (lhs == null) {
        return toStatement(rhs);
      } else if (rhs == null) {
        return toStatement(lhs);
      }

      if (pStat instanceof CFunctionCallAssignmentStatement) {
        return new CFunctionCallAssignmentStatement(
            loc, (CLeftHandSide) lhs, (CFunctionCallExpression) rhs);
      } else if (pStat instanceof JMethodInvocationAssignmentStatement) {
        return new JMethodInvocationAssignmentStatement(
            loc, (JLeftHandSide) lhs, (JMethodInvocationExpression) rhs);
      } else {
        throw new AssertionError();
      }

    } else {
      throw new AssertionError();
    }
  }

  private AExpressionStatement toStatement(AExpression pExpr) {
    if (pExpr instanceof CExpression) {
      return new CExpressionStatement(pExpr.getFileLocation(), (CExpression) pExpr);
    } else if (pExpr instanceof JExpression) {
      return new JExpressionStatement(pExpr.getFileLocation(), (JExpression) pExpr);
    } else {
      throw new AssertionError();
    }
  }

  private AFunctionCallStatement toStatement(AFunctionCallExpression pCall) {
    if (pCall instanceof CFunctionCallExpression) {
      return new CFunctionCallStatement(pCall.getFileLocation(), (CFunctionCallExpression) pCall);
    } else if (pCall instanceof JReferencedMethodInvocationExpression) {
      return new JMethodInvocationStatement(
          pCall.getFileLocation(), (JMethodInvocationExpression) pCall);
    } else {
      throw new AssertionError();
    }

  }

  private ADeclaration substitute(ADeclaration pDecl) {
    if (!(pDecl instanceof AVariableDeclaration)) {
      return null;
    }

    AInitializer init = ((AVariableDeclaration) pDecl).getInitializer();
    init = substituteInitializer(init);
    if (init == null) {
      return null;
    }

    FileLocation loc = pDecl.getFileLocation();
    Type type = pDecl.getType();
    String name = pDecl.getName();
    String orig = pDecl.getOrigName();
    String qual = pDecl.getQualifiedName();

    if (pDecl instanceof CVariableDeclaration) {
      boolean isG = pDecl.isGlobal();
      CStorageClass storage = ((CVariableDeclaration) pDecl).getCStorageClass();
      return new CVariableDeclaration(
          loc, isG, storage, (CType) type, name, orig, qual, (CInitializer) init);

    } else if (pDecl instanceof JFieldDeclaration) {
      throw new AssertionError("Java fields do not have initializers to substitute expression");
      // String simple = ((JFieldDeclaration) pDecl).getSimpleName();
      // boolean isF = ((JFieldDeclaration) pDecl).isFinal();
      // boolean isS = ((JFieldDeclaration) pDecl).isStatic();
      // boolean isT = ((JFieldDeclaration) pDecl).isTransient();
      // boolean isV = ((JFieldDeclaration) pDecl).isVolatile();
      // VisibilityModifier vis = ((JFieldDeclaration) pDecl).getVisibility();
      // return new JFieldDeclaration(loc, (JType) type, name, simple, isF, isS, isT, isV, vis);

    } else if (pDecl instanceof JVariableDeclaration) {
      boolean isF = ((JVariableDeclaration) pDecl).isFinal();
      return new JVariableDeclaration(
          loc, (JType) type, name, orig, qual, (JInitializer) init, isF);

    } else {
      throw new AssertionError();
    }
  }
}
