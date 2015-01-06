/*
 * CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2014  Dirk Beyer
 *  All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 *  CPAchecker web page:
 *    http://cpachecker.sosy-lab.org
 */
package org.sosy_lab.cpachecker.cpa.constraints.constraint.expressions;

/**
 * Visitor for {@link ConstraintExpression} subtypes.
 *
 * @param <MethodReturnT> the return type of all <code>visit</code> methods.
 */
public interface ConstraintExpressionVisitor<MethodReturnT> {

  MethodReturnT visit(ConstantConstraintExpression pExpression);

  MethodReturnT visit(AdditionExpression pExpression);

  MethodReturnT visit(MultiplicationExpression pExpression);

  MethodReturnT visit(DivisionExpression pExpression);

  MethodReturnT visit(ModuloExpression pExpression);

  MethodReturnT visit(BinaryAndExpression pExpression);

  MethodReturnT visit(BinaryNotExpression pExpression);

  MethodReturnT visit(BinaryOrExpression pExpression);

  MethodReturnT visit(BinaryXorExpression pExpression);

  MethodReturnT visit(ShiftRightExpression pExpression);

  MethodReturnT visit(ShiftLeftExpression pExpression);

  MethodReturnT visit(LogicalNotExpression pExpression);

  MethodReturnT visit(LessThanOrEqualExpression pExpression);

  MethodReturnT visit(LessThanExpression pExpression);

  MethodReturnT visit(EqualsExpression pExpression);

  MethodReturnT visit(LogicalOrExpression pExpression);

  MethodReturnT visit(LogicalAndExpression pExpression);
}
